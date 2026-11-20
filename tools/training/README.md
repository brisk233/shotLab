# 多源球检测训练与评测

当前正式模型 `app/src/main/assets/ball_yolo.onnx` 为 `416 x 416` 单类球检测器，
由旧 512 模型增量训练得到。`ball_precision.onnx` 保留旧模型，只作为 Android
主检丢失且橙色先验出现时的精准回补。

## 数据流程

1. `sources_batch01.json` 记录 19 个来源视频、BVID、作者和按视频隔离的划分。
2. `build_multisource_dataset.py` 用 YOLO-World 的 `basketball` 提示词做教师标注，
   同时保留无球硬负样本。
3. `curate_ball_labels.py` 过滤颜色/尺寸不符的教师误标，并对主检小目标做局部复核。
4. `train_ball.py` 默认从旧模型增量训练并导出 Android ONNX。
5. `benchmark_ball_models.py` 只读取 test 视频来源，输出召回、无球误报、中心误差和耗时。

train/val/test 严格按 BVID 隔离，同一视频的相邻帧不会跨集合。

## 环境

```powershell
python -m pip install -r tools/training/requirements.txt
```

PyTorch CPU 版本单独安装：

```powershell
python -m pip install torch torchvision --index-url https://download.pytorch.org/whl/cpu
```

## 构建与训练

```powershell
python tools/training/build_multisource_dataset.py
python tools/training/curate_ball_labels.py --apply
python tools/training/train_ball.py `
  --base D:\develop\dsh\work\ball-v2\runs\ball_v2\weights\best.pt `
  --epochs 12 --imgsz 416 --freeze 8
```

训练产物写入 `datasets/runs/`，不进入版本库。

## 基准

```powershell
python tools/training/benchmark_ball_models.py `
  datasets/derived/ball_yolo_v76_512.onnx `
  app/src/main/assets/ball_yolo.onnx `
  --dataset datasets/derived/ball_multisource
```

旧模型应使用 `0.25` 阈值，新主检使用 `0.08` 阈值。详细结果见
`docs/training_report_v77.md`。

本地视频、导出图片和模型权重只用于训练和回归，不对外重新分发。
