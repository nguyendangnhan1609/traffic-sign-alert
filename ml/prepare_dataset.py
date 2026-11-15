#!/usr/bin/env python3
"""Chuẩn bị bộ dữ liệu Ultralytics YOLO từ thư mục ảnh + nhãn YOLO (.txt) đã được người duyệt.

    python ml/prepare_dataset.py --src data/frames --out data/dataset

- Kiểm tra từng dòng nhãn (id lớp hợp lệ, toạ độ trong 0..1), loại và báo cáo dòng lỗi.
- Chia train/val/test (mặc định 80/10/10, có seed) THEO NHÓM: nhóm = phần tên file trước `__` đầu tiên
  (tên video), để các khung hình của cùng một đoạn video không rò rỉ giữa các tập.
- Ghi cấu trúc images/{train,val,test}, labels/{train,val,test} và data.yaml (names lấy từ classes.yaml).
- In số lượng đối tượng theo lớp, cảnh báo lớp có ít hơn 200 mẫu.

Ảnh không có file .txt bị bỏ qua (coi như chưa gán nhãn). Ảnh có file .txt RỖNG được giữ làm ảnh nền.
"""
from __future__ import annotations

import argparse
import json
import math
import random
import shutil
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
SPLITS = ("train", "val", "test")
GROUP_SEPARATOR = "__"
DEFAULT_MIN_INSTANCES = 200

Box = Tuple[int, float, float, float, float]  # (class_id, cx, cy, w, h), chuẩn hoá 0..1
Issue = Tuple[int, str, str]  # (số dòng, nội dung dòng, lý do)


def validate_label_lines(lines: Sequence[str], num_classes: int, tol: float = 1e-3) -> Tuple[List[Box], List[Issue]]:
    """Kiểm tra các dòng nhãn YOLO `class cx cy w h`.

    Hộp tràn mép ảnh trong phạm vi `tol` (sai số làm tròn) được cắt lại; tràn nhiều hơn thì loại.
    Trả về (các hộp hợp lệ, các dòng bị loại kèm lý do).
    """
    valid: List[Box] = []
    issues: List[Issue] = []
    seen = set()
    for line_no, raw in enumerate(lines, start=1):
        line = raw.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) != 5:
            issues.append((line_no, line, f"cần đúng 5 giá trị, có {len(parts)}"))
            continue
        try:
            cls_f = float(parts[0])
            cx, cy, w, h = (float(p) for p in parts[1:])
        except ValueError:
            issues.append((line_no, line, "có giá trị không phải số"))
            continue
        if not all(math.isfinite(v) for v in (cls_f, cx, cy, w, h)):
            issues.append((line_no, line, "có giá trị NaN/vô cực"))
            continue
        if cls_f != int(cls_f):
            issues.append((line_no, line, "id lớp không phải số nguyên"))
            continue
        cls = int(cls_f)
        if not 0 <= cls < num_classes:
            issues.append((line_no, line, f"id lớp {cls} ngoài khoảng 0..{num_classes - 1}"))
            continue
        if w <= 0 or h <= 0:
            issues.append((line_no, line, "chiều rộng/cao phải > 0"))
            continue
        x0, y0, x1, y1 = cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2
        if min(x0, y0) < -tol or max(x1, y1) > 1 + tol:
            issues.append((line_no, line, "toạ độ nằm ngoài 0..1"))
            continue
        x0, y0, x1, y1 = max(0.0, x0), max(0.0, y0), min(1.0, x1), min(1.0, y1)
        if x1 <= x0 or y1 <= y0:
            issues.append((line_no, line, "hộp rỗng sau khi cắt về 0..1"))
            continue
        box = (cls, round((x0 + x1) / 2, 6), round((y0 + y1) / 2, 6), round(x1 - x0, 6), round(y1 - y0, 6))
        if box in seen:
            issues.append((line_no, line, "trùng với một dòng trước"))
            continue
        seen.add(box)
        valid.append(box)
    return valid, issues


def format_label_lines(boxes: Sequence[Box]) -> str:
    return "".join(f"{c} {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}\n" for c, cx, cy, w, h in boxes)


def group_key(filename: str) -> str:
    """`clipA__000120.jpg` -> `clipA`. File không có `__` tự thành một nhóm riêng."""
    stem = Path(filename).stem
    return stem.split(GROUP_SEPARATOR, 1)[0] if GROUP_SEPARATOR in stem else stem


def split_groups(
    group_sizes: Dict[str, int], ratios: Sequence[float] = (0.8, 0.1, 0.1), seed: int = 42
) -> Dict[str, str]:
    """Gán mỗi nhóm vào đúng MỘT tập, sao cho số ảnh mỗi tập gần tỉ lệ yêu cầu nhất có thể.

    Kết quả chỉ phụ thuộc (tập nhóm, kích thước, tỉ lệ, seed) — không phụ thuộc thứ tự đầu vào.
    """
    if len(ratios) != 3 or any(r < 0 for r in ratios) or sum(ratios) <= 0:
        raise ValueError("ratios phải gồm 3 số không âm (train, val, test) có tổng > 0")
    total_ratio = float(sum(ratios))
    total = sum(group_sizes.values())
    target = {s: total * r / total_ratio for s, r in zip(SPLITS, ratios)}
    active = [s for s, r in zip(SPLITS, ratios) if r > 0]

    names = sorted(group_sizes)
    random.Random(seed).shuffle(names)
    names.sort(key=lambda g: -group_sizes[g])  # sắp xếp ổn định: nhóm lớn trước, cùng cỡ thì theo thứ tự xáo trộn

    current = {s: 0 for s in SPLITS}
    members: Dict[str, List[str]] = {s: [] for s in SPLITS}
    for g in names:
        best = max(active, key=lambda s: (target[s] - current[s], -SPLITS.index(s)))
        members[best].append(g)
        current[best] += group_sizes[g]

    # Nếu đủ nhóm thì không để tập nào (có tỉ lệ > 0) bị rỗng: chuyển nhóm nhỏ nhất từ tập nhiều nhóm nhất.
    if len(names) >= len(active):
        for s in active:
            if members[s]:
                continue
            donor = max(active, key=lambda d: len(members[d]))
            g = min(members[donor], key=lambda x: group_sizes[x])
            members[donor].remove(g)
            members[s].append(g)
    return {g: s for s in SPLITS for g in members[s]}


def load_review_status(review_path: Path) -> Dict[str, bool]:
    """Đọc review.jsonl của tools/labeler: tên ảnh -> đã được người duyệt chưa (bản ghi sau cùng thắng)."""
    status: Dict[str, bool] = {}
    try:
        text = Path(review_path).read_text(encoding="utf-8")
    except OSError:
        return status
    for line in text.splitlines():
        try:
            rec = json.loads(line)
        except ValueError:
            continue
        if isinstance(rec, dict) and isinstance(rec.get("image"), str):
            status[rec["image"]] = bool(rec.get("reviewed", False))
    return status


@dataclass
class Sample:
    image: Path
    boxes: List[Box]

    @property
    def group(self) -> str:
        return group_key(self.image.name)


@dataclass
class ScanReport:
    samples: List[Sample] = field(default_factory=list)
    unlabeled: List[str] = field(default_factory=list)
    excluded: List[Tuple[str, str]] = field(default_factory=list)  # (ảnh, lý do)
    issues: List[Tuple[str, int, str, str]] = field(default_factory=list)  # (file nhãn, dòng, nội dung, lý do)


def scan_source(
    src: Path, labels_dir: Optional[Path], num_classes: int, strict: bool = False
) -> ScanReport:
    """Đọc và kiểm tra mọi cặp ảnh/nhãn trong `src` (không đệ quy)."""
    labels_dir = labels_dir or src
    report = ScanReport()
    images = sorted(p for p in src.iterdir() if p.is_file() and p.suffix.lower() in IMAGE_EXTS)
    stems = Counter(p.stem for p in images)
    for img in images:
        if stems[img.stem] > 1:
            report.excluded.append((img.name, "nhiều ảnh trùng tên (khác đuôi) nên không biết nhãn thuộc ảnh nào"))
            continue
        label = labels_dir / f"{img.stem}.txt"
        if not label.is_file():
            report.unlabeled.append(img.name)
            continue
        lines = label.read_text(encoding="utf-8", errors="replace").splitlines()
        boxes, issues = validate_label_lines(lines, num_classes)
        report.issues.extend((label.name, n, text, why) for n, text, why in issues)
        if issues and not boxes:
            # Không còn hộp hợp lệ nào: nếu giữ lại ảnh sẽ thành "ảnh nền" sai.
            report.excluded.append((img.name, "mọi dòng nhãn đều lỗi"))
            continue
        if issues and strict:
            report.excluded.append((img.name, "có dòng nhãn lỗi (--strict)"))
            continue
        report.samples.append(Sample(img, boxes))
    return report


def count_instances(samples: Sequence[Sample], assignment: Dict[str, str], num_classes: int) -> Dict[str, List[int]]:
    counts = {s: [0] * num_classes for s in SPLITS}
    for sample in samples:
        for box in sample.boxes:
            counts[assignment[sample.group]][box[0]] += 1
    return counts


def low_count_classes(
    counts: Dict[str, List[int]], names: Sequence[str], min_instances: int = DEFAULT_MIN_INSTANCES
) -> List[Tuple[str, int]]:
    totals = [sum(counts[s][i] for s in SPLITS) for i in range(len(names))]
    return [(names[i], t) for i, t in enumerate(totals) if t < min_instances]


def write_dataset(
    samples: Sequence[Sample], assignment: Dict[str, str], out: Path, names: Sequence[str], symlink: bool = False
) -> Path:
    for split in SPLITS:
        (out / "images" / split).mkdir(parents=True, exist_ok=True)
        (out / "labels" / split).mkdir(parents=True, exist_ok=True)
    for sample in samples:
        split = assignment[sample.group]
        dst = out / "images" / split / sample.image.name
        if symlink:
            dst.symlink_to(sample.image.resolve())
        else:
            shutil.copy2(sample.image, dst)
        (out / "labels" / split / f"{sample.image.stem}.txt").write_text(
            format_label_lines(sample.boxes), encoding="utf-8"
        )
    import yaml

    data = {
        "path": str(out.resolve()),
        "train": "images/train",
        "val": "images/val",
        "test": "images/test",
        "names": {i: n for i, n in enumerate(names)},
    }
    data_yaml = out / "data.yaml"
    data_yaml.write_text(yaml.safe_dump(data, sort_keys=False, allow_unicode=True), encoding="utf-8")
    return data_yaml


def print_counts(counts: Dict[str, List[int]], names: Sequence[str], min_instances: int) -> None:
    width = max(len(n) for n in names)
    print(f"\n{'id':>3}  {'lớp':<{width}}  {'train':>7} {'val':>7} {'test':>7} {'tổng':>7}")
    for i, name in enumerate(names):
        row = [counts[s][i] for s in SPLITS]
        total = sum(row)
        flag = f"   <-- ít hơn {min_instances}" if total < min_instances else ""
        print(f"{i:>3}  {name:<{width}}  {row[0]:>7} {row[1]:>7} {row[2]:>7} {total:>7}{flag}")


def main(argv: Optional[Sequence[str]] = None) -> int:
    from check_classes import ClassCheckError, load_class_names

    here = Path(__file__).resolve().parent
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--src", type=Path, required=True, help="thư mục chứa ảnh (và nhãn .txt nếu không dùng --labels)")
    ap.add_argument("--labels", type=Path, default=None, help="thư mục nhãn .txt nếu để riêng (mặc định: cùng --src)")
    ap.add_argument("--out", type=Path, required=True, help="thư mục dataset đầu ra")
    ap.add_argument("--classes", type=Path, default=here / "classes.yaml")
    ap.add_argument("--split", type=float, nargs=3, default=(0.8, 0.1, 0.1), metavar=("TRAIN", "VAL", "TEST"))
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--min-instances", type=int, default=DEFAULT_MIN_INSTANCES, help="ngưỡng cảnh báo lớp ít mẫu")
    ap.add_argument("--strict", action="store_true", help="loại cả ảnh nếu có bất kỳ dòng nhãn lỗi nào")
    ap.add_argument("--only-reviewed", action="store_true",
                    help="chỉ lấy ảnh đã được đánh dấu duyệt trong review.jsonl (review_server.py)")
    ap.add_argument("--symlink", action="store_true", help="tạo symlink thay vì chép ảnh")
    ap.add_argument("--force", action="store_true", help="xoá images/, labels/, data.yaml cũ trong --out nếu có")
    args = ap.parse_args(argv)

    try:
        names = load_class_names(args.classes)
    except ClassCheckError as e:
        print(f"LỖI: {e}", file=sys.stderr)
        return 2
    if not args.src.is_dir():
        print(f"LỖI: không có thư mục {args.src}", file=sys.stderr)
        return 2

    report = scan_source(args.src, args.labels, len(names), strict=args.strict)
    samples = report.samples

    review_file = (args.labels or args.src) / "review.jsonl"
    if review_file.is_file():
        status = load_review_status(review_file)
        pending = [s for s in samples if s.image.name in status and not status[s.image.name]]
        if args.only_reviewed:
            samples = [s for s in samples if status.get(s.image.name, False)]
            print(f"--only-reviewed: giữ {len(samples)} ảnh đã duyệt, bỏ {len(report.samples) - len(samples)} ảnh.")
        elif pending:
            print(f"CẢNH BÁO: {len(pending)} ảnh có nhãn do máy gán nhưng CHƯA được người duyệt "
                  f"(theo {review_file.name}). Hãy duyệt bằng review_server.py hoặc dùng --only-reviewed.")
    elif args.only_reviewed:
        print(f"LỖI: --only-reviewed cần file {review_file}", file=sys.stderr)
        return 2

    print(f"Ảnh dùng được: {len(samples)} | chưa có nhãn (bỏ qua): {len(report.unlabeled)} | "
          f"bị loại: {len(report.excluded)} | dòng nhãn lỗi: {len(report.issues)}")
    for name, why in report.excluded[:50]:
        print(f"  LOẠI ẢNH {name}: {why}")
    for file, line_no, text, why in report.issues[:50]:
        print(f"  BỎ DÒNG {file}:{line_no}: {why}  [{text}]")
    hidden = max(0, len(report.excluded) - 50) + max(0, len(report.issues) - 50)
    if hidden:
        print(f"  ... và {hidden} mục khác")
    if report.issues and not args.strict:
        print("CẢNH BÁO: ảnh có dòng bị bỏ vẫn được giữ với các hộp còn lại — biển ở dòng bị bỏ sẽ thành "
              "'không có nhãn'. Nên sửa file nhãn rồi chạy lại, hoặc dùng --strict.")
    if not samples:
        print("LỖI: không có ảnh nào dùng được.", file=sys.stderr)
        return 1

    out = args.out
    stale = [p for p in (out / "images", out / "labels", out / "data.yaml") if p.exists()]
    if stale and not args.force:
        print(f"LỖI: {out} đã có dataset. Dùng --force để ghi đè.", file=sys.stderr)
        return 2
    for p in stale:
        shutil.rmtree(p) if p.is_dir() else p.unlink()

    group_sizes = Counter(s.group for s in samples)
    assignment = split_groups(dict(group_sizes), args.split, args.seed)
    data_yaml = write_dataset(samples, assignment, out, names, symlink=args.symlink)

    per_split = Counter(assignment[s.group] for s in samples)
    groups_per_split = Counter(assignment.values())
    print(f"\n{len(group_sizes)} nhóm (video). Số ảnh: " +
          ", ".join(f"{s}={per_split[s]} ({groups_per_split[s]} nhóm)" for s in SPLITS))
    for split, ratio in zip(SPLITS, args.split):
        if ratio > 0 and per_split[split] == 0:
            print(f"CẢNH BÁO: tập {split} RỖNG vì quá ít nhóm. Cần ít nhất 3 video khác nhau "
                  f"(tên ảnh dạng <video>__<khung>.jpg).")

    counts = count_instances(samples, assignment, len(names))
    print_counts(counts, names, args.min_instances)
    low = low_count_classes(counts, names, args.min_instances)
    if low:
        print(f"\nCẢNH BÁO: {len(low)} lớp có ít hơn {args.min_instances} mẫu, model sẽ nhận diện kém các lớp này:")
        print("  " + ", ".join(f"{n} ({c})" for n, c in low))
    for split in ("val", "test"):
        empty = [names[i] for i, c in enumerate(counts[split]) if c == 0 and counts["train"][i] > 0]
        if empty:
            print(f"CẢNH BÁO: tập {split} không có mẫu nào của: {', '.join(empty)} (không đánh giá được các lớp này)")
    print(f"\nĐã ghi {data_yaml}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
