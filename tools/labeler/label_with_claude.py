#!/usr/bin/env python3
"""Gán nhãn SƠ BỘ biển báo bằng Claude API, ghi nhãn YOLO để người duyệt lại.

    export ANTHROPIC_API_KEY=...
    python tools/labeler/label_with_claude.py --images data/frames --limit 20 --dry-run
    python tools/labeler/label_with_claude.py --images data/frames --workers 4

Với mỗi ảnh tạo `<ảnh>.txt` (class cx cy w h, chuẩn hoá 0..1) và thêm một dòng vào `review.jsonl`
(độ tin cậy, đã duyệt chưa). Nhãn do model tạo CHỈ là bản nháp: phải duyệt bằng review_server.py trước khi huấn luyện.
"""
from __future__ import annotations

import argparse
import base64
import io
import json
import os
import random
import re
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "ml"))
from check_classes import DEFAULT_KOTLIN, load_class_names, parse_kotlin_sign_classes  # noqa: E402

DEFAULT_MODEL = "claude-sonnet-4-5"
MAX_SIDE = 1568
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp"}

EXTRA_DESCRIPTIONS = {
    "SUPPLEMENTARY_PLATE": "Biển phụ hình chữ nhật có chữ hoặc số (giờ hiệu lực, loại xe, khoảng cách...) đặt dưới biển chính",
    "OTHER_SIGN": "Biển báo giao thông khác không thuộc danh sách trên",
}

# (class_id, x_min, y_min, x_max, y_max, confidence)
Item = Tuple[int, float, float, float, float, float]


def class_descriptions(names: Sequence[str], kotlin_path: Path = DEFAULT_KOTLIN) -> Dict[str, str]:
    """Mô tả tiếng Việt cho từng lớp, lấy từ câu đọc và mã biển trong SignClass.kt."""
    desc: Dict[str, str] = {}
    try:
        for c in parse_kotlin_sign_classes(kotlin_path.read_text(encoding="utf-8")):
            if c.speech:
                desc[c.name] = f"{c.speech} (biển {c.code})"
    except OSError:
        pass
    for n in names:
        desc.setdefault(n, EXTRA_DESCRIPTIONS.get(n, n))
    return desc


def build_prompt(names: Sequence[str], descriptions: Dict[str, str]) -> str:
    lines = "\n".join(f"- {n}: {descriptions.get(n, n)}" for n in names)
    return (
        "Đây là khung hình từ camera hành trình ô tô tại Việt Nam. Hãy tìm TẤT CẢ biển báo giao thông nhìn thấy được.\n"
        f"Chỉ dùng các tên lớp sau:\n{lines}\n\n"
        "Quy tắc:\n"
        "- Biển không có trong danh sách: dùng OTHER_SIGN. Biển phụ có chữ: dùng SUPPLEMENTARY_PLATE, hộp riêng.\n"
        "- box = [x_min, y_min, x_max, y_max], chuẩn hoá 0..1 theo chiều rộng/cao ảnh, ôm sát mép biển.\n"
        "- Bỏ qua biển quá nhỏ hoặc mờ đến mức không đọc được; bỏ qua biển in trên xe khác hoặc biển quảng cáo.\n"
        "- confidence từ 0 đến 1, thể hiện mức chắc chắn về CẢ loại biển và vị trí.\n"
        "- Không có biển nào thì trả về [].\n"
        "Trả về DUY NHẤT một mảng JSON, không thêm chữ nào khác, dạng:\n"
        '[{"class_name": "SPEED_50", "box": [0.61, 0.32, 0.66, 0.41], "confidence": 0.9}]'
    )


def extract_json_array(text: str):
    """Lấy mảng JSON trong câu trả lời, chấp nhận code fence hoặc chữ thừa."""
    text = re.sub(r"```(?:json)?", "", text)
    start, end = text.find("["), text.rfind("]")
    if start < 0 or end <= start:
        raise ValueError("không có mảng JSON trong câu trả lời")
    data = json.loads(text[start:end + 1])
    if not isinstance(data, list):
        raise ValueError("JSON không phải mảng")
    return data


def validate_items(raw: list, names: Sequence[str], min_side: float = 0.004) -> Tuple[List[Item], List[str]]:
    """Kiểm tra và cắt hộp về 0..1; bỏ tên lớp lạ và hộp suy biến. Trả về (hợp lệ, lý do bỏ)."""
    index = {n: i for i, n in enumerate(names)}
    items: List[Item] = []
    dropped: List[str] = []
    for obj in raw:
        if not isinstance(obj, dict):
            dropped.append("không phải object"); continue
        name = obj.get("class_name")
        if name not in index:
            dropped.append(f"lớp lạ: {name!r}"); continue
        box = obj.get("box")
        if not (isinstance(box, (list, tuple)) and len(box) == 4 and all(isinstance(v, (int, float)) for v in box)):
            dropped.append(f"box sai định dạng: {box!r}"); continue
        x1, y1, x2, y2 = (min(1.0, max(0.0, float(v))) for v in box)
        if x2 < x1: x1, x2 = x2, x1
        if y2 < y1: y1, y2 = y2, y1
        if x2 - x1 < min_side or y2 - y1 < min_side:
            dropped.append(f"box quá nhỏ: {box!r}"); continue
        conf = obj.get("confidence", 0.5)
        conf = min(1.0, max(0.0, float(conf))) if isinstance(conf, (int, float)) else 0.5
        items.append((index[name], x1, y1, x2, y2, conf))
    return items, dropped


def to_yolo_lines(items: Sequence[Item]) -> str:
    out = []
    for cls, x1, y1, x2, y2, _ in items:
        out.append(f"{cls} {(x1 + x2) / 2:.6f} {(y1 + y2) / 2:.6f} {x2 - x1:.6f} {y2 - y1:.6f}")
    return "\n".join(out) + ("\n" if out else "")


def review_record(image_name: str, items: Sequence[Item], names: Sequence[str], model: str) -> dict:
    return {
        "image": image_name,
        "reviewed": False,
        "source": f"claude:{model}",
        "min_confidence": min((i[5] for i in items), default=1.0),
        "boxes": [{"class_name": names[i[0]], "box": [round(v, 5) for v in i[1:5]], "confidence": i[5]} for i in items],
    }


def encode_image(path: Path, max_side: int = MAX_SIDE) -> Tuple[str, str]:
    """Đọc ảnh, thu nhỏ nếu cần, trả về (base64, media_type)."""
    from PIL import Image

    with Image.open(path) as im:
        im = im.convert("RGB")
        if max(im.size) > max_side:
            s = max_side / max(im.size)
            im = im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, format="JPEG", quality=90)
    return base64.standard_b64encode(buf.getvalue()).decode("ascii"), "image/jpeg"


def call_claude(client, model: str, prompt: str, image_b64: str, media_type: str, retries: int = 5) -> str:
    import anthropic

    delay = 2.0
    for attempt in range(retries + 1):
        try:
            msg = client.messages.create(
                model=model,
                max_tokens=1500,
                messages=[{"role": "user", "content": [
                    {"type": "image", "source": {"type": "base64", "media_type": media_type, "data": image_b64}},
                    {"type": "text", "text": prompt},
                ]}],
            )
            return "".join(b.text for b in msg.content if getattr(b, "type", "") == "text")
        except (anthropic.RateLimitError, anthropic.InternalServerError, anthropic.APIConnectionError) as e:
            if attempt == retries:
                raise
            wait = delay * (2 ** attempt) + random.uniform(0, 1)
            print(f"  tạm lỗi ({type(e).__name__}), thử lại sau {wait:.0f}s", file=sys.stderr)
            time.sleep(wait)
    raise RuntimeError("unreachable")


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--images", type=Path, required=True)
    ap.add_argument("--labels", type=Path, default=None, help="thư mục ghi nhãn (mặc định: cùng thư mục ảnh)")
    ap.add_argument("--model", default=DEFAULT_MODEL, help="tên model Anthropic, có thể đổi")
    ap.add_argument("--classes", type=Path, default=REPO / "ml/classes.yaml")
    ap.add_argument("--limit", type=int, default=0, help="chỉ xử lý N ảnh (0 = tất cả)")
    ap.add_argument("--workers", type=int, default=3)
    ap.add_argument("--overwrite", action="store_true", help="gán lại cả ảnh đã có nhãn")
    ap.add_argument("--dry-run", action="store_true", help="chỉ in danh sách ảnh và prompt, không gọi API")
    args = ap.parse_args(argv)

    names = load_class_names(args.classes)
    prompt = build_prompt(names, class_descriptions(names))
    labels_dir = args.labels or args.images
    labels_dir.mkdir(parents=True, exist_ok=True)
    review_path = labels_dir / "review.jsonl"

    images = sorted(p for p in args.images.iterdir() if p.suffix.lower() in IMAGE_EXTS)
    todo = [p for p in images if args.overwrite or not (labels_dir / f"{p.stem}.txt").exists()]
    if args.limit:
        todo = todo[: args.limit]
    print(f"{len(images)} ảnh, cần gán {len(todo)} ảnh (model {args.model})")
    if args.dry_run:
        print("\n--- PROMPT ---\n" + prompt)
        for p in todo[:20]:
            print("  ", p.name)
        return 0
    if not todo:
        return 0
    if not os.environ.get("ANTHROPIC_API_KEY"):
        print("LỖI: chưa đặt biến môi trường ANTHROPIC_API_KEY", file=sys.stderr)
        return 2
    try:
        import anthropic
    except ImportError:
        print("LỖI: chưa cài anthropic. Chạy: pip install anthropic pillow", file=sys.stderr)
        return 2
    client = anthropic.Anthropic()
    lock = threading.Lock()
    done = failed = boxes_total = 0

    def work(path: Path):
        b64, media = encode_image(path)
        text = call_claude(client, args.model, prompt, b64, media)
        items, dropped = validate_items(extract_json_array(text), names)
        return path, items, dropped

    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        futures = [pool.submit(work, p) for p in todo]
        for fut in as_completed(futures):
            try:
                path, items, dropped = fut.result()
            except Exception as e:  # một ảnh lỗi không làm dừng cả lượt
                failed += 1
                print(f"  LỖI: {e}", file=sys.stderr)
                continue
            with lock:
                (labels_dir / f"{path.stem}.txt").write_text(to_yolo_lines(items), encoding="utf-8")
                with review_path.open("a", encoding="utf-8") as f:
                    f.write(json.dumps(review_record(path.name, items, names, args.model), ensure_ascii=False) + "\n")
                done += 1
                boxes_total += len(items)
                note = f", bỏ {len(dropped)}" if dropped else ""
                print(f"[{done + failed}/{len(todo)}] {path.name}: {len(items)} biển{note}")
    print(f"\nXong: {done} ảnh, {boxes_total} hộp, {failed} lỗi. Duyệt lại bằng:\n"
          f"  python tools/labeler/review_server.py --images {args.images}" +
          (f" --labels {labels_dir}" if args.labels else ""))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
