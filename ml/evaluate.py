#!/usr/bin/env python3
"""Đánh giá model trên tập test, nhấn mạnh lỗi nhầm giữa các biển giới hạn tốc độ.

    python ml/evaluate.py --weights ml/runs/signs/weights/best.pt --data data/dataset/data.yaml

Nhầm 50 thành 80 nguy hiểm hơn nhiều so với bỏ sót một biển, nên script in riêng các cặp nhầm này.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import List, Optional, Sequence, Tuple

SPEED_PREFIX = "SPEED_"


def speed_confusions(matrix, names: Sequence[str], min_count: float = 1) -> List[Tuple[str, str, int]]:
    """Từ ma trận nhầm lẫn Ultralytics (hàng = dự đoán, cột = thật, có thêm lớp nền ở cuối),
    trả về các cặp (thật, dự đoán, số lần) giữa hai biển tốc độ KHÁC nhau, nhiều nhất trước."""
    speed_ids = [i for i, n in enumerate(names) if n.startswith(SPEED_PREFIX)]
    out = []
    for p in speed_ids:
        for t in speed_ids:
            if p != t and matrix[p][t] >= min_count:
                out.append((names[t], names[p], int(matrix[p][t])))
    return sorted(out, key=lambda x: -x[2])


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--weights", type=Path, required=True)
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--split", default="test", choices=["val", "test"])
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--conf", type=float, default=0.45, help="ngưỡng giống app (YoloDecoder.scoreThreshold)")
    args = ap.parse_args(argv)

    try:
        from ultralytics import YOLO
    except ImportError:
        print("LỖI: chưa cài ultralytics. Chạy: pip install -r ml/requirements.txt", file=sys.stderr)
        return 2

    model = YOLO(str(args.weights))
    metrics = model.val(data=str(args.data), split=args.split, imgsz=args.imgsz, conf=args.conf, plots=True)
    names = [model.names[i] for i in range(len(model.names))]

    print(f"\n{'Lớp':<22}{'Precision':>10}{'Recall':>10}{'mAP50':>10}")
    box = metrics.box
    for idx, cls in enumerate(box.ap_class_index):
        p, r, ap50, _ = box.class_result(idx)
        flag = "  <-- recall thấp" if r < 0.8 else ""
        print(f"{names[int(cls)]:<22}{p:>10.3f}{r:>10.3f}{ap50:>10.3f}{flag}")
    print(f"\nmAP50 toàn bộ: {box.map50:.3f}   mAP50-95: {box.map:.3f}")

    pairs = speed_confusions(metrics.confusion_matrix.matrix, names)
    if pairs:
        print("\nCẢNH BÁO: nhầm giữa các biển giới hạn tốc độ (thật -> dự đoán):")
        for t, p, n in pairs:
            print(f"  {t} -> {p}: {n} lần")
        print("Cần thêm dữ liệu cho các lớp này trước khi dùng thật.")
    else:
        print("\nKhông có nhầm lẫn giữa các biển giới hạn tốc độ trên tập này.")
    print(f"Biểu đồ và ma trận nhầm lẫn: {metrics.save_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
