"""Audit generated labels and recover small balls missed by the full-frame teacher.

Positive labels must have basketball-colored pixels. Frames with no remaining
label are passed through the current detector; each plausible candidate is
cropped and re-checked by YOLO-World at a larger effective object size. This
keeps hard negatives while promoting obvious teacher misses.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np
from ultralytics import YOLO

from benchmark_ball_models import BallOnnx
from build_multisource_dataset import orange_fraction, valid_box


def read_boxes(label_path: Path, width: int, height: int) -> list[np.ndarray]:
    boxes = []
    if not label_path.exists():
        return boxes
    for line in label_path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) < 5:
            continue
        _, cx, cy, bw, bh = (float(value) for value in parts[:5])
        boxes.append(
            np.array(
                [
                    (cx - bw / 2.0) * width,
                    (cy - bh / 2.0) * height,
                    (cx + bw / 2.0) * width,
                    (cy + bh / 2.0) * height,
                ],
                dtype=np.float32,
            )
        )
    return boxes


def write_boxes(label_path: Path, boxes: list[np.ndarray], width: int, height: int) -> None:
    lines = []
    for box in boxes:
        x1, y1, x2, y2 = (float(value) for value in box)
        cx = ((x1 + x2) / 2.0) / width
        cy = ((y1 + y2) / 2.0) / height
        bw = (x2 - x1) / width
        bh = (y2 - y1) / height
        lines.append(f"0 {cx:.6f} {cy:.6f} {bw:.6f} {bh:.6f}")
    label_path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")


def crop_candidate(image: np.ndarray, box: np.ndarray, scale: float) -> tuple[np.ndarray, int, int]:
    height, width = image.shape[:2]
    x1, y1, x2, y2 = (float(value) for value in box)
    cx = (x1 + x2) / 2.0
    cy = (y1 + y2) / 2.0
    side = max(x2 - x1, y2 - y1) * scale
    side = max(side, 64.0)
    left = max(0, int(round(cx - side / 2.0)))
    top = max(0, int(round(cy - side / 2.0)))
    right = min(width, int(round(cx + side / 2.0)))
    bottom = min(height, int(round(cy + side / 2.0)))
    return image[top:bottom, left:right], left, top


def mapped_teacher_box(
    teacher: YOLO,
    image: np.ndarray,
    candidate: np.ndarray,
    local_conf: float,
    local_orange: float,
) -> tuple[np.ndarray, float] | None:
    crop, left, top = crop_candidate(image, candidate, 3.0)
    if crop.size == 0:
        return None
    result = teacher.predict(crop, imgsz=640, conf=0.055, iou=0.45, max_det=10, verbose=False)[0]
    if result.boxes is None or not len(result.boxes):
        return None
    candidate_cx = (candidate[0] + candidate[2]) / 2.0 - left
    candidate_cy = (candidate[1] + candidate[3]) / 2.0 - top
    candidate_diag = float(np.hypot(candidate[2] - candidate[0], candidate[3] - candidate[1]))
    best = None
    for box_tensor, conf_tensor in zip(result.boxes.xyxy, result.boxes.conf):
        local_box = box_tensor.cpu().numpy().astype(np.float32)
        confidence = float(conf_tensor.cpu())
        local_box[0] += left
        local_box[2] += left
        local_box[1] += top
        local_box[3] += top
        orange = orange_fraction(image, local_box)
        if not valid_box(local_box, image.shape[1], image.shape[0], confidence, orange):
            continue
        teacher_cx = (local_box[0] + local_box[2]) / 2.0 - left
        teacher_cy = (local_box[1] + local_box[3]) / 2.0 - top
        distance = float(np.hypot(teacher_cx - candidate_cx, teacher_cy - candidate_cy))
        if distance > max(12.0, candidate_diag * 1.5):
            continue
        quality = confidence + 0.2 * orange
        if best is None or quality > best[0]:
            best = (quality, confidence, local_box, orange)
    if best is None:
        return None
    _, confidence, box, orange = best
    if confidence < local_conf and orange < local_orange:
        return None
    return box, confidence


def run(args) -> dict[str, int]:
    root = args.dataset.resolve()
    manifest_path = root / "manifest.jsonl"
    rows = [
        json.loads(line)
        for line in manifest_path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    row_by_image = {Path(row["image"]).stem: row for row in rows}
    detector = BallOnnx(args.model, [args.provider])
    teacher = YOLO(args.teacher)
    teacher.set_classes(["basketball"])
    stats = {
        "labels_before": 0,
        "labels_after": 0,
        "boxes_removed_low_orange": 0,
        "empty_after_filter": 0,
        "promoted": 0,
        "still_negative": 0,
    }

    for split in ("train", "val", "test"):
        for image_path in sorted((root / "images" / split).glob("*.jpg")):
            label_path = root / "labels" / split / f"{image_path.stem}.txt"
            image = cv2.imread(str(image_path))
            if image is None:
                continue
            height, width = image.shape[:2]
            boxes = read_boxes(label_path, width, height)
            stats["labels_before"] += len(boxes)
            kept = []
            for box in boxes:
                orange = orange_fraction(image, box)
                if orange >= args.min_orange and valid_box(
                    box,
                    width,
                    height,
                    confidence=1.0,
                    orange=orange,
                ):
                    kept.append(box)
                else:
                    stats["boxes_removed_low_orange"] += 1

            if not kept:
                stats["empty_after_filter"] += 1
                prediction = detector.detect(image, args.candidate_conf)
                if prediction:
                    score, candidate = prediction[0]
                    orange = orange_fraction(image, candidate)
                    if (
                        score >= args.candidate_conf
                        and orange >= args.candidate_orange
                        and valid_box(candidate, width, height, score, orange)
                    ):
                        promoted = mapped_teacher_box(
                            teacher,
                            image,
                            candidate,
                            args.crop_conf,
                            args.crop_orange,
                        )
                        if promoted is not None:
                            kept = [promoted[0]]
                            stats["promoted"] += 1

            stats["labels_after"] += len(kept)
            if not kept:
                stats["still_negative"] += 1
            if args.apply:
                write_boxes(label_path, kept, width, height)
                row = row_by_image.get(image_path.stem)
                if row is not None:
                    row["boxes"] = len(kept)
                    row["curated"] = True

    if args.apply:
        manifest_path.write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
            encoding="utf-8",
        )
        (root / "curation_summary.json").write_text(
            json.dumps(stats, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    print(json.dumps(stats, ensure_ascii=False, indent=2))
    return stats


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, default=Path("datasets/derived/ball_multisource"))
    parser.add_argument("--model", type=Path, default=Path("app/src/main/assets/ball_yolo.onnx"))
    parser.add_argument("--teacher", default="yolov8s-worldv2.pt")
    parser.add_argument("--provider", default="CPUExecutionProvider")
    parser.add_argument("--min-orange", type=float, default=0.08)
    parser.add_argument("--candidate-conf", type=float, default=0.20)
    parser.add_argument("--candidate-orange", type=float, default=0.18)
    parser.add_argument("--crop-conf", type=float, default=0.10)
    parser.add_argument("--crop-orange", type=float, default=0.16)
    parser.add_argument("--apply", action="store_true")
    return parser.parse_args()


if __name__ == "__main__":
    run(parse_args())
