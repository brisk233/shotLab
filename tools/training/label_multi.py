"""多场景球自动标注：YOLO-World 提示 basketball，采集不同场景/光照/分辨率的球。
重点保留"飞行中的球"（画面中上部、离人远），这是当前模型的短板。"""
import os, sys, json
import cv2, numpy as np
from ultralytics import YOLO

OUT = "dataset"
CONF = 0.13
EVERY_N = 4
MAX_PER_VIDEO = int(os.environ.get('MAX_PER_VIDEO', '120'))

videos = sys.argv[1:]
for split in ("train", "val"):
    os.makedirs(f"{OUT}/images/{split}", exist_ok=True)
    os.makedirs(f"{OUT}/labels/{split}", exist_ok=True)

m = YOLO(os.path.join(os.path.dirname(__file__), "yolov8s-worldv2.pt"))
m.set_classes(["basketball"])
person = YOLO(os.path.join(os.path.dirname(__file__), "yolov8n.pt"))

stats = {"train": 0, "val": 0, "flight": 0}
for vi, v in enumerate(videos):
    cap = cv2.VideoCapture(v)
    W = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)); H = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    split = "val" if vi == len(videos) - 1 else "train"
    stem = os.path.splitext(os.path.basename(v))[0][:12]
    i = 0; saved = 0
    while True:
        ok, f = cap.read()
        if not ok: break
        if i % EVERY_N:
            i += 1; continue
        i += 1
        rb = m.predict(f, conf=CONF, imgsz=1280, verbose=False)[0]
        if rb.boxes is None or not len(rb.boxes): continue
        best = None
        for j in range(len(rb.boxes)):
            conf = float(rb.boxes.conf[j])
            x1, y1, x2, y2 = rb.boxes.xyxy[j].cpu().numpy().tolist()
            w = (x2-x1)/W; h = (y2-y1)/H
            if w < 0.004 or w > 0.12: continue
            ar = (x2-x1) / max(1.0, (y2-y1))
            if ar < 0.6 or ar > 1.8: continue
            fill = 1.0
            if best is None or conf > best[0]:
                best = (conf, x1/W, y1/H, x2/W, y2/H)
        if best is None: continue
        _, cx1, cy1, cx2, cy2 = best
        # 是否飞行段：球在画面中上部（<0.55），且离开最大人体框的肩线
        in_flight = (cy1 + cy2) / 2 < 0.55
        rp = person.predict(f, classes=[0], conf=0.35, verbose=False)[0]
        if rp.boxes is not None and len(rp.boxes):
            bb = max(rp.boxes.xyxy.cpu().numpy().tolist(), key=lambda b: (b[2]-b[0])*(b[3]-b[1]))
            shoulder = (bb[1] + (bb[3]-bb[1]) * 0.28) / H
            if (cy1 + cy2) / 2 < shoulder - 0.02:
                in_flight = True
        if in_flight: stats["flight"] += 1
        name = f"{stem}_{saved:04d}"
        cv2.imwrite(f"{OUT}/images/{split}/{name}.jpg", f)
        cx = (cx1 + cx2) / 2; cy = (cy1 + cy2) / 2
        bw = cx2 - cx1; bh = cy2 - cy1
        with open(f"{OUT}/labels/{split}/{name}.txt", "w") as fh:
            fh.write(f"0 {cx:.6f} {cy:.6f} {bw:.6f} {bh:.6f}\n")
        saved += 1
        if saved >= MAX_PER_VIDEO:
            break
    cap.release()
    stats[split] += saved
    print(f"{os.path.basename(v)} -> {split}: {saved} (frame {W}x{H})")

with open(f"{OUT}/data.yaml", "w") as f:
    f.write(f"path: {os.path.abspath(OUT)}\ntrain: images/train\nval: images/val\nnames:\n  0: ball\n")
print("stats:", json.dumps(stats, ensure_ascii=False))