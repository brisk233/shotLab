# ShotLab 7.9.0 batch02 球检测训练报告

日期：2026-11-24

## 交付结论

- 在 7.7 `416 x 416` 单类球检测器基础上增量训练。
- 新增 15 个来源、1163 帧和 818 个教师辅助球框。
- 最终数据：train 1456、val 367、test 845，严格按视频来源隔离。
- 845 张独立测试帧上，召回从 `65.49%` 提升到 `69.19%`。
- 无球误报从 `63.75%` 降到 `61.35%`。
- 球心归一化误差从 `0.002414` 降到 `0.002257`。
- 同机隔离复测，平均 ONNX CPU 推理从 `52.788 ms` 降到 `52.074 ms`，
  速度基本持平。

最终 Android 资产：

```text
app/src/main/assets/ball_yolo.onnx
SHA256 5843E3EFBC33ABBB080D0BC7B37037DA361F969E7561DCA1042CE15D2AF4AFAD
```

## 新增素材

Bilibili 素材通过公开接口检索并下载到本机，仅用于训练和评估，不随 APK 或仓库分发。

| BVID | 场景 | 划分 |
|---|---|---|
| BV1a6gL64EKq | NBA 球馆赛前热身 | train |
| BV1Nx1BYzECo | NBA 训练馆连续投篮 | train |
| BV1Qx4y1G7TX | 职业射手训练 | train |
| BV1Mv411k7FL | NBA 三分训练 | train |
| BV1hYbJzYE4d | 日本女篮多人训练 | train |
| BV1AC4y1u74K | 青少年女篮训练 | train |
| BV1P5yXzSEBT | 连续罚球近景 | train |
| BV1snjbzJERe | 室外公园球场 | train |
| DYNRIM001 | 用户提供的手持动态篮筐跟拍 | train |
| BV1my41187Qw | WNBA 赛前训练 | val |
| BV1RqyRzrE2J | 竖屏 NBA 赛前训练 | val |
| BV1Fd6mYGEvc | 个人连续投篮比赛 | val |
| BV1vs4y1q7T7 | NBA 球馆完整热身 | test |
| BV1jtrZYNE8N | WNBA 完整训练 | test |
| BV1WysF66EdA | 室外强光球场 | test |

完整元数据见 `tools/training/sources_batch02.json`。

## 数据处理

- 每 2 秒采样，单来源最多 180 帧。
- 教师模型：YOLO-World，提示词 `basketball`，输入 `800`。
- 保留低置信度但橙色占比高的球框，同时生成稳定硬负样本。
- 标注审计后移除 12 个低橙色误标框，并从主检候选补回 3 个小球框。
- train/val/test 不含跨集合的同一 BVID 或同一视频片段。

最终分布：

| split | images | positive | negative | boxes |
|---|---:|---:|---:|---:|
| train | 1456 | 1009 | 447 | 1291 |
| val | 367 | 279 | 88 | 335 |
| test | 845 | 594 | 251 | 782 |

## 训练策略

第一次使用 `optimizer=auto` 高学习率训练时，召回增加但误报明显恶化，
因此停止该试验，改用低学习率从 7.7 稳定模型重新微调：

```powershell
python tools/training/train_ball.py `
  --base datasets/runs/ball_multisource_v3_from_v76_416/weights/best.pt `
  --epochs 8 --imgsz 416 --batch 16 --freeze 10 `
  --optimizer AdamW --lr0 0.0003
```

最佳轮次验证指标：

- Precision：`0.620`
- Recall：`0.501`
- mAP50：`0.557`
- mAP50-95：`0.344`

## 独立测试对比

测试集同时包含旧的 603 张独立帧和 batch02 新测试来源，共 845 张。

| 指标 | 7.7 当前模型 | 7.9 batch02 |
|---|---:|---:|
| IoU 0.20 召回 | 65.49% | 69.19% |
| 无球误报 | 63.75% | 61.35% |
| 无球误报图片数 | 160 / 251 | 154 / 251 |
| 中心误差（归一化） | 0.002414 | 0.002257 |
| 平均 ONNX CPU 耗时 | 52.788 ms | 52.074 ms |

三个 batch02 新测试来源上的召回和误报均没有回退：

| 来源 | 7.7 召回 / 误报 | 7.9 召回 / 误报 |
|---|---|---|
| BV1vs4y1q7T7 | 80.0% / 81.1% | 80.0% / 81.1% |
| BV1jtrZYNE8N | 52.5% / 50.0% | 52.5% / 50.0% |
| BV1WysF66EdA | 66.7% / 55.6% | 66.7% / 55.6% |

## 应用回归

- Android 单元测试 `78/78` 通过，PC 分析引擎测试 `8/8` 通过。
- lint、debug APK、release APK 构建通过。
- 最终版本：`7.9.0-batch02`，versionCode `82`。
- 真机已安装 `82 / 7.9.0-batch02`。手持动态筐样例在无人工框选、无机位记忆条件下
  识别为 `3 投 1 中`，出手时间为 `0.000s / 6.006s / 12.144s`，命中结果为
  `true / false / false`。
- 第 2、3 次出手的单球明细均已在真机点击验证，播放器可正常打开和播放。
- 末段移动篮筐轨迹持续跟随，`14.058s` 至 `15.246s` 的筐心从
  `(0.837, 0.394)` 平滑移动到 `(0.896, 0.403)`。
- 另用一段 90 秒真人固定机位复杂训练片段复验：多人、自然光、场边遮挡，
  篮筐、投篮者和跑动人员同框。结果 `14 投 3 中`，固定筐中心保持不变，
  `rimTrack=0`、`rimAdjustedPx=0`，不再出现轨迹反向带偏筐框或全部误判命中。
- 上述真人复杂片段的第 1、第 3、第 14 次出手明细均已在真机打开播放。
  该片段筐宽约 `57px`，低于建议的 `90px`，命中率仍属于低分辨率参考结果。

## 产物

- PT：`datasets/runs/ball_multisource_v5_batch02_lowlr_416/weights/best.pt`
- ONNX：`datasets/runs/ball_multisource_v5_batch02_lowlr_416/weights/best.onnx`
- 训练曲线：`datasets/runs/ball_multisource_v5_batch02_lowlr_416/results.csv`
- 测试明细：`datasets/derived/ball_multisource/benchmark_v5_lowlr.json`
