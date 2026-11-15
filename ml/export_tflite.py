#!/usr/bin/env python3
"""Xuất best.pt sang TFLite float16 và chép vào app.

    python ml/export_tflite.py --weights ml/runs/signs/weights/best.pt

Kết quả: app/src/main/assets/signs.tflite, đầu vào [1, 640, 640, 3], đầu ra [1, 4 + số lớp, số anchor].
Nên chạy trên Google Colab hoặc Linux: Ultralytics sẽ tự cài tensorflow và công cụ chuyển đổi lần đầu.
"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path
from typing import Optional, Sequence

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
DEFAULT_DEST = REPO / "app/src/main/assets/signs.tflite"


def expected_output_shape(num_classes: int, imgsz: int = 640) -> list:
    """Số anchor của YOLOv8/11 với 3 tầng stride 8, 16, 32."""
    anchors = sum((imgsz // s) ** 2 for s in (8, 16, 32))
    return [1, 4 + num_classes, anchors]


def find_tflite(export_result: Optional[str], weights: Path) -> Optional[Path]:
    if export_result and Path(export_result).is_file():
        return Path(export_result)
    candidates = sorted(weights.parent.glob(f"{weights.stem}_saved_model/*float16.tflite"))
    return candidates[0] if candidates else None


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--weights", type=Path, required=True)
    ap.add_argument("--imgsz", type=int, default=640, help="phải trùng kích thước đầu vào app dùng")
    ap.add_argument("--dest", type=Path, default=DEFAULT_DEST)
    ap.add_argument("--classes", type=Path, default=HERE / "classes.yaml")
    args = ap.parse_args(argv)

    sys.path.insert(0, str(HERE))
    from check_classes import load_class_names

    names = load_class_names(args.classes)
    if not args.weights.is_file():
        print(f"LỖI: không có file {args.weights}", file=sys.stderr)
        return 2
    try:
        from ultralytics import YOLO
    except ImportError:
        print("LỖI: chưa cài ultralytics. Chạy: pip install -r ml/requirements.txt", file=sys.stderr)
        return 2

    model = YOLO(str(args.weights))
    model_names = list(model.names.values()) if isinstance(model.names, dict) else list(model.names)
    if model_names != names:
        print("LỖI: danh sách lớp trong model khác classes.yaml. Huấn luyện lại với data.yaml mới.", file=sys.stderr)
        return 1

    result = model.export(format="tflite", imgsz=args.imgsz, half=True, nms=False)
    tflite = find_tflite(result, args.weights)
    if tflite is None:
        print("LỖI: không tìm thấy file .tflite sau khi xuất", file=sys.stderr)
        return 1
    args.dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(tflite, args.dest)
    size_mb = args.dest.stat().st_size / 1e6
    print(f"Đã chép {tflite.name} -> {args.dest} ({size_mb:.1f} MB)")
    print(f"App cần đầu vào [1, {args.imgsz}, {args.imgsz}, 3] float32, đầu ra {expected_output_shape(len(names), args.imgsz)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
