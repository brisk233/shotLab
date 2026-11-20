# PC 轨迹分析

`engine_v7.py` 与 Android `TrackletEngine` 使用同一套出手、轨迹和筐口复核规则。

对于剪辑集锦，脚本会先用多条下落轨迹校正机位记忆中的筐框，再按局部穿点适
应变焦或裁切。输出中的 `rimAdjustedPx` 表示校准前后筐框中心的像素位移。
没有球场单应标定时，`type` 按出手点到校正筐口的距离与筐宽比例估算。

运行：

```powershell
python engine_v7.py fixtures/one_score_one_miss.json "0.625,0.322,0.663,0.352" 0.55
python engine_v7.py fixtures/two_score_three_miss.json "0.625,0.322,0.663,0.352" 0.55
python engine_v7.py fixtures/two_score_two_miss.json "0.625,0.322,0.663,0.352" 0.55
```

预期结果：

```text
one_score_one_miss      2 投 1 中
two_score_three_miss    5 投 2 中
two_score_two_miss      4 投 2 中
```

`extract_cands.py` 用于从视频重新生成候选点，依赖 Ultralytics 和本地 ONNX/PT 模型路径。

`extract_tracklet_video.py` 支持 AV1/HEVC、多个 ONNX 模型、15fps 采样、人物框和
筐区二次精检，用于跨来源回归：

```powershell
python tools/analysis/extract_tracklet_video.py input.mp4 output.json `
  --ball-model app/src/main/assets/ball_yolo.onnx `
  --ball-imgsz 416 --ball-conf 0.08 --fps 15
python tools/analysis/engine_v7.py output.json "0.49,0.35,0.54,0.36" 0.55
```

低分辨率且未形成多轨迹校正时，30~60px 的粗筐框会使用更宽但仍有界的球心容差，
避免把擦筐入网判成不中；大于 2 筐宽的伪轨迹仍会被拒绝。

`export_events.py` 将逐球结果输出为带 UTF-8 BOM 的 CSV，便于 Excel 直接打开。

详细训练、素材来源和版本对比见 `docs/training_report_v77.md`。
