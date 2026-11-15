#!/usr/bin/env python3
"""Kiểm tra ml/classes.yaml khớp với enum SignClass trong core (tên lớp và thứ tự).

    python ml/check_classes.py

Mã thoát: 0 = khớp, 1 = lệch, 2 = không đọc được file.
"""
from __future__ import annotations

import argparse
import difflib
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional, Sequence

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_KOTLIN = REPO_ROOT / "core/src/main/kotlin/vn/trafficalert/core/SignClass.kt"
DEFAULT_YAML = Path(__file__).resolve().parent / "classes.yaml"

# Chuỗi "..." hoặc chú thích; dùng để xoá chú thích mà không đụng vào nội dung chuỗi.
_STRING_OR_COMMENT = re.compile(r'"(?:\\.|[^"\\\n])*"|/\*.*?\*/|//[^\n]*', re.S)
_ENUM_HEADER = re.compile(r"\benum\s+class\s+SignClass\b")
_ENTRY_NAME = re.compile(r"\s*([A-Za-z_][A-Za-z0-9_]*)")
_ENTRY_ARGS = re.compile(
    r'\(\s*"(?P<code>(?:\\.|[^"\\])*)"\s*,\s*SignCategory\.(?P<category>\w+)\s*,\s*'
    r'(?P<speed>\d+|null)\s*,\s*"(?P<speech>(?:\\.|[^"\\])*)"\s*,?\s*\)',
    re.S,
)


class ClassCheckError(Exception):
    """Không đọc/phân tích được file."""


@dataclass(frozen=True)
class KotlinSignClass:
    name: str
    code: Optional[str] = None
    category: Optional[str] = None
    speed_limit_kmh: Optional[int] = None
    speech: Optional[str] = None


def _strip_comments(src: str) -> str:
    return _STRING_OR_COMMENT.sub(lambda m: m.group(0) if m.group(0).startswith('"') else " ", src)


def _skip_balanced(src: str, i: int, open_ch: str, close_ch: str) -> int:
    """src[i] là dấu mở; trả về vị trí ngay sau dấu đóng tương ứng (bỏ qua nội dung chuỗi)."""
    depth = 0
    in_str = False
    while i < len(src):
        ch = src[i]
        if in_str:
            if ch == "\\":
                i += 1
            elif ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
        elif ch == open_ch:
            depth += 1
        elif ch == close_ch:
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    raise ClassCheckError(f"Thiếu dấu '{close_ch}' trong SignClass.kt")


def _split_entries(body: str) -> List[str]:
    """Tách phần khai báo hằng enum (trước dấu ';' đầu tiên) theo dấu phẩy ở mức ngoài cùng."""
    entries: List[str] = []
    depth = 0
    in_str = False
    start = 0
    i = 0
    while i < len(body):
        ch = body[i]
        if in_str:
            if ch == "\\":
                i += 1
            elif ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
        elif ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif depth == 0 and ch in ",;":
            entries.append(body[start:i])
            start = i + 1
            if ch == ";":
                return entries
        i += 1
    entries.append(body[start:])
    return entries


def parse_kotlin_sign_classes(source: str) -> List[KotlinSignClass]:
    """Đọc các hằng của `enum class SignClass` theo đúng thứ tự khai báo."""
    src = _strip_comments(source)
    header = _ENUM_HEADER.search(src)
    if not header:
        raise ClassCheckError("Không tìm thấy 'enum class SignClass' trong file Kotlin")
    i = header.end()
    while i < len(src) and src[i].isspace():
        i += 1
    if i < len(src) and src[i] == "(":
        i = _skip_balanced(src, i, "(", ")")
    brace = src.find("{", i)
    if brace < 0:
        raise ClassCheckError("Không tìm thấy thân enum SignClass")
    body = src[brace + 1 : _skip_balanced(src, brace, "{", "}") - 1]

    result: List[KotlinSignClass] = []
    for entry in _split_entries(body):
        if not entry.strip():
            continue
        m = _ENTRY_NAME.match(entry)
        if not m:
            raise ClassCheckError(f"Không đọc được hằng enum: {entry.strip()[:60]!r}")
        args = _ENTRY_ARGS.match(entry, m.end())
        if args:
            speed = args.group("speed")
            result.append(
                KotlinSignClass(
                    name=m.group(1),
                    code=args.group("code"),
                    category=args.group("category"),
                    speed_limit_kmh=None if speed == "null" else int(speed),
                    speech=args.group("speech"),
                )
            )
        else:
            result.append(KotlinSignClass(name=m.group(1)))
    if not result:
        raise ClassCheckError("Enum SignClass không có hằng nào")
    return result


def parse_kotlin_enum_names(source: str) -> List[str]:
    return [c.name for c in parse_kotlin_sign_classes(source)]


def load_class_names(path: Path = DEFAULT_YAML) -> List[str]:
    """Đọc `names` từ classes.yaml (danh sách, hoặc dict {id: tên} kiểu Ultralytics)."""
    import yaml

    try:
        data = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    except OSError as e:
        raise ClassCheckError(f"Không đọc được {path}: {e}") from e
    except yaml.YAMLError as e:
        raise ClassCheckError(f"{path} không phải YAML hợp lệ: {e}") from e
    names = data.get("names") if isinstance(data, dict) else None
    if isinstance(names, dict):
        keys = sorted(names)
        if keys != list(range(len(keys))):
            raise ClassCheckError(f"{path}: khoá của `names` phải là 0..{len(keys) - 1} liên tục")
        names = [names[k] for k in keys]
    if not isinstance(names, list) or not names:
        raise ClassCheckError(f"{path}: thiếu danh sách `names`")
    if not all(isinstance(n, str) and n for n in names):
        raise ClassCheckError(f"{path}: mọi phần tử của `names` phải là chuỗi")
    return list(names)


def compare_class_lists(kotlin_names: Sequence[str], yaml_names: Sequence[str]) -> List[str]:
    """Trả về các dòng mô tả khác biệt; danh sách rỗng nghĩa là khớp hoàn toàn."""
    problems: List[str] = []
    for label, names in (("SignClass.kt", kotlin_names), ("classes.yaml", yaml_names)):
        dups = sorted({n for n in names if list(names).count(n) > 1})
        if dups:
            problems.append(f"Tên lớp bị lặp trong {label}: {', '.join(dups)}")
    if list(kotlin_names) == list(yaml_names):
        return problems

    problems.append(
        f"Danh sách lớp KHÔNG khớp (SignClass.kt: {len(kotlin_names)} lớp, classes.yaml: {len(yaml_names)} lớp)"
    )
    missing = [n for n in kotlin_names if n not in yaml_names]
    extra = [n for n in yaml_names if n not in kotlin_names]
    if missing:
        problems.append(f"Thiếu trong classes.yaml: {', '.join(missing)}")
    if extra:
        problems.append(f"Thừa trong classes.yaml (không có trong SignClass.kt): {', '.join(extra)}")
    for i in range(max(len(kotlin_names), len(yaml_names))):
        k = kotlin_names[i] if i < len(kotlin_names) else "(không có)"
        y = yaml_names[i] if i < len(yaml_names) else "(không có)"
        if k != y:
            problems.append(f"  id {i}: SignClass.kt = {k}  |  classes.yaml = {y}")
    problems.append("Diff (- SignClass.kt, + classes.yaml):")
    problems.extend(
        "  " + line
        for line in difflib.unified_diff(
            list(kotlin_names), list(yaml_names), "SignClass.kt", "classes.yaml", lineterm="", n=1
        )
    )
    return problems


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--kotlin", type=Path, default=DEFAULT_KOTLIN, help="đường dẫn SignClass.kt")
    ap.add_argument("--yaml", type=Path, default=DEFAULT_YAML, help="đường dẫn classes.yaml")
    args = ap.parse_args(argv)
    try:
        try:
            source = args.kotlin.read_text(encoding="utf-8")
        except OSError as e:
            raise ClassCheckError(f"Không đọc được {args.kotlin}: {e}") from e
        kotlin_names = parse_kotlin_enum_names(source)
        yaml_names = load_class_names(args.yaml)
    except ClassCheckError as e:
        print(f"LỖI: {e}", file=sys.stderr)
        return 2
    problems = compare_class_lists(kotlin_names, yaml_names)
    if problems:
        print("LỖI: ml/classes.yaml lệch với SignClass.kt — sửa cho khớp trước khi huấn luyện/xuất model.", file=sys.stderr)
        for p in problems:
            print(p, file=sys.stderr)
        return 1
    print(f"OK: {len(yaml_names)} lớp, khớp tên và thứ tự với SignClass.kt")
    return 0


if __name__ == "__main__":
    sys.exit(main())
