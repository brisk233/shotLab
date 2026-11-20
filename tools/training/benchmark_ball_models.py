"""Benchmark one or more exported YOLO ball ONNX models on the held-out split."""

from __future__ import annotations

import argparse
import json
import statistics
import time
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort


def letterbox(image: np.ndarray, width: int, height: int):
    source_h, source_w = image.shape[:2]
    scale = min(width / source_w, height / source_h)
    resized_w = max(1, min(width, round(source_w * scale)))
    resized_h = max(1, min(height, round(source_h * scale)))
    resized = cv2.resize(image, (resized_w, resized_h), interpolation=cv2.INTER_LINEAR)
    pad_x = (width - resized_w) / 2.0
    pad_y = (height - resized_h) / 2.0
    canvas = np.full((height, width, 3), 114, dtype=np.uint8)
    left = int(round(pad_x))
    top = int(round(pad_y))
    canvas[top : top + resized_h, left : left + resized_w] = resized
    rgb = cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB)
    tensor = rgb.transpose(2, 0, 1)[None].astype(np.float32) / 255.0
    return tensor, scale, pad_x, pad_y


class BallOnnx:
    def __init__(self, path: Path, providers):
        options = ort.SessionOptions()
        options.intra_op_num_threads = 4
        self.session = ort.InferenceSession(str(path), options, providers=providers)
        self.input = self.session.get_inputs()[0]
        self.output = self.session.get_outputs()[0]
        self.height = int(self.input.shape[2])
        self.width = int(self.input.shape[3])

    def detect(self, image: np.ndarray, conf: float):
        tensor, scale, pad_x, pad_y = letterbox(image, self.width, self.height)
        output = self.session.run(None, {self.input.name: tensor})[0]
        if output.ndim != 3:
            return []
        values = output[0]
        if values.shape[0] < values.shape[1]:
            values = values.T
        best = None
        for row in values:
            score = float(row[4:].max())
            if score < conf:
                continue
            if best is None or score > best[0]:
                best = (score, row[:4])
        if best is None:
            return []
        score, box = best
        cx, cy, width, height = (float(value) for value in box)
        x1 = (cx - width / 2.0 - pad_x) / scale
        y1 = (cy - height / 2.0 - pad_y) / scale
        x2 = (cx + width / 2.0 - pad_x) / scale
        y2 = (cy + height / 2.0 - pad_y) / scale
        return [(score, np.array([x1, y1, x2, y2], dtype=np.float32))]


def read_labels(path: Path, width: int, height: int) -> list[np.ndarray]:
    boxes = []
    if not path.exists():
        return boxes
    for line in path.read_text(encoding="utf-8").splitlines():
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


def iou(a: np.ndarray, b: np.ndarray) -> float:
    x1 = max(float(a[0]), float(b[0]))
    y1 = max(float(a[1]), float(b[1]))
    x2 = min(float(a[2]), float(b[2]))
    y2 = min(float(a[3]), float(b[3]))
    intersection = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    area_a = max(0.0, float(a[2] - a[0])) * max(0.0, float(a[3] - a[1]))
    area_b = max(0.0, float(b[2] - b[0])) * max(0.0, float(b[3] - b[1]))
    union = area_a + area_b - intersection
    return 0.0 if union <= 0.0 else intersection / union


def benchmark(model_path: Path, split: Path, conf: float, providers, warmup: int) -> dict:
    model = BallOnnx(model_path, providers)
    images = sorted((split / "images" / "test").glob("*.jpg"))
    labels_root = split / "labels" / "test"
    positives = 0
    hits = 0
    false_images = 0
    total_predictions = 0
    times = []
    center_errors = []

    for index, image_path in enumerate(images):
        image = cv2.imread(str(image_path))
        height, width = image.shape[:2]
        labels = read_labels(labels_root / f"{image_path.stem}.txt", width, height)
        start = time.perf_counter()
        predictions = model.detect(image, conf)
        elapsed = (time.perf_counter() - start) * 1000.0
        if index >= warmup:
            times.append(elapsed)
        total_predictions += len(predictions)
        if labels:
            positives += 1
            if predictions and max(iou(predictions[0][1], box) for box in labels) >= 0.20:
                hits += 1
                predicted = predictions[0][1]
                target = max(labels, key=lambda box: iou(predicted, box))
                px = (predicted[0] + predicted[2] - target[0] - target[2]) / 2.0
                py = (predicted[1] + predicted[3] - target[1] - target[3]) / 2.0
                center_errors.append(float(np.hypot(px, py) / np.hypot(width, height)))
        elif predictions:
            false_images += 1

    negatives = len(images) - positives
    return {
        "model": str(model_path),
        "input": [model.width, model.height],
        "images": len(images),
        "positives": positives,
        "negatives": negatives,
        "recall_at_iou_020": round(hits / max(1, positives), 4),
        "negative_false_rate": round(false_images / max(1, negatives), 4),
        "false_positive_images": false_images,
        "mean_prediction_ms": round(statistics.mean(times), 3) if times else None,
        "p95_prediction_ms": round(float(np.percentile(times, 95)), 3) if times else None,
        "center_error_norm_mean": round(statistics.mean(center_errors), 6) if center_errors else None,
    }


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("models", nargs="+", type=Path)
    parser.add_argument(
        "--dataset",
        type=Path,
        default=Path("datasets/derived/ball_multisource"),
    )
    parser.add_argument("--conf", type=float, default=0.20)
    parser.add_argument("--warmup", type=int, default=10)
    parser.add_argument(
        "--provider",
        default="CPUExecutionProvider",
        choices=["CPUExecutionProvider", "CUDAExecutionProvider"],
    )
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()
    rows = [
        benchmark(model, args.dataset, args.conf, [args.provider], args.warmup)
        for model in args.models
    ]
    text = json.dumps(rows, ensure_ascii=False, indent=2)
    print(text)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
