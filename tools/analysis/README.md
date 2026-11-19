# PC 轨迹分析

`engine_v7.py` 与 Android `TrackletEngine` 使用同一套出手、轨迹和筐口复核规则。

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

`export_events.py` 将逐球结果输出为带 UTF-8 BOM 的 CSV，便于 Excel 直接打开。
