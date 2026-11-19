import os, sys, json
import cv2, numpy as np
from ultralytics import YOLO
VIDEO = sys.argv[1]; OUT = sys.argv[2]
FPS = 10.0
IMGSZ = int(os.environ.get("BALL_IMGSZ", "512"))
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
BALLM = os.environ.get("BALL_MODEL", os.path.join(ASSETS, "ball_yolo.onnx"))
PERSONM = os.environ.get("PERSON_MODEL", os.path.join(ASSETS, "person_yolo.onnx"))
ball = YOLO(BALLM)
person = YOLO(PERSONM)
cap = cv2.VideoCapture(VIDEO)
fps = cap.get(cv2.CAP_PROP_FPS) or 30
step = max(1, int(round(fps / FPS)))
W = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)); H = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
frames = []
i = 0
while True:
    ok, f = cap.read()
    if not ok: break
    if i % step:
        i += 1
        continue
    i += 1
    t = i / fps
    gray = cv2.cvtColor(f, cv2.COLOR_BGR2GRAY).mean(axis=1)
    bright = np.where(gray > 24)[0]
    if len(bright) > 0:
        y0 = max(0, int(bright[0])); y1 = min(H, int(bright[-1]) + 1)
    else:
        y0, y1 = 0, H
    if (y1 - y0) < H * 0.2:
        y0, y1 = 0, H
    band = f[y0:y1, :]
    CONF = float(os.environ.get("CAND_CONF", "0.15"))
    rb = ball.predict(band, conf=CONF, imgsz=IMGSZ, verbose=False)[0]
    cands = []
    if rb.boxes is not None and len(rb.boxes):
        items = list(zip(rb.boxes.xyxy.cpu().numpy().tolist(), rb.boxes.conf.cpu().numpy().tolist()))
        items.sort(key=lambda t2: -t2[1])
        for xyxy, conf in items[:3]:
            x1, yy1, x2, yy2 = xyxy
            cands.append([round(x1 / W, 4), round((yy1 + y0) / H, 4), round(x2 / W, 4), round((yy2 + y0) / H, 4), round(float(conf), 3)])
    rp = person.predict(f, classes=[0], conf=0.35, imgsz=320, verbose=False)[0]
    pb = None
    if rp.boxes is not None and len(rp.boxes):
        bb = max(rp.boxes.xyxy.cpu().numpy().tolist(), key=lambda b: (b[2]-b[0])*(b[3]-b[1]))
        pb = [bb[0]/W, bb[1]/H, bb[2]/W, bb[3]/H]
    frames.append({"t": round(t, 3), "cands": cands, "person": pb})
cap.release()
json.dump({"fps": FPS, "w": W, "h": H, "frames": frames}, open(OUT, "w"))
n_with = sum(1 for x in frames if x["cands"])
print(json.dumps({"frames": len(frames), "with_cands": round(n_with / max(1, len(frames)), 3)}, ensure_ascii=False))
