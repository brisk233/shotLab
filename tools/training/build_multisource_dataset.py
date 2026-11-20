"""Build a source-isolated basketball detection set from local videos.

The teacher detector is YOLO-World with the open-vocabulary prompt
``basketball``. It is used only to bootstrap labels; validation/test videos are
kept out of training so repeated frames from one source cannot inflate metrics.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import time
from dataclasses import dataclass
from pathlib import Path

import av
import cv2
import numpy as np
from ultralytics import YOLO


PARTIAL_SUFFIX = re.compile(r"\.f\d+$")


@dataclass(frozen=True)
class Source:
    bvid: str
    title: str
    split: str
    scene: str
    video: Path


def find_video(root: Path, bvid: str) -> Path:
    candidates = sorted(root.glob(f"{bvid}_*.mp4"))
    candidates = [path for path in candidates if not PARTIAL_SUFFIX.search(path.stem)]
    if not candidates:
        raise FileNotFoundError(f"video for {bvid} not found under {root}")
    return candidates[0]


def load_sources(root: Path, manifest: Path) -> list[Source]:
    entries = json.loads(manifest.read_text(encoding="utf-8"))
    return [
        Source(
            bvid=item["bvid"],
            title=item["title"],
            split=item["split"],
            scene=item["scene"],
            video=find_video(root, item["bvid"]),
        )
        for item in entries
    ]


def iter_frames(video: Path, interval: float, max_frames: int):
    """Yield timestamped BGR frames without relying on OpenCV codec support."""
    container = av.open(str(video))
    try:
        stream = container.streams.video[0]
        next_time = 0.0
        count = 0
        for frame in container.decode(stream):
            timestamp = float(frame.time or 0.0)
            if timestamp + 1e-4 < next_time:
                continue
            yield timestamp, frame.to_ndarray(format="bgr24")
            count += 1
            next_time = timestamp + interval
            if count >= max_frames:
                break
    finally:
        container.close()


def valid_box(box: np.ndarray, image_w: int, image_h: int, confidence: float, orange: float) -> bool:
    x1, y1, x2, y2 = (float(value) for value in box)
    width = max(0.0, x2 - x1)
    height = max(0.0, y2 - y1)
    if width < 3.0 or height < 3.0:
        return False
    if width > image_w * 0.16 or height > image_h * 0.16:
        return False
    aspect = width / max(1.0, height)
    if not 0.55 <= aspect <= 1.80:
        return False
    # Low-confidence candidates are useful only when the pixels themselves are
    # strongly basketball-orange. High-confidence teacher boxes retain context.
    return confidence >= 0.18 or (confidence >= 0.075 and orange >= 0.12)


def orange_fraction(image: np.ndarray, box: np.ndarray) -> float:
    x1, y1, x2, y2 = (int(round(value)) for value in box)
    x1 = max(0, x1)
    y1 = max(0, y1)
    x2 = min(image.shape[1], max(x1 + 1, x2))
    y2 = min(image.shape[0], max(y1 + 1, y2))
    roi = image[y1:y2, x1:x2]
    if roi.size == 0:
        return 0.0
    hsv = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)
    hue, saturation, value = cv2.split(hsv)
    orange = (hue <= 32) & (saturation >= 80) & (value >= 45)
    return float(orange.mean())


def stable_negative(
    bvid: str,
    timestamp: float,
    every_seconds: float,
    salt: str = "shotlab-v1",
) -> bool:
    bucket = int(timestamp // every_seconds)
    digest = hashlib.sha1(f"{salt}:{bvid}:{bucket}".encode("utf-8")).digest()
    return digest[0] < 72


def save_sample(
    output: Path,
    source: Source,
    timestamp: float,
    image: np.ndarray,
    boxes: list[np.ndarray],
    teacher_confidences: list[float],
    metadata,
) -> None:
    digest = hashlib.sha1(f"{source.bvid}:{timestamp:.3f}".encode("utf-8")).hexdigest()[:10]
    stem = f"{source.split}_{source.bvid}_{int(timestamp * 1000):07d}_{digest}"
    image_path = output / "images" / source.split / f"{stem}.jpg"
    label_path = output / "labels" / source.split / f"{stem}.txt"
    image_path.parent.mkdir(parents=True, exist_ok=True)
    label_path.parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(image_path), image, [cv2.IMWRITE_JPEG_QUALITY, 94])

    height, width = image.shape[:2]
    label_lines: list[str] = []
    for box in boxes:
        x1, y1, x2, y2 = box
        cx = ((x1 + x2) / 2.0) / width
        cy = ((y1 + y2) / 2.0) / height
        bw = (x2 - x1) / width
        bh = (y2 - y1) / height
        label_lines.append(f"0 {cx:.6f} {cy:.6f} {bw:.6f} {bh:.6f}")
    label_path.write_text("\n".join(label_lines) + ("\n" if label_lines else ""), encoding="utf-8")
    metadata.append(
        {
            "image": str(image_path),
            "label": str(label_path),
            "bvid": source.bvid,
            "title": source.title,
            "scene": source.scene,
            "split": source.split,
            "time_sec": round(timestamp, 3),
            "boxes": len(boxes),
            "teacher_conf": [round(value, 4) for value in teacher_confidences],
        }
    )
def run(args) -> dict[str, int]:
    input_dir = args.input.resolve()
    output = args.output.resolve()
    manifest = args.manifest.resolve()
    sources = load_sources(input_dir, manifest)
    if args.bvid:
        wanted = set(args.bvid)
        sources = [source for source in sources if source.bvid in wanted]
    if not sources:
        raise SystemExit("no sources selected")

    output.mkdir(parents=True, exist_ok=True)
    for split in ("train", "val", "test"):
        (output / "images" / split).mkdir(parents=True, exist_ok=True)
        (output / "labels" / split).mkdir(parents=True, exist_ok=True)

    teacher = YOLO(args.teacher)
    teacher.set_classes(["basketball"])
    manifest_path = output / "manifest.jsonl"
    metadata: list[dict] = []
    if args.append and manifest_path.exists():
        metadata = [
            json.loads(line)
            for line in manifest_path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
    stats = {"teacher_calls": 0, "positive": 0, "negative": 0, "skipped": 0}
    started = time.time()

    for source_index, source in enumerate(sources, 1):
        source_pos = 0
        source_neg = 0
        batch_frames: list[np.ndarray] = []
        batch_times: list[float] = []

        def flush() -> None:
            nonlocal source_pos, source_neg
            if not batch_frames:
                return
            results = teacher.predict(
                batch_frames,
                imgsz=args.imgsz,
                conf=args.conf,
                iou=0.45,
                max_det=20,
                batch=len(batch_frames),
                verbose=False,
            )
            stats["teacher_calls"] += len(batch_frames)
            for image, timestamp, result in zip(batch_frames, batch_times, results):
                accepted: list[np.ndarray] = []
                accepted_conf: list[float] = []
                if result.boxes is not None:
                    candidates = []
                    for box_tensor, conf_tensor in zip(result.boxes.xyxy, result.boxes.conf):
                        box = box_tensor.cpu().numpy()
                        confidence = float(conf_tensor.cpu())
                        orange = orange_fraction(image, box)
                        if valid_box(box, image.shape[1], image.shape[0], confidence, orange):
                            candidates.append((confidence + 0.18 * orange, confidence, box))
                    candidates.sort(key=lambda item: item[0], reverse=True)
                    for _, confidence, box in candidates[:3]:
                        accepted.append(box)
                        accepted_conf.append(confidence)

                if accepted:
                    save_sample(output, source, timestamp, image, accepted, accepted_conf, metadata)
                    source_pos += 1
                    stats["positive"] += 1
                elif stable_negative(source.bvid, timestamp, args.negative_every):
                    save_sample(output, source, timestamp, image, [], [], metadata)
                    source_neg += 1
                    stats["negative"] += 1

            batch_frames.clear()
            batch_times.clear()

        for timestamp, image in iter_frames(source.video, args.interval, args.max_frames):
            # Encoded portrait/landscape input stays untouched. The detector's
            # own letterbox performs the same aspect-preserving resize as Android.
            batch_frames.append(image)
            batch_times.append(timestamp)
            if len(batch_frames) >= args.batch:
                flush()
        flush()
        print(
            f"[{source_index}/{len(sources)}] {source.bvid} {source.split} "
            f"pos={source_pos} neg={source_neg} {source.title}",
            flush=True,
        )

    data_yaml = output / "data.yaml"
    data_yaml.write_text(
        "\n".join(
            [
                f"path: {output.as_posix()}",
                "train: images/train",
                "val: images/val",
                "test: images/test",
                "names:",
                "  0: ball",
                "",
            ]
        ),
        encoding="utf-8",
    )
    manifest_path.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in metadata) + "\n",
        encoding="utf-8",
    )
    summary = {
        **stats,
        "sources": len(sources),
        "train_images": len(list((output / "images" / "train").glob("*.jpg"))),
        "val_images": len(list((output / "images" / "val").glob("*.jpg"))),
        "test_images": len(list((output / "images" / "test").glob("*.jpg"))),
        "elapsed_sec": round(time.time() - started, 1),
    }
    (output / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return stats


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--input",
        type=Path,
        default=Path("datasets/incoming/youtube_batch01"),
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path("tools/training/sources_batch01.json"),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("datasets/derived/ball_multisource"),
    )
    parser.add_argument("--teacher", default="yolov8s-worldv2.pt")
    parser.add_argument("--imgsz", type=int, default=960)
    parser.add_argument("--conf", type=float, default=0.075)
    parser.add_argument("--interval", type=float, default=2.0)
    parser.add_argument("--max-frames", type=int, default=220)
    parser.add_argument("--negative-every", type=float, default=10.0)
    parser.add_argument("--batch", type=int, default=4)
    parser.add_argument("--bvid", nargs="*", help="optional source allow-list")
    parser.add_argument(
        "--append",
        action="store_true",
        help="append another source batch without rebuilding the existing split",
    )
    return parser.parse_args()


if __name__ == "__main__":
    run(parse_args())
