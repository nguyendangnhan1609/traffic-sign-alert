import sys
from pathlib import Path

import pytest

ML = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ML))

import check_classes as C  # noqa: E402
import evaluate as E  # noqa: E402
import export_tflite as X  # noqa: E402
import extract_frames as F  # noqa: E402
import prepare_dataset as P  # noqa: E402
import train as T  # noqa: E402

NAMES = C.load_class_names(C.DEFAULT_YAML)


# ---- check_classes ----

def test_repo_classes_match_kotlin():
    assert C.main([]) == 0


def test_kotlin_parser_reads_fields():
    parsed = C.parse_kotlin_sign_classes(C.DEFAULT_KOTLIN.read_text(encoding="utf-8"))
    s50 = next(c for c in parsed if c.name == "SPEED_50")
    assert s50.code == "P.127-50" and s50.speed_limit_kmh == 50 and s50.category == "SPEED_LIMIT"
    assert [c.name for c in parsed] == NAMES


def test_mismatch_detected(tmp_path):
    bad = tmp_path / "classes.yaml"
    swapped = list(NAMES)
    swapped[0], swapped[1] = swapped[1], swapped[0]
    bad.write_text("names:\n" + "".join(f"  - {n}\n" for n in swapped), encoding="utf-8")
    assert C.main(["--yaml", str(bad)]) == 1
    assert C.compare_class_lists(NAMES, swapped)


# ---- prepare_dataset ----

def test_validate_label_lines():
    lines = [
        "2 0.5 0.5 0.1 0.1",
        "2 0.5 0.5 0.1 0.1",      # trùng
        "99 0.5 0.5 0.1 0.1",     # lớp ngoài khoảng
        "1 0.5 0.5 0.1",          # thiếu giá trị
        "1 0.98 0.5 0.1 0.1",     # tràn mép phải
        "1 0.9995 0.5 0.001 0.1", # tràn trong sai số -> cắt lại
        "x 0.5 0.5 0.1 0.1",
        "",
    ]
    boxes, issues = P.validate_label_lines(lines, len(NAMES))
    assert len(boxes) == 2 and len(issues) == 5
    assert boxes[1][3] <= 0.0011


def test_group_key():
    assert P.group_key("clipA__000120.jpg") == "clipA"
    assert P.group_key("a__b__c.jpg") == "a"
    assert P.group_key("single.jpg") == "single"


def test_split_groups_no_leak_and_deterministic():
    sizes = {f"clip{i}": 10 + i for i in range(30)}
    a = P.split_groups(sizes, (0.8, 0.1, 0.1), seed=1)
    b = P.split_groups(dict(reversed(list(sizes.items()))), (0.8, 0.1, 0.1), seed=1)
    assert a == b
    assert set(a) == set(sizes)
    per = {s: sum(sizes[g] for g, sp in a.items() if sp == s) for s in P.SPLITS}
    total = sum(sizes.values())
    assert abs(per["train"] / total - 0.8) < 0.05
    assert per["val"] > 0 and per["test"] > 0


def test_split_groups_bad_ratios():
    with pytest.raises(ValueError):
        P.split_groups({"a": 1}, (0.5, 0.5))


def test_end_to_end_dataset(tmp_path):
    from PIL import Image
    src = tmp_path / "src"
    src.mkdir()
    for clip in range(6):
        for f in range(5):
            name = f"clip{clip}__{f:06d}"
            Image.new("RGB", (32, 32)).save(src / f"{name}.jpg")
            (src / f"{name}.txt").write_text(f"{clip % 3} 0.5 0.5 0.2 0.2\n")
    Image.new("RGB", (32, 32)).save(src / "nolabel.jpg")
    out = tmp_path / "out"
    assert P.main(["--src", str(src), "--out", str(out), "--seed", "3"]) == 0
    import yaml
    data = yaml.safe_load((out / "data.yaml").read_text())
    assert list(data["names"].values()) == NAMES
    seen = {}
    for split in P.SPLITS:
        for img in (out / "images" / split).iterdir():
            seen.setdefault(P.group_key(img.name), set()).add(split)
            assert (out / "labels" / split / f"{img.stem}.txt").is_file()
    assert len(seen) == 6 and all(len(s) == 1 for s in seen.values())  # không rò rỉ nhóm
    assert T.dataset_names_mismatch(out / "data.yaml", C.DEFAULT_YAML) is None


# ---- train / export / evaluate / extract ----

def test_train_args_never_flip():
    args = T.parse_args(["--data", "x/data.yaml"])
    ta = T.build_train_args(args)
    assert ta["fliplr"] == 0.0 and ta["flipud"] == 0.0 and ta["imgsz"] == 640


def test_expected_output_shape():
    assert X.expected_output_shape(len(NAMES), 640) == [1, 4 + len(NAMES), 8400]


def test_speed_confusions():
    n = len(NAMES) + 1  # + lớp nền
    m = [[0] * n for _ in range(n)]
    i50, i80, inp = NAMES.index("SPEED_50"), NAMES.index("SPEED_80"), NAMES.index("NO_PARKING")
    m[i80][i50] = 4   # thật 50, đoán 80
    m[i50][i50] = 90  # đúng
    m[inp][i50] = 2   # nhầm sang biển khác loại: không tính ở đây
    assert E.speed_confusions(m, NAMES) == [("SPEED_50", "SPEED_80", 4)]


def test_frame_naming():
    assert F.frame_name(Path("trip__1.mp4"), 15) == "trip_1__000015.jpg"
    assert P.group_key(F.frame_name(Path("trip__1.mp4"), 15)) == "trip_1"
    assert F.frame_step(30, 0.5) == 15
    assert F.frame_step(0, 0.5) == 15
    assert F.frame_step(float("nan"), 1) == 30
