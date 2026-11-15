#!/usr/bin/env python3
"""Cắt khung hình từ video camera hành trình để gán nhãn.

    python ml/extract_frames.py --videos data/videos --out data/frames --every 0.5

Tên file: <tên video>__<chỉ số khung>.jpg. Phần trước `__` dùng để nhóm khi chia train/val/test,
nên đừng dùng `__` trong tên video.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Optional, Sequence

VIDEO_EXTS = {".mp4", ".mov", ".avi", ".mkv", ".m4v"}


def frame_name(video: Path, index: int) -> str:
    stem = video.stem.replace("__", "_")
    return f"{stem}__{index:06d}.jpg"


def frame_step(fps: float, every_seconds: float) -> int:
    if fps <= 0 or fps != fps:  # 0 hoặc NaN: không biết fps
        fps = 30.0
    return max(1, round(fps * every_seconds))


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--videos", type=Path, required=True, help="thư mục video (hoặc một file video)")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--every", type=float, default=0.5, help="lấy một khung mỗi N giây")
    ap.add_argument("--max-side", type=int, default=1920, help="thu nhỏ nếu cạnh dài hơn")
    args = ap.parse_args(argv)

    try:
        import cv2
    except ImportError:
        print("LỖI: chưa cài opencv-python", file=sys.stderr)
        return 2

    videos = [args.videos] if args.videos.is_file() else sorted(
        p for p in args.videos.iterdir() if p.suffix.lower() in VIDEO_EXTS
    )
    if not videos:
        print("Không tìm thấy video nào", file=sys.stderr)
        return 1
    args.out.mkdir(parents=True, exist_ok=True)
    total = 0
    for video in videos:
        cap = cv2.VideoCapture(str(video))
        step = frame_step(cap.get(cv2.CAP_PROP_FPS), args.every)
        idx = saved = 0
        while True:
            ok = cap.grab()
            if not ok:
                break
            if idx % step == 0:
                ok, frame = cap.retrieve()
                if ok:
                    h, w = frame.shape[:2]
                    if max(h, w) > args.max_side:
                        s = args.max_side / max(h, w)
                        frame = cv2.resize(frame, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA)
                    cv2.imwrite(str(args.out / frame_name(video, idx)), frame, [cv2.IMWRITE_JPEG_QUALITY, 92])
                    saved += 1
            idx += 1
        cap.release()
        total += saved
        print(f"{video.name}: {saved} khung")
    print(f"Tổng cộng {total} khung -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
