# ShotLab

ShotLab 是一个离线篮球投篮分析 Android 应用。视频只在本机处理，不上传服务器；分析结果包括出手数、命中数、投篮类型、轨迹事件、姿态指标、CSV 报告和命中集锦。

当前版本：`7.3.0-round3`

## 核心能力

- 自训 YOLO11n 篮球检测模型，ONNX Runtime Android，支持 NNAPI。
- YOLO person 多人检测，篮球只和距离最近的人做持球/离手判断。
- 短轨迹串联和抛物线拟合，丢帧时仍可恢复飞行轨迹。
- 预测穿过筐口后，再用真实下降轨迹复核；碰筐弹出不计命中。
- 篮筐内径 `45.72 cm` 作为米制尺度，区分上篮、两分和三分。
- 同机位篮筐记忆，同一机位后续视频可直接分析。
- 出手明细中的缩略图可点击，在 App 内播放该次出手的独立片段。
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
tools/training/             球检测数据集自动标注脚本
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

已确认的验证素材和真机结果：

| 素材 | 结果 |
|---|---:|
| `one_score_one_miss` | 2 投 1 中 |
| `two_score_three_miss` | 5 投 2 中 |
| `two_score_two_miss` | 4 投 2 中 |

第二轮性能优化后的真机分析时间约为：

| 素材 | 7.1 | 7.2 |
|---|---:|---:|
| `one_score_one_miss` | 43.1s | 17.2s |
| `two_score_three_miss` | 110.2s | 55.6s |
| `two_score_two_miss` | 86.7s | 52.9s |

性能会受手机温度、后台负载和素材内容影响。

## 测试

```powershell
gradle testDebugUnitTest
gradle lintDebug
```

轨迹回归 fixture 覆盖：

- `2 投 1 中`
- `5 投 2 中`
- `4 投 2 中`
- 碰筐弹出、重复事件、机位检测、letterbox、帧率和调试导出

## 已知限制

- 自动找筐尚未完成，当前依赖用户标定和机位记忆。
- 罚球判定、半场/全场模式和实时分析尚未完成。
- 球检测模型主要来自自动标注数据，跨新场景泛化能力仍需单独验证。
- 当前 release APK 使用 debug 签名。
