import json
import sys
import threading
import urllib.error
import urllib.request

import pytest
from http.server import ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import label_with_claude as L  # noqa: E402
import review_server as R  # noqa: E402

NAMES = L.load_class_names(L.REPO / "ml/classes.yaml")


def test_descriptions_cover_every_class():
    desc = L.class_descriptions(NAMES)
    assert set(desc) == set(NAMES)
    assert "50" in desc["SPEED_50"] and "P.127" in desc["SPEED_50"]
    prompt = L.build_prompt(NAMES, desc)
    for n in NAMES:
        assert n in prompt


def test_extract_json_tolerates_fences_and_text():
    text = 'Đây là kết quả:\n```json\n[{"class_name": "SPEED_50", "box": [0.1,0.1,0.2,0.2], "confidence": 0.9}]\n```'
    assert L.extract_json_array(text)[0]["class_name"] == "SPEED_50"
    assert L.extract_json_array("[]") == []


def test_extract_json_rejects_garbage():
    with pytest.raises(ValueError):
        L.extract_json_array("không có biển nào")


def test_validate_items_clamps_swaps_and_drops():
    raw = [
        {"class_name": "SPEED_50", "box": [0.2, 0.3, 0.1, 0.1], "confidence": 0.8},   # đảo góc
        {"class_name": "NO_PARKING", "box": [-0.1, 0.5, 0.2, 1.3], "confidence": 2},  # tràn mép
        {"class_name": "SPEED_55", "box": [0.1, 0.1, 0.2, 0.2]},                     # lớp lạ
        {"class_name": "SPEED_60", "box": [0.1, 0.1, 0.1001, 0.2]},                  # quá hẹp
        {"class_name": "SPEED_60", "box": "0.1,0.1"},
        "rác",
    ]
    items, dropped = L.validate_items(raw, NAMES)
    assert len(items) == 2 and len(dropped) == 4
    cls, x1, y1, x2, y2, conf = items[0]
    assert NAMES[cls] == "SPEED_50" and (x1, y1, x2, y2) == (0.1, 0.1, 0.2, 0.3) and conf == 0.8
    assert items[1][1:5] == (0.0, 0.5, 0.2, 1.0) and items[1][5] == 1.0


def test_yolo_conversion_roundtrip(tmp_path):
    items = [(2, 0.1, 0.2, 0.3, 0.6, 0.9)]
    text = L.to_yolo_lines(items)
    assert text == "2 0.200000 0.400000 0.200000 0.400000\n"
    assert L.to_yolo_lines([]) == ""
    p = tmp_path / "a.txt"
    p.write_text(text)
    back = R.read_yolo(p, len(NAMES))
    assert back[0]["cls"] == 2
    assert [round(v, 6) for v in back[0]["box"]] == [0.1, 0.2, 0.3, 0.6]


def test_review_record_has_min_confidence():
    rec = L.review_record("x.jpg", [(0, 0, 0, 0.1, 0.1, 0.7), (1, 0, 0, 0.1, 0.1, 0.4)], NAMES, "m")
    assert rec["min_confidence"] == 0.4 and rec["reviewed"] is False
    assert L.review_record("y.jpg", [], NAMES, "m")["min_confidence"] == 1.0


def test_encode_image_downscales(tmp_path):
    from PIL import Image
    import base64, io
    p = tmp_path / "big.png"
    Image.new("RGB", (3000, 1000), "red").save(p)
    b64, media = L.encode_image(p)
    assert media == "image/jpeg"
    im = Image.open(io.BytesIO(base64.b64decode(b64)))
    assert max(im.size) == L.MAX_SIDE


def test_review_server_list_save_and_traversal(tmp_path):
    from PIL import Image
    for name, conf in [("v__000001.jpg", 0.9), ("v__000002.jpg", 0.3)]:
        Image.new("RGB", (100, 60)).save(tmp_path / name)
        (tmp_path / name.replace(".jpg", ".txt")).write_text("2 0.5 0.5 0.2 0.2\n")
        with (tmp_path / "review.jsonl").open("a") as f:
            f.write(json.dumps({"image": name, "reviewed": False, "min_confidence": conf}) + "\n")
    Image.new("RGB", (10, 10)).save(tmp_path / "unlabeled.jpg")

    server = ThreadingHTTPServer(("127.0.0.1", 0), R.make_handler(tmp_path, tmp_path, NAMES))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{server.server_address[1]}"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    urllib.request.install_opener(opener)
    try:
        lst = json.load(urllib.request.urlopen(base + "/api/list"))
        assert [x["name"] for x in lst] == ["v__000002.jpg", "v__000001.jpg"]  # tin cậy thấp trước

        body = json.dumps({"boxes": [{"cls": 0, "box": [0.1, 0.1, 0.3, 0.4]}]}).encode()
        req = urllib.request.Request(base + "/api/labels/v__000002.jpg", data=body, method="POST",
                                     headers={"Content-Type": "application/json"})
        assert json.load(urllib.request.urlopen(req)) == {"saved": 1}
        assert (tmp_path / "v__000002.txt").read_text().startswith("0 0.200000 0.250000")
        lst = json.load(urllib.request.urlopen(base + "/api/list"))
        assert lst[-1]["name"] == "v__000002.jpg" and lst[-1]["reviewed"]

        with pytest.raises(urllib.error.HTTPError):
            urllib.request.urlopen(base + "/img/..%2F..%2Fetc%2Fpasswd")
    finally:
        server.shutdown()

    # prepare_dataset đọc được trạng thái duyệt do review_server ghi.
    sys.path.insert(0, str(L.REPO / "ml"))
    from prepare_dataset import load_review_status
    status = load_review_status(tmp_path / "review.jsonl")
    assert status == {"v__000001.jpg": False, "v__000002.jpg": True}
