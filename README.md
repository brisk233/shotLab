# ShotLab

ShotLab 是一个离线篮球投篮分析 Android 应用。视频只在本机处理，不上传服务器；分析结果包括出手数、命中数、投篮类型、轨迹事件、姿态指标、CSV 报告和命中集锦。

当前版本：`7.9.0-batch02`

## 核心能力

- 自训 YOLO11n 篮球检测模型，ONNX Runtime Android，支持 NNAPI；416 快速主检配合橙色触发的 512 精准回补。
- `7.9.0-batch02` 新增 15 个多场景来源训练，独立测试召回 `69.19%`，无球误报 `61.35%`。
- YOLO person 多人检测，篮球只和距离最近的人做持球/离手判断。
- 手持/跟拍视频自动定位并持续跟踪移动篮筐，找筐失败才回退人工框选。
- 橙色飞行球候选补充高空漏检，并识别回球网反向轨迹，避免回程被拆成额外出手或改判命中。
- 短轨迹串联和抛物线拟合，丢帧时仍可恢复飞行轨迹。
- 预测穿过筐口后，再用真实下降轨迹复核；碰筐弹出不计命中。
- 篮筐内径 `45.72 cm` 作为米制尺度，区分上篮、两分和三分。
- 同机位篮筐记忆，同一机位后续视频可直接分析。
- 出手明细中的缩略图可点击，在当前报告页内无边框浮窗播放，并支持结束后重播。
- 单球预览按首个关键帧重新锚定 HEVC 时间轴，保持约 30fps 的稳定帧间隔，并自动失效旧缓存。
- 每次成功解析都会保存本地历史，可查看解析列表、详细统计和出手明细，并支持在报告页或历史列表中重命名。
- 命中集锦导出到 `Movies/ShotLab`，窗口为出手前 `2.0s` 到判中后 `1.2s`。
- CSV 报告写入 `Download/ShotLab`，支持系统分享。
- 诊断文件 `tracklet_dump.json` 可由 PC 端 `engine_v7.py` 原样复算。

## 工程结构

```text
app/                         Android 应用
  src/main/assets/           球、人、姿态模型
  src/main/java/             检测、跟踪、判定、报告和 UI
  src/test/                  JVM 单元测试和轨迹 fixture
tools/analysis/              PC 端轨迹引擎和验证工具
tools/training/             多源球检测数据构建、审核、训练和基准测试
```

## 构建

环境：

- JDK 17 或 21
- Android SDK 34
- Gradle 8.9

PowerShell：

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot'
$env:ANDROID_HOME='D:\android-sdk'

gradle testDebugUnitTest
gradle lintDebug
gradle assembleRelease
```

Release 当前使用 Android Debug 签名，适合内部测试；正式分发前应替换为专用 release keystore。

## 验证结果

已确认的历史验证素材：

| 素材 | 结果 |
|---|---:|
| `one_score_one_miss` | 2 投 1 中 |
| `two_score_three_miss` | 5 投 2 中 |
| `two_score_two_miss` | 4 投 2 中 |
| 手持动态筐样例（15.5 秒） | 3 投 1 中 |

`7.7.0-multisource` 多源球检测基准（603 张按视频隔离测试帧，教师辅助标签）：

| 模型 | 输入/阈值 | 召回 | 无球误报 | 平均检测耗时 |
|---|---|---:|---:|---:|
| 7.6 旧模型 | 512 / 0.25 | 60.7% | 63.8% | 29.8 ms |
| 7.7 新模型 | 416 / 0.08 | 70.0% | 59.7% | 20.2 ms |

`7.9.0-batch02` 使用扩展后的 845 张独立测试帧，与同集重跑的 7.7 基线比较：

| 模型 | 输入/阈值 | 召回 | 无球误报 | 平均检测耗时 |
|---|---|---:|---:|---:|
| 7.7 基线 | 416 / 0.08 | 65.49% | 63.75% | 52.79 ms |
| 7.9 batch02 | 416 / 0.08 | 69.19% | 61.35% | 52.07 ms |

桌面 ONNX CPU 单帧检测耗时下降约 32%。原始 2 分钟素材的最终 Android release
完整链路为 **34 投 34 中**：上篮 `11/11`、两分 `17/17`、三分 `6/6`、罚球 `0/0`。
分析耗时从首轮约 `9m31s` 降到 `6m28s`，约快 32%。旧版 34 投 34 中夹具继续保留。

第二轮性能优化后的真机分析时间约为：

| 素材 | 7.1 | 7.2 |
|---|---:|---:|
| `one_score_one_miss` | 43.1s | 17.2s |
| `two_score_three_miss` | 110.2s | 55.6s |
| `two_score_two_miss` | 86.7s | 52.9s |

性能会受手机温度、后台负载和素材内容影响。
7.9 batch02 的新增素材、数据构建和逐来源测试见 `docs/training_report_v79.md`。

## 测试

```powershell
gradle testDebugUnitTest
gradle lintDebug
python tools/analysis/test_engine_v7.py -v
```

轨迹回归 fixture 覆盖：

- `2 投 1 中`
- `5 投 2 中`
- `4 投 2 中`
- 多源 416 模型的真机 34 投 34 中及 `11/17/6` 类型统计
- 碰筐弹出、重复事件、机位检测、letterbox、帧率和调试导出

## 已知限制

- 极暗、强遮挡或篮筐长时间完全离镜时，自动找筐会回退到人工框选。
- 罚球判定、半场/全场模式和实时分析尚未完成。
- 球检测模型主要来自自动标注数据，跨新场景泛化能力仍需单独验证。
- 当前 release APK 使用 debug 签名。
