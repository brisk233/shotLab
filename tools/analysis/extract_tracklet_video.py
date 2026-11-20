"""Extract Android-compatible tracklet candidates from a video.

This is the codec-safe PC counterpart used for multi-source evaluation. It
samples the same 15 fps by default, crops encoded black bars, runs the exported
ball/person ONNX models, and writes the normalized frame format consumed by
``engine_v7.py`` and the Android regression fixture tests.
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import av
import cv2
import numpy as np
from ultralytics import YOLO


def content_crop(image: np.ndarray) -> np.ndarray:
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    row_mean = gray.mean(axis=1)
    bright = np.flatnonzero(row_mean > 24)
    if len(bright) == 0:
        return image
    top = int(bright[0])
    bottom = int(bright[-1]) + 1
    if bottom - top < image.shape[0] * 0.15:
        return image
    return image[top:bottom]


def boxes_for(result, width: int, height: int, max_boxes: int):
    boxes = []
    if result.boxes is None:
        return boxes
    for box_tensor, conf_tensor in zip(result.boxes.xyxy, result.boxes.conf):
        x1, y1, x2, y2 = (float(value) for value in box_tensor.cpu().numpy().tolist())
        bw = x2 - x1
        bh = y2 - y1
        if bw < 2.0 or bh < 2.0:
            continue
        aspect = bw / max(1.0, bh)
        if aspect < 0.5 or aspect > 2.0:
            continue
        boxes.append(
            [
                max(0.0, x1 / width),
                max(0.0, y1 / height),
                min(1.0, x2 / width),
                min(1.0, y2 / height),
                float(conf_tensor.cpu()),
            ]
        )
    boxes.sort(key=lambda item: item[4], reverse=True)
    return boxes[:max_boxes]


def person_boxes(result, width: int, height: int, max_people: int):
    people = []
    if result.boxes is None or result.boxes.xyxy is None:
        return people
    for box_tensor, cls_tensor, conf_tensor in zip(
        result.boxes.xyxy,
        result.boxes.cls,
        result.boxes.conf,
    ):
        if int(cls_tensor.cpu()) != 0:
            continue
        x1, y1, x2, y2 = (float(value) for value in box_tensor.cpu().numpy().tolist())
        if (x2 - x1) * (y2 - y1) < width * height * 0.002:
            continue
        people.append(
            [
                max(0.0, x1 / width),
                max(0.0, y1 / height),
                min(1.0, x2 / width),
                min(1.0, y2 / height),
                float(conf_tensor.cpu()),
            ]
        )
    people.sort(key=lambda item: (item[2] - item[0]) * (item[3] - item[1]), reverse=True)
    return people[:max_people]


def run(args) -> dict:
    ball = YOLO(str(args.ball_model))
    person = YOLO(str(args.person_model))
    container = av.open(str(args.video))
    stream = container.streams.video[0]
    frames = []
    source_w = 0
    source_h = 0
    sampled = 0
    with_ball = 0
    last_people = []
    ball_ms = 0.0
    person_ms = 0.0
    roi_ms = 0.0
    roi_runs = 0
    next_time = 0.0
    rim = None
    if args.rim:
        rim = tuple(float(value) for value in args.rim.split(","))
        if len(rim) != 4:
            raise SystemExit("--rim must be x1,y1,x2,y2")
    started = time.perf_counter()
    try:
        for frame in container.decode(stream):
            timestamp = float(frame.time or 0.0)
            if timestamp + 1e-4 < next_time:
                continue
            if args.max_seconds is not None and timestamp > args.max_seconds:
                break
            image = frame.to_ndarray(format="bgr24")
            height, width = image.shape[:2]
            scale = min(args.width / width, args.height / height, 1.0)
            if scale < 1.0:
                image = cv2.resize(
                    image,
                    (max(1, round(width * scale)), max(1, round(height * scale))),
                    interpolation=cv2.INTER_AREA,
                )
                height, width = image.shape[:2]
            # MediaMetadataRetriever 先按 720x720 等比缩放，再裁编码黑边；
            # 顺序不能反过来，否则 9:16 素材会得到错误的 720x679 坐标系。
            if args.content_crop:
                image = content_crop(image)
                height, width = image.shape[:2]
            source_w = width
            source_h = height

            ball_started = time.perf_counter()
            ball_result = ball.predict(
                image,
                imgsz=args.ball_imgsz,
                conf=args.ball_conf,
                iou=0.45,
                max_det=20,
                verbose=False,
            )[0]
            ball_ms += (time.perf_counter() - ball_started) * 1000.0
            cands = boxes_for(ball_result, width, height, args.max_balls)
            if (
                rim is not None
                and cands
                and args.refine_roi
                and (rim[2] - rim[0]) * width >= 30.0
            ):
                best = cands[0]
                confidence = best[4]
                cx = (best[0] + best[2]) / 2.0
                cy = (best[1] + best[3]) / 2.0
                rim_cx = (rim[0] + rim[2]) / 2.0
                rim_cy = (rim[1] + rim[3]) / 2.0
                half = min(0.12, max(0.05, (rim[2] - rim[0]) * 3.5))
                if confidence < 0.60 and abs(cx - rim_cx) < half and abs(cy - rim_cy) < half:
                    left = max(0, int((rim_cx - half) * width))
                    top = max(0, int((rim_cy - half) * height))
                    right = min(width, int((rim_cx + half) * width))
                    bottom = min(height, int((rim_cy + half) * height))
                    crop = image[top:bottom, left:right]
                    if crop.size:
                        roi_started = time.perf_counter()
                        roi_result = ball.predict(
                            crop,
                            imgsz=args.ball_imgsz,
                            conf=args.ball_conf,
                            iou=0.45,
                            max_det=10,
                            verbose=False,
                        )[0]
                        roi_ms += (time.perf_counter() - roi_started) * 1000.0
                        roi_runs += 1
                        roi_boxes = boxes_for(roi_result, crop.shape[1], crop.shape[0], 1)
                        if roi_boxes:
                            box = roi_boxes[0]
                            refined = [
                                (left + box[0] * crop.shape[1]) / width,
                                (top + box[1] * crop.shape[0]) / height,
                                (left + box[2] * crop.shape[1]) / width,
                                (top + box[3] * crop.shape[0]) / height,
                                0.9,
                            ]
                            cands = [refined] + [item for item in cands if abs(item[0] - refined[0]) + abs(item[1] - refined[1]) > 0.01]

            if sampled % max(1, args.person_every) == 0:
                person_started = time.perf_counter()
                person_result = person.predict(
                    image,
                    imgsz=args.person_imgsz,
                    conf=args.person_conf,
                    classes=[0],
                    max_det=args.max_people,
                    verbose=False,
                )[0]
                person_ms += (time.perf_counter() - person_started) * 1000.0
                last_people = person_boxes(person_result, width, height, args.max_people)

            if cands:
                with_ball += 1
                frames.append(
                    {
                        "t": round(timestamp, 4),
                        "cands": [
                            [round(value, 6) for value in box]
                            for box in cands
                        ],
                        "person": last_people[0][:4] if last_people else None,
                        "persons": [box[:4] for box in last_people],
                    }
                )
            sampled += 1
            next_time = timestamp + 1.0 / args.fps
    finally:
        container.close()

    output = {
        "fps": args.fps,
        "w": source_w,
        "h": source_h,
        "source": args.video.name,
        "ballModel": args.ball_model.name,
        "frames": frames,
        "metrics": {
            "sampled": sampled,
            "withBall": with_ball,
            "withBallRate": round(with_ball / max(1, sampled), 4),
            "avgBallMs": round(ball_ms / max(1, sampled), 3),
            "avgPersonMs": round(
                person_ms / max(1, (sampled + max(1, args.person_every) - 1) // max(1, args.person_every)),
                3,
            ),
            "roiRuns": roi_runs,
            "avgRoiMs": round(roi_ms / max(1, roi_runs), 3),
            "elapsedSec": round(time.perf_counter() - started, 3),
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(output, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    print(json.dumps(output["metrics"], ensure_ascii=False, indent=2))
    return output


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("video", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--ball-model", type=Path, default=Path("app/src/main/assets/ball_yolo.onnx"))
    parser.add_argument("--person-model", type=Path, default=Path("app/src/main/assets/person_yolo.onnx"))
    parser.add_argument("--fps", type=float, default=15.0)
    parser.add_argument("--width", type=int, default=720)
    parser.add_argument("--height", type=int, default=720)
    parser.add_argument("--ball-imgsz", type=int, default=416)
    parser.add_argument("--ball-conf", type=float, default=0.08)
    parser.add_argument("--max-balls", type=int, default=3)
    parser.add_argument("--person-imgsz", type=int, default=320)
    parser.add_argument("--person-conf", type=float, default=0.25)
    parser.add_argument("--person-every", type=int, default=5)
    parser.add_argument("--max-people", type=int, default=4)
    parser.add_argument("--rim", help="normalized x1,y1,x2,y2 for near-rim refinement")
    parser.add_argument("--no-refine-roi", dest="refine_roi", action="store_false")
    parser.set_defaults(refine_roi=True)
    parser.add_argument("--no-content-crop", dest="content_crop", action="store_false")
    parser.set_defaults(content_crop=True)
    parser.add_argument("--max-seconds", type=float)
    return parser.parse_args()


if __name__ == "__main__":
    run(parse_args())
