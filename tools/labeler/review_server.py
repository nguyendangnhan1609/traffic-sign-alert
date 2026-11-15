#!/usr/bin/env python3
"""Trang web cục bộ để duyệt và sửa nhãn (không cần cài thêm gì).

    python tools/labeler/review_server.py --images data/frames
    # mở http://localhost:8765

Ảnh có độ tin cậy thấp nhất hiện trước. Bấm Lưu = ghi lại file .txt và đánh dấu ảnh "đã duyệt" trong review.jsonl.
"""
from __future__ import annotations

import argparse
import json
import mimetypes
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Dict, List, Optional, Sequence
from urllib.parse import unquote, urlparse

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "ml"))
from check_classes import load_class_names  # noqa: E402

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp"}


def read_yolo(path: Path, num_classes: int) -> List[dict]:
    boxes = []
    if not path.is_file():
        return boxes
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) != 5:
            continue
        try:
            c = int(parts[0]); cx, cy, w, h = map(float, parts[1:])
        except ValueError:
            continue
        if 0 <= c < num_classes:
            boxes.append({"cls": c, "box": [cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2]})
    return boxes


def write_yolo(path: Path, boxes: Sequence[dict], num_classes: int) -> int:
    lines = []
    for b in boxes:
        c = int(b["cls"])
        x1, y1, x2, y2 = (min(1.0, max(0.0, float(v))) for v in b["box"])
        x1, x2 = sorted((x1, x2)); y1, y2 = sorted((y1, y2))
        if not (0 <= c < num_classes) or x2 - x1 <= 0 or y2 - y1 <= 0:
            continue
        lines.append(f"{c} {(x1 + x2) / 2:.6f} {(y1 + y2) / 2:.6f} {x2 - x1:.6f} {y2 - y1:.6f}")
    path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
    return len(lines)


def load_review(path: Path) -> Dict[str, dict]:
    out: Dict[str, dict] = {}
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            try:
                rec = json.loads(line)
            except ValueError:
                continue
            if isinstance(rec, dict) and isinstance(rec.get("image"), str):
                merged = out.get(rec["image"], {})
                merged.update(rec)
                out[rec["image"]] = merged
    return out


def image_list(images: Path, labels: Path) -> List[dict]:
    review = load_review(labels / "review.jsonl")
    items = []
    for p in sorted(images.iterdir()):
        if p.suffix.lower() not in IMAGE_EXTS or not (labels / f"{p.stem}.txt").exists():
            continue
        r = review.get(p.name, {})
        items.append({"name": p.name, "reviewed": bool(r.get("reviewed")), "conf": float(r.get("min_confidence", 1.0))})
    items.sort(key=lambda x: (x["reviewed"], x["conf"], x["name"]))
    return items


def safe_child(folder: Path, name: str) -> Optional[Path]:
    """Chặn truy cập ra ngoài thư mục (../)."""
    p = (folder / name).resolve()
    return p if p.parent == folder.resolve() and p.is_file() else None


PAGE = r"""<!DOCTYPE html><html lang="vi"><head><meta charset="utf-8"><title>Duyệt nhãn biển báo</title>
<style>
body{margin:0;font-family:system-ui,sans-serif;display:flex;height:100vh;background:#1e1e1e;color:#eee}
#list{width:260px;overflow:auto;border-right:1px solid #444;font-size:13px}
#list div{padding:6px 10px;cursor:pointer;border-bottom:1px solid #333}
#list div.on{background:#264f78}#list .ok{color:#7c7}
#main{flex:1;display:flex;flex-direction:column;min-width:0}
#bar{padding:8px;display:flex;gap:8px;align-items:center;flex-wrap:wrap;border-bottom:1px solid #444}
#wrap{flex:1;overflow:auto;display:flex;justify-content:center;align-items:flex-start;padding:8px}
canvas{max-width:100%;cursor:crosshair}
button,select{font-size:14px;padding:4px 10px}#hint{color:#aaa;font-size:12px}
</style></head><body>
<div id="list"></div>
<div id="main"><div id="bar">
<b id="title"></b>
<label>Lớp cho hộp đang chọn / hộp mới: <select id="cls"></select></label>
<button id="del">Xoá hộp (Del)</button><button id="save">Lưu &amp; ảnh tiếp (S)</button>
<span id="hint">Kéo chuột để vẽ hộp mới. Bấm vào hộp để chọn. N/P: ảnh sau/trước.</span>
</div><div id="wrap"><canvas id="cv"></canvas></div></div>
<script>
let names=[],items=[],cur=-1,boxes=[],sel=-1,img=new Image(),drag=null;
const cv=document.getElementById('cv'),ctx=cv.getContext('2d'),clsSel=document.getElementById('cls');
async function j(u,o){const r=await fetch(u,o);if(!r.ok)throw new Error(await r.text());return r.json()}
async function init(){names=(await j('/api/classes')).names;
 names.forEach((n,i)=>{const o=document.createElement('option');o.value=i;o.textContent=i+' '+n;clsSel.appendChild(o)});
 await refresh();if(items.length)open(0)}
async function refresh(){items=await j('/api/list');const L=document.getElementById('list');L.innerHTML='';
 items.forEach((it,i)=>{const d=document.createElement('div');d.textContent=(it.reviewed?'✓ ':'')+it.name+' ('+it.conf.toFixed(2)+')';
  if(it.reviewed)d.className='ok';if(i===cur)d.classList.add('on');d.onclick=()=>open(i);L.appendChild(d)})}
async function open(i){if(i<0||i>=items.length)return;cur=i;sel=-1;const n=items[i].name;
 document.getElementById('title').textContent=n+' ('+(i+1)+'/'+items.length+')';
 boxes=(await j('/api/labels/'+encodeURIComponent(n))).boxes;img=new Image();
 img.onload=()=>{cv.width=img.width;cv.height=img.height;draw()};img.src='/img/'+encodeURIComponent(n);
 [...document.querySelectorAll('#list div')].forEach((d,k)=>d.classList.toggle('on',k===i))}
function draw(){ctx.drawImage(img,0,0);ctx.lineWidth=Math.max(2,cv.width/500);ctx.font=Math.max(14,cv.width/60)+'px sans-serif';
 boxes.forEach((b,i)=>{const[x1,y1,x2,y2]=b.box;ctx.strokeStyle=i===sel?'#0ff':'#ff0';
  ctx.strokeRect(x1*cv.width,y1*cv.height,(x2-x1)*cv.width,(y2-y1)*cv.height);
  ctx.fillStyle=i===sel?'#0ff':'#ff0';ctx.fillText(names[b.cls],x1*cv.width,Math.max(16,y1*cv.height-4))});
 if(drag&&drag.moved){ctx.strokeStyle='#f0f';ctx.strokeRect(drag.x0*cv.width,drag.y0*cv.height,(drag.x-drag.x0)*cv.width,(drag.y-drag.y0)*cv.height)}}
function pos(e){const r=cv.getBoundingClientRect();return[(e.clientX-r.left)/r.width,(e.clientY-r.top)/r.height]}
cv.onmousedown=e=>{const[x,y]=pos(e);drag={x0:x,y0:y,x,y,moved:false}};
cv.onmousemove=e=>{if(!drag)return;const[x,y]=pos(e);drag.x=x;drag.y=y;drag.moved=Math.abs(x-drag.x0)>0.005||Math.abs(y-drag.y0)>0.005;draw()};
cv.onmouseup=e=>{if(!drag)return;const d=drag;drag=null;
 if(d.moved){boxes.push({cls:+clsSel.value,box:[Math.min(d.x0,d.x),Math.min(d.y0,d.y),Math.max(d.x0,d.x),Math.max(d.y0,d.y)]});sel=boxes.length-1}
 else{sel=-1;let best=1e9;boxes.forEach((b,i)=>{const[x1,y1,x2,y2]=b.box;const a=(x2-x1)*(y2-y1);
  if(d.x>=x1&&d.x<=x2&&d.y>=y1&&d.y<=y2&&a<best){best=a;sel=i}});if(sel>=0)clsSel.value=boxes[sel].cls}draw()};
clsSel.onchange=()=>{if(sel>=0){boxes[sel].cls=+clsSel.value;draw()}};
function del(){if(sel>=0){boxes.splice(sel,1);sel=-1;draw()}}
async function save(){const n=items[cur].name;await j('/api/labels/'+encodeURIComponent(n),{method:'POST',
 headers:{'Content-Type':'application/json'},body:JSON.stringify({boxes})});const next=cur+1;await refresh();
 const idx=items.findIndex(x=>x.name===n);open(Math.min(idx+1,items.length-1)<0?0:(next<items.length?idx+1:idx))}
document.getElementById('del').onclick=del;document.getElementById('save').onclick=save;
document.onkeydown=e=>{if(e.target.tagName==='SELECT')return;if(e.key==='Delete'||e.key==='Backspace')del();
 else if(e.key==='s'||e.key==='S')save();else if(e.key==='n'||e.key==='N')open(cur+1);else if(e.key==='p'||e.key==='P')open(cur-1)};
init();
</script></body></html>"""


def make_handler(images: Path, labels: Path, names: List[str]):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *a):  # bớt log
            pass

        def send(self, code: int, body: bytes, ctype: str):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def send_json(self, obj, code: int = 200):
            self.send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")

        def do_GET(self):
            path = unquote(urlparse(self.path).path)
            if path == "/":
                return self.send(200, PAGE.encode("utf-8"), "text/html; charset=utf-8")
            if path == "/api/classes":
                return self.send_json({"names": names})
            if path == "/api/list":
                return self.send_json(image_list(images, labels))
            if path.startswith("/api/labels/"):
                img = safe_child(images, path[len("/api/labels/"):])
                if not img:
                    return self.send_json({"error": "not found"}, 404)
                return self.send_json({"boxes": read_yolo(labels / f"{img.stem}.txt", len(names))})
            if path.startswith("/img/"):
                img = safe_child(images, path[len("/img/"):])
                if not img or img.suffix.lower() not in IMAGE_EXTS:
                    return self.send_json({"error": "not found"}, 404)
                ctype = mimetypes.guess_type(img.name)[0] or "application/octet-stream"
                return self.send(200, img.read_bytes(), ctype)
            self.send_json({"error": "not found"}, 404)

        def do_POST(self):
            path = unquote(urlparse(self.path).path)
            if not path.startswith("/api/labels/"):
                return self.send_json({"error": "not found"}, 404)
            img = safe_child(images, path[len("/api/labels/"):])
            if not img:
                return self.send_json({"error": "not found"}, 404)
            try:
                length = int(self.headers.get("Content-Length", "0"))
                data = json.loads(self.rfile.read(length) or b"{}")
                n = write_yolo(labels / f"{img.stem}.txt", data.get("boxes", []), len(names))
            except (ValueError, KeyError, TypeError) as e:
                return self.send_json({"error": str(e)}, 400)
            with (labels / "review.jsonl").open("a", encoding="utf-8") as f:
                f.write(json.dumps({"image": img.name, "reviewed": True, "min_confidence": 1.0}, ensure_ascii=False) + "\n")
            self.send_json({"saved": n})

    return Handler


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--images", type=Path, required=True)
    ap.add_argument("--labels", type=Path, default=None)
    ap.add_argument("--classes", type=Path, default=REPO / "ml/classes.yaml")
    ap.add_argument("--port", type=int, default=8765)
    args = ap.parse_args(argv)
    labels = args.labels or args.images
    names = load_class_names(args.classes)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(args.images, labels, names))
    print(f"Mở http://localhost:{args.port}  ({len(image_list(args.images, labels))} ảnh có nhãn). Ctrl+C để dừng.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
