#!/usr/bin/env python3
"""Huấn luyện YOLO (Ultralytics) cho biển báo giao thông Việt Nam.

    python ml/train.py --data data/dataset/data.yaml
    python ml/train.py --data data/dataset/data.yaml --model yolo11s.pt --epochs 150 --batch 32

Tăng cường dữ liệu được chọn riêng cho biển báo: KHÔNG lật ảnh (biển/chữ số bị lật là sai nghĩa),
đổi màu/tỉ lệ/tịnh tiến vừa phải, bật mosaic.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Any, Dict, Optional, Sequence

DEFAULT_MODEL = "yolo11n.pt"

# Tham số tăng cường dữ liệu (ghi đè mặc định của Ultralytics).
AUGMENTATION: Dict[str, Any] = {
    "fliplr": 0.0,       # lật ngang biến "cấm rẽ trái" thành "cấm rẽ phải", số 50 thành hình vô nghĩa
    "flipud": 0.0,
    "hsv_h": 0.01,       # đổi sắc độ rất ít: màu đỏ/xanh của biển mang ý nghĩa
    "hsv_s": 0.5,
    "hsv_v": 0.4,        # sáng/tối: nắng gắt, bóng râm, chiều tối
    "degrees": 3.0,      # camera gắn hơi nghiêng
    "translate": 0.1,
    "scale": 0.4,        # biển ở xa/gần
    "shear": 0.0,
    "perspective": 0.0,
    "mosaic": 1.0,
    "close_mosaic": 10,  # tắt mosaic 10 epoch cuối
    "mixup": 0.0,
    "copy_paste": 0.0,
}


def build_train_args(args: argparse.Namespace) -> Dict[str, Any]:
    """Tham số truyền cho `YOLO.train()`."""
    train_args: Dict[str, Any] = {
        "data": str(args.data),
        "imgsz": args.imgsz,
        "epochs": args.epochs,
        "batch": args.batch,
        "patience": args.patience,
        "workers": args.workers,
        "seed": args.seed,
        "project": str(args.project),
        "name": args.name,
        "exist_ok": args.exist_ok,
        "plots": True,
    }
    if args.device is not None:
        train_args["device"] = args.device
    train_args.update(AUGMENTATION)
    return train_args


def dataset_names_mismatch(data_yaml: Path, classes_yaml: Path) -> Optional[str]:
    """Trả về thông báo lỗi nếu `names` trong data.yaml khác classes.yaml, ngược lại None."""
    from check_classes import compare_class_lists, load_class_names

    expected = load_class_names(classes_yaml)
    actual = load_class_names(data_yaml)
    if actual == expected:
        return None
    return "\n".join(
        [f"`names` trong {data_yaml} khác {classes_yaml} — chạy lại prepare_dataset.py."]
        + [p.replace("SignClass.kt", "classes.yaml (gốc)").replace("classes.yaml =", "data.yaml =")
           for p in compare_class_lists(expected, actual)[:1]]
    )


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    here = Path(__file__).resolve().parent
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--data", type=Path, required=True, help="data.yaml do prepare_dataset.py tạo")
    ap.add_argument("--model", default=DEFAULT_MODEL,
                    help=f"trọng số khởi đầu (mặc định {DEFAULT_MODEL}; ví dụ yolo11s.pt chính xác hơn nhưng chậm hơn)")
    ap.add_argument("--imgsz", type=int, default=640, help="app dùng đầu vào 640; đổi thì phải sửa cả app")
    ap.add_argument("--epochs", type=int, default=100)
    ap.add_argument("--batch", type=int, default=16, help="-1 để Ultralytics tự chọn theo bộ nhớ GPU")
    ap.add_argument("--patience", type=int, default=30, help="dừng sớm nếu không cải thiện sau N epoch")
    ap.add_argument("--device", default=None, help="ví dụ 0 (GPU), cpu, mps; mặc định tự chọn")
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--project", type=Path, default=here / "runs")
    ap.add_argument("--name", default="signs")
    ap.add_argument("--exist-ok", action="store_true", help="ghi đè thư mục runs/<name> nếu đã có")
    ap.add_argument("--resume", type=Path, default=None, metavar="LAST_PT",
                    help="tiếp tục một lần huấn luyện bị ngắt từ file last.pt")
    ap.add_argument("--classes", type=Path, default=here / "classes.yaml")
    return ap.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    if not args.data.is_file():
        print(f"LỖI: không có file {args.data}", file=sys.stderr)
        return 2
    problem = dataset_names_mismatch(args.data, args.classes)
    if problem:
        print(f"LỖI: {problem}", file=sys.stderr)
        return 1

    try:
        from ultralytics import YOLO  # nhập trễ: file này import được mà không cần ultralytics/torch
    except ImportError:
        print("LỖI: chưa cài ultralytics. Chạy: pip install -r ml/requirements.txt", file=sys.stderr)
        return 2

    if args.resume:
        model = YOLO(str(args.resume))
        model.train(resume=True)
    else:
        model = YOLO(args.model)
        model.train(**build_train_args(args))

    save_dir = Path(model.trainer.save_dir)
    best = save_dir / "weights" / "best.pt"
    print(f"\nXong. Trọng số tốt nhất: {best}")
    print(f"Tiếp theo:\n  python ml/evaluate.py --weights {best} --data {args.data}"
          f"\n  python ml/export_tflite.py --weights {best}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
