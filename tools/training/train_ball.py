"""Train a small single-class ball detector and export Android-friendly ONNX."""

from __future__ import annotations

import argparse
from pathlib import Path

from ultralytics import YOLO


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=Path("datasets/derived/ball_multisource/data.yaml"))
    parser.add_argument("--base", default="yolo11n.pt")
    parser.add_argument("--imgsz", type=int, default=416)
    parser.add_argument("--epochs", type=int, default=20)
    parser.add_argument("--batch", type=int, default=16)
    parser.add_argument("--workers", type=int, default=0)
    parser.add_argument("--patience", type=int, default=6)
    parser.add_argument("--freeze", type=int, default=8)
    parser.add_argument("--optimizer", default="auto")
    parser.add_argument(
        "--lr0",
        type=float,
        default=0.0,
        help="initial learning rate; <=0 lets Ultralytics choose automatically",
    )
    parser.add_argument("--name", default="ball_multisource_v2")
    parser.add_argument("--project", type=Path, default=Path("tools/training/runs"))
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()
    model = YOLO(args.base)
    train_args = dict(
        data=str(args.data),
        imgsz=args.imgsz,
        epochs=args.epochs,
        batch=args.batch,
        workers=args.workers,
        patience=args.patience,
        device="cpu",
        amp=False,
        cache="ram",
        freeze=args.freeze,
        cos_lr=True,
        close_mosaic=5,
        degrees=6.0,
        translate=0.08,
        scale=0.35,
        fliplr=0.5,
        hsv_h=0.015,
        hsv_s=0.55,
        hsv_v=0.35,
        mosaic=0.8,
        mixup=0.0,
        project=str(args.project),
        name=args.name,
        exist_ok=True,
        plots=True,
        verbose=True,
    )
    if args.optimizer:
        train_args["optimizer"] = args.optimizer
    if args.lr0 > 0:
        train_args["lr0"] = args.lr0
    result = model.train(**train_args)
    best = Path(result.save_dir) / "weights" / "best.pt"
    exported = YOLO(str(best)).export(
        format="onnx",
        imgsz=args.imgsz,
        opset=12,
        simplify=True,
        dynamic=False,
        nms=False,
        batch=1,
    )
    print(best)
    print(exported)
