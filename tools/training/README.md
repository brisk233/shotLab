# 球检测数据标注

`label_multi.py` 使用 YOLO-World 和 person 模型生成球检测数据集，重点保留飞行中的球。

典型调用：

```powershell
python label_multi.py video1.mp4 video2.mp4 video3.mp4
```

脚本会生成 YOLO 格式的 `images/train`、`images/val` 和对应标签。最后一段视频作为验证集。

建议正式训练前：

- 人工复核自动标签。
- 按源视频划分训练和验证集，避免同一视频的相邻帧泄漏。
- 增加无球负样本。
- 用与 Android 一致的 letterbox 预处理做导出后验证。

现有模型导出的 ONNX 输入为 `512 x 512`，单类 `ball`。
