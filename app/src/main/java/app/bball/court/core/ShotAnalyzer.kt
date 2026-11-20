package app.bball.court.core

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * 视频 → 投篮报告。A+B 双引擎：
 *
 *   A（检测）：416 YOLO 主检给跟踪器可信绝对位置；主检丢失且存在橙色篮球先验时，
 *              再用 512 YOLO 做一次精准回补。MediaPipe 仅在 ONNX 不可用时兜底。
 *   B（跟踪）：TemplateTracker，中间帧做 NCC 局部搜索 + 匀速预测，几毫秒一帧。
 *
 * 两者写进同一条 track 缓冲，抛物线拟合只认这一条序列；检测只负责校正，
 * 因此不会出现"两条不同精度的数据打架"。
 *
 * 全链路 try/catch：一帧解不出来、一次推理抛异常，都只降低结果质量，不让 App 死。
 */
class ShotAnalyzer(private val context: Context) {

    data class Options(
        /** 集锦里动作切换快；15fps 在召回和真机耗时之间取得平衡。 */
        val sampleFps: Int = 15,
        /** 读取不到容器帧率时的兜底帧率。 */
        val sourceFps: Double = 30.0,
        val analyzeWidthPx: Int = 720,
        val poseEveryNFrames: Int = 6,
        /** 多人检测间隔；中间帧复用上一组人体框，避免每帧重跑 320 模型。 */
        val personEveryNFrames: Int = 6,
        /** 两次完整球检测之间由模板跟踪补两帧，降低移动端重复推理。 */
        val detectorEveryNFrames: Int = 2,
        /** 是否在篮筐附近再跑一次放大 ROI 模型；完整模型足够准时可关闭以省一次推理。 */
        val refineRimRoi: Boolean = true,
        /** 用户在第一帧点的球（归一化），有它就能从第一帧开始跟踪。 */
        val seedPoint: NPt? = null,
        /** 球场四点标定结果；为空时不估算米制距离和投篮类型。 */
        val homography: CourtHomography? = null,
        /** 筐框是否来自机位记忆（自动套用）。 */
        val autoDetected: Boolean = false,
    )

    data class Live(
        val progress: Float = 0f,
        val report: ShotReport = ShotReport(),
        val trackHits: Int = 0,
        val detectHits: Int = 0,
        val lostCount: Int = 0,
    )

    suspend fun analyze(
        uri: Uri,
        hoop: NRect,
        options: Options = Options(),
        onProgress: (Float, ShotReport) -> Unit = { _, _ -> },
    ): ShotReport = withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        try { retriever.setDataSource(context, uri) }
        catch (t: Throwable) { return@withContext ShotReport(error = "打不开视频：" + (t.message ?: t.javaClass.simpleName)) }

        val durationMs = try {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) { 0L }
        if (durationMs <= 0L) {
            try { retriever.release() } catch (_: Throwable) {}
            return@withContext ShotReport(error = "读不到视频时长，换 mp4/H.264 再试")
        }
        val sourceFps = readSourceFps(context, uri, retriever)
            ?.let(VideoFrameClock::sanitizeFps)
            ?: VideoFrameClock.sanitizeFps(options.sourceFps)
        Log.i("ShotLab", "source fps=$sourceFps")

        var frames = 0
        var hits = 0
        var detectHits = 0
        var trackHits = 0
        var colorHits = 0
        var pendingShoulderY = 0.45f
        var pendingApproach0 = 0f
        var pendingRose = 0f
        val releaseAngles = mutableListOf<Float>()
        val elbowAngles = mutableListOf<Float>()
        val kneeAngles = mutableListOf<Float>()
        val inHandTimes = mutableListOf<Long>()
        var track = mutableListOf<BallSample>()
        var shotStartMs: Long? = null
        var fatal: String? = null
        val stepMs = (1000L / options.sampleFps).coerceAtLeast(60L)
        val sensitivity = Sensitivity.load(context)
        var sinceDetect = Int.MAX_VALUE
        val tracker = TemplateTracker()
        val colorFinder = ColorBallFinder()

        val ball = BallDetector(context)
        val yolo = YoloBallDetector(context)
        // 416 主检负责速度；旧 512 模型只在主检丢失且橙色先验出现时回补，
        // 用于极低分辨率、高弧球和短暂遮挡，避免全帧恢复扫描拖慢整体。
        val yoloPrecision = YoloBallDetector(
            context,
            assetName = "ball_precision.onnx",
            confThreshold = 0.25f,
        )
        val personDet = PersonDetector(context)
        var crop: Letterbox.Crop? = null
        var lastShotEndMs = -100000L
        var lastPerson: NRect? = null
        var lastPersons: List<NRect> = emptyList()
        val obsFrames = ArrayList<TrackletEngine.FrameObs>()
        val thumbs = HashMap<Long, android.graphics.Bitmap>()
        var prevSig: FloatArray? = null
        var cuts = 0
        val ballW = ArrayList<Float>()
        var hoopFinal: NRect? = null
        var scaledW = 0
        var frameHForEngine = 1280
        var tDecode = 0L; var tDetect = 0L; var tPerson = 0L; var tRoi = 0L
        var tPose = 0L; var tTrack = 0L; var tPrecision = 0L
        var nDecode = 0; var nDetect = 0; var nPerson = 0; var nRoi = 0; var nPose = 0; var nTrack = 0
        var nPrecision = 0; var precisionHits = 0
        val pose = PoseEstimator(context)
        try {
            if (ball.initError != null && pose.initError != null) {
                return@withContext ShotReport(error = "模型初始化失败：" + ball.initError + " / " + pose.initError)
            }
            var t = 0L
            while (t < durationMs) {
                ensureActive()
                // 直接按目标宽度解码（getScaledFrameAtTime），比"整帧解码再缩放"快 3-5 倍
                val tA = System.currentTimeMillis()
                // 批量取帧：一次解码 8 帧连续画面，摊薄"每次请求都从关键帧重解"的开销
                // 实测单帧 getScaledFrameAtTime 要 651ms，占总耗时 96%
                val frame = try {
                    // 顺序解码的 YUV→Bitmap 转换在 Kotlin 里太慢（实测更差），默认走批量取帧
                    batchFrame(retriever, t, options, sourceFps)
                } catch (t2: Throwable) { null }
                tDecode += System.currentTimeMillis() - tA; nDecode++
                if (frame != null) {
                    var scaled: android.graphics.Bitmap? = if (frame.width <= options.analyzeWidthPx) frame else try { frame.scaledToWidth(options.analyzeWidthPx) } catch (t2: Throwable) { null }
                    if (scaled != null) {
                        val sig = SceneCut.signature(scaled)
                        if (SceneCut.isCut(prevSig, sig)) {
                            cuts++
                            tracker.reset()
                            track.clear()
                            shotStartMs = null
                        }
                        prevSig = sig
                        if (crop == null) crop = Letterbox.contentCrop(scaled)
                        val c = crop!!
                        val preH = scaled.height
                        if (c.height < scaled.height) {
                            scaled = try { Bitmap.createBitmap(scaled, 0, c.top, scaled.width, c.height) } catch (t2: Throwable) { scaled }
                        }
                        // 筐框是用户在全帧上画的；分析用的是裁边后的位图 → 必须换算坐标系
                        val hoopA = if (c.height < preH) NRect(
                            hoop.left,
                            ((hoop.top * preH - c.top) / c.height).coerceIn(0f, 1f),
                            hoop.right,
                            ((hoop.bottom * preH - c.top) / c.height).coerceIn(0f, 1f),
                        ) else hoop
                        hoopFinal = hoopA
                        scaledW = scaled.width
                        frameHForEngine = scaled.height

                        // ---- A+B: 检测校正 + 跟踪补帧 ----------------------------------
                        var sample: BallSample? = null
                        var detPos: NPt? = null
                        val rad = takeIf { track.isNotEmpty() }?.let { track.last().radius } ?: 0.03f
                        if (tracker.hasTarget && sinceDetect < options.detectorEveryNFrames.coerceAtLeast(0)) {
                            val tT = System.currentTimeMillis()
                            val p = tracker.update(scaled)
                            tTrack += System.currentTimeMillis() - tT; nTrack++
                            if (p != null) { sample = BallSample(t, p, tracker.lastScore, rad); trackHits++ }
                        }
                        if (sample == null) {
                            val seed = options.seedPoint
                            val tD = System.currentTimeMillis()
                            var det = if (frames == 0 && seed != null && tracker.init(scaled, seed)) {
                                BallSample(t, seed, 1f)
                            } else {
                                // 自训模型可用时不再跑 MediaPipe（后者召回只有 3%，纯浪费算力）
                                val y = if (yolo.initError == null) yolo.detect(scaled) else null
                                if (yolo.initError != null) (y ?: ball.detect(scaled)) else y
                            }
                            var colorSeed: Pair<NPt, Float>? = null
                            if (det == null && frames % 2 == 0) {
                                val players = if (lastPersons.isNotEmpty()) lastPersons else listOfNotNull(lastPerson)
                                colorSeed = colorFinder.find(scaled, players)
                                if (colorSeed != null && yoloPrecision.initError == null) {
                                    val tPr = System.currentTimeMillis()
                                    det = yoloPrecision.detect(scaled)
                                    tPrecision += System.currentTimeMillis() - tPr; nPrecision++
                                    if (det != null) precisionHits++
                                }
                            }
                            detPos = det?.p
                            if (det != null) {
                                tracker.init(scaled, det.p, det.radius)
                                sample = det
                                detectHits++
                                sinceDetect = 0
                            } else {
                                // CNN 没命中：用橙色先验补一个种子（篮球最稳的特征）
                                val blob = colorSeed
                                if (blob != null && tracker.init(scaled, blob.first, blob.second)) {
                                    sample = BallSample(t, blob.first, 0.5f, blob.second)
                                    colorHits++; sinceDetect = 0
                                } else {
                                    tracker.reset()
                                    sinceDetect++
                                }
                            }
                            tDetect += System.currentTimeMillis() - tD; nDetect++
                        } else sinceDetect++

                        if (sample != null) { hits++; track.add(sample); if (sample.radius > 0f) ballW.add(sample.radius * 2f) }
                        else if (track.isNotEmpty()) track.add(BallSample(t, track.last().p, 0f))

                        val tP = System.currentTimeMillis()
                        val body = if (frames % options.poseEveryNFrames == 0) pose.detect(scaled) else null
                        if (body != null) { tPose += System.currentTimeMillis() - tP; nPose++ }

                        if (body != null && track.isNotEmpty()) {
                            val ballNow = track.last().p
                            val wrist = body.nearerWrist(ballNow)
                            val near = hypot(wrist.x - ballNow.x, wrist.y - ballNow.y) < 0.16f
                            if (near && shotStartMs == null && track.size >= 2) {
                                val vy = ballNow.y - track[track.size - 2].p.y
                                if (vy < -0.004f) {
                                    shotStartMs = t
                                    track = track.takeLast(6).toMutableList()
                                    val (s, e, w) = body.sameChain(ballNow)
                                    elbowAngles.add(Geometry.angleDeg(s, e, w))
                                    body.kneeAngle()?.let { kneeAngles.add(it) }
                                }
                            }
                        }

                        // 逐帧候选段只用于姿态和持球时长指标；最终出手与命中由下方轨迹引擎统一统计。
                        run {
                                                        val tPD = System.currentTimeMillis()
                                                        val detectPeople = frames % options.personEveryNFrames.coerceAtLeast(1) == 0
                                                        val allP = if (detectPeople) personDet.detectAll(scaled) else lastPersons
                                                        if (detectPeople) { tPerson += System.currentTimeMillis() - tPD; nPerson++ }
                                                        lastPersons = allP
                                                        val pboxDet = allP.firstOrNull() ?: if (detectPeople) ball.detectPerson(scaled) else lastPerson
                            if (pboxDet != null) lastPerson = pboxDet
                            val pbox = body?.let { b ->
                                // 用全部关键点（手腕/手肘/膝/踝都算）近似全身框，和 YOLO 人体框一致
                                val xs = listOf(b.shoulderL.x, b.shoulderR.x, b.hipL.x, b.hipR.x, b.kneeL.x, b.kneeR.x, b.ankleL.x, b.ankleR.x, b.wristL.x, b.wristR.x)
                                val ys = listOf(b.shoulderL.y, b.shoulderR.y, b.hipL.y, b.hipR.y, b.kneeL.y, b.kneeR.y, b.ankleL.y, b.ankleR.y, b.wristL.y, b.wristR.y)
                                val l = xs.min(); val r = xs.max()
                                val t0 = ys.min(); val b0 = ys.max()
                                val hgt = (b0 - t0).coerceAtLeast(0.05f)
                                NRect(
                                    (l - 0.06f).coerceIn(0f, 1f), (t0 - hgt * 0.55f).coerceIn(0f, 1f),
                                    (r + 0.06f).coerceIn(0f, 1f), (b0 + hgt * 0.9f).coerceIn(0f, 1f),
                                )
                            }
                            if (pbox != null && pboxDet == null) lastPerson = pbox
                            // ---- 筐区 ROI 精检：构图不动，只在筐周围放大后再次检测 ----
                            // 你的机位必须覆盖三分线，筐只占 41px；命中最需要精度的地方恰恰是筐附近。
                            // 做法：裁 4.5×筐宽且最大 16% 画幅的方块，再单独检测球心。
                            var roiSample: NPt? = null
                            val ballNow = sample?.p ?: track.lastOrNull()?.p
                            val sampleConfidence = sample?.score ?: 0f
                            if (options.refineRimRoi &&
                                yolo.initError == null &&
                                hoopA.w * scaled.width >= RoiRefinementPolicy.MIN_RIM_PIXELS &&
                                RoiRefinementPolicy.shouldRun(ballNow, sampleConfidence, hoopA)
                            ) {
                                val cx = hoopA.cx
                                val cy = (hoopA.top + hoopA.bottom) / 2f
                                val half = RoiRefinementPolicy.roiHalf(hoopA)
                                val l = (cx - half).coerceIn(0f, 1f); val t0 = (cy - half).coerceIn(0f, 1f)
                                val r = (cx + half).coerceIn(0f, 1f); val b0 = (cy + half).coerceIn(0f, 1f)
                                val x0 = (l * scaled.width).toInt()
                                val y0 = (t0 * scaled.height).toInt()
                                val w0 = ((r - l) * scaled.width).toInt().coerceAtLeast(24)
                                val h0 = ((b0 - t0) * scaled.height).toInt().coerceAtLeast(24)
                                if (x0 + w0 <= scaled.width && y0 + h0 <= scaled.height) {
                                    val roiBmp = android.graphics.Bitmap.createBitmap(scaled, x0, y0, w0, h0)
                                    val roiScaled = android.graphics.Bitmap.createScaledBitmap(roiBmp, 320, (320 * h0 / w0).coerceAtLeast(32), true)
                                    val tR = System.currentTimeMillis()
                                    val rp = yolo.detect(roiScaled)
                                    tRoi += System.currentTimeMillis() - tR; nRoi++
                                    if (rp != null && rp.score > 0.16f) {
                                        roiSample = NPt(
                                            (x0 + rp.p.x * w0) / scaled.width,
                                            (y0 + rp.p.y * h0) / scaled.height,
                                        )
                                    }
                                    if (roiScaled !== roiBmp) roiScaled.recycle()
                                    roiBmp.recycle()
                                }
                            }
                            // 收集观测给轨迹引擎（最多 3 个球候选）
                            run {
                                val pb = pboxDet ?: lastPerson
                                val pbAll = if (lastPersons.isNotEmpty()) lastPersons else listOfNotNull(pb)
                                val cands = ArrayList<TrackletEngine.Obs>()
                                roiSample?.let { cands.add(TrackletEngine.Obs(t, it.x, it.y, 0.9f)) }
                                detPos?.let { cands.add(TrackletEngine.Obs(t, it.x, it.y, 0.8f)) }
                                sample?.let { cands.add(TrackletEngine.Obs(t, it.p.x, it.p.y, it.score)) }
                                if (cands.isNotEmpty()) obsFrames.add(TrackletEngine.FrameObs(t, cands, pb, pbAll))
                                if (t % 1000 < stepMs) {
                                    try {
                                        val th = makeThumb(scaled, 0.5f, 0.5f)
                                        if (th != null) thumbs[t] = th
                                    } catch (_: Throwable) {}
                                }
                            }
                        }
                        // 球位置跳变 > 20% 画面高度 = 剪辑硬切，重置状态
                        if (track.size >= 2) {
                            val a0 = track[track.size - 2].p
                            val b0 = track.last().p
                            if (kotlin.math.hypot(a0.x - b0.x, a0.y - b0.y) > 0.20f) {
                                tracker.reset(); track.clear(); shotStartMs = null
                            }
                        }

                        // 出手条件收紧：① 离手 ② 上升超过肩高 ③ 朝筐方向位移 > 1.5 筐宽
                        if (shotStartMs == null && track.size >= 3) {
                            val dy = track[track.size - 1].p.y - track[track.size - 3].p.y
                            if (dy < -0.02f && t - lastShotEndMs > sensitivity.minIntervalMs) {
                                val p = track.last().p
                                val shoulderY = body?.let { minOf(it.shoulderL.y, it.shoulderR.y) } ?: 0.45f
                                // 记录候选，等后续帧确认是否满足"过肩 / 接近筐"
                                shotStartMs = t
                                pendingShoulderY = shoulderY
                                pendingApproach0 = kotlin.math.abs(p.x - hoopA.cx)
                                pendingRose = 0f
                                track = track.takeLast(6).toMutableList()
                            }
                        }

                        val start = shotStartMs
                        if (start != null) {
                            track.lastOrNull()?.let { s ->
                                if (s.p.y < pendingShoulderY) pendingRose = 1f
                            }
                            val approach = kotlin.math.abs((track.lastOrNull()?.p?.x ?: hoopA.cx) - hoopA.cx)
                            val needRise = pendingRose > 0f || !sensitivity.requireRise
                            val plausibleShot = needRise || (pendingApproach0 - approach) > sensitivity.riseFactor * 1.5f * hoop.w
                            val done = Geometry.isMade(track, hoopA) ||
                                (track.isNotEmpty() && track.last().p.y > hoopA.bottom + 0.06f) ||
                                (t - start > 2500)
                            if (done) {
                                // 旧逐帧逻辑只保留姿态和持球时长指标，不再修改出手、命中或明细。
                                val eAngle = Geometry.releaseAngle(track)
                                val plausible = plausibleShot && track.size >= 3 && t - start >= 150 && start > 1500
                                if (plausible) {
                                    eAngle?.let { releaseAngles.add(it) }
                                    inHandTimes.add((t - start).coerceAtLeast(0))
                                }
                                track = mutableListOf()
                                shotStartMs = null
                                lastShotEndMs = t
                            }
                        }
                        if (track.size > 40) track = track.takeLast(30).toMutableList()
                        frames++
                        onProgress(
                            (t.toFloat() / durationMs).coerceIn(0f, 1f),
                            ShotReport(
                                avgReleaseAngle = releaseAngles.avgF(),
                                avgElbowAngle = elbowAngles.avgF(),
                                avgKneeAngle = kneeAngles.avgF(),
                                avgBallInHandMs = inHandTimes.avgL(),
                                framesAnalysed = frames,
                                ballHitRate = if (frames == 0) 0f else hits.toFloat() / frames,
                            ),
                        )
                    }
                }
                t += stepMs
            }
            Log.i("ShotLab", "timing decode=" + (if (nDecode > 0) tDecode / nDecode else 0L) + "ms/" + nDecode +
                " detect=" + (if (nDetect > 0) tDetect / nDetect else 0L) + "ms/" + nDetect +
                " person=" + (if (nPerson > 0) tPerson / nPerson else 0L) + "ms/" + nPerson +
                " roi=" + (if (nRoi > 0) tRoi / nRoi else 0L) + "ms/" + nRoi +
                " pose=" + (if (nPose > 0) tPose / nPose else 0L) + "ms/" + nPose +
                " track=" + (if (nTrack > 0) tTrack / nTrack else 0L) + "ms/" + nTrack +
                " precision=" + (if (nPrecision > 0) tPrecision / nPrecision else 0L) + "ms/" + nPrecision)
            Log.i(
                "ShotLab",
                "cuts=" + cuts + " done frames=" + frames + " detect=" + detectHits +
                    " precision=" + precisionHits + " color=" + colorHits + " track=" + trackHits,
            )
        } catch (t: Throwable) {
            Log.w("ShotLab", "analysis stopped", t)
            fatal = t.message ?: t.javaClass.simpleName
        } finally {
            try { clearBatch() } catch (_: Throwable) {}
            try { personDet.close() } catch (_: Throwable) {}
            ball.close(); pose.close(); yolo.close(); yoloPrecision.close()
            try { retriever.release() } catch (_: Throwable) {}
        }


        // ---- 轨迹法是最终出手、命中、类型和节奏的唯一来源 ----
        val engineRim = hoopFinal ?: hoop
        val trackletResult = TrackletEngine.runDetailed(
            obsFrames,
            engineRim,
            scaledW.takeIf { it > 0 } ?: 0,
            frameHForEngine,
            options.homography,
        )
        val trackletEvents = trackletResult.events
        val finalRim = trackletResult.rim
        val summary = ShotAggregator.summarize(trackletEvents)
        try {
            val file = java.io.File(context.filesDir, "tracklet_dump.json")
            file.writeText(
                TrackletDump.build(
                    sourceFps = sourceFps,
                    analysisWidth = scaledW,
                    analysisHeight = frameHForEngine,
                    rim = engineRim,
                    correctedRim = finalRim,
                    frames = obsFrames,
                    events = summary.events,
                    rimAdjustedPx = trackletResult.rimAdjustedPx,
                ),
            )
            Log.i("ShotLab", "tracklet dump written " + file.length() + " bytes")
        } catch (t: Throwable) {
            Log.w("ShotLab", "tracklet dump failed", t)
        }
        val shotLog = summary.events.map { ev ->
            val hm = options.homography
            val cx0 = hm?.toCourt(ev.x, ev.y)?.first ?: 0f
            val cy0 = hm?.toCourt(ev.x, ev.y)?.second ?: 0f
            val th = thumbs.entries.minByOrNull { kotlin.math.abs(it.key - ev.releaseMs) }?.value
            ShotEvent(
                startFrame = 0,
                tMs = ev.releaseMs,
                type = ev.type,
                made = ev.made,
                releaseX = ev.x,
                releaseY = ev.y,
                distanceM = ev.distM,
                releaseAngle = null,
                thumb = th,
                courtX = cx0,
                courtY = cy0,
                makeMs = if (ev.made) ev.resolveMs else 0L,
                resolveMs = ev.resolveMs,
            )
        }
        Log.i("ShotLab", "tracklet events=" + trackletEvents.size + " frames=" + obsFrames.size)
        Log.i("ShotLab", "shots=" + shotLog.size + " types=" + shotLog.groupingBy { it.type.label }.eachCount())
        for (e in shotLog) {
            Log.i("ShotLab", "SHOT t=" + (e.tMs / 1000.0) + "s type=" + e.type.label +
                " made=" + e.made + " dist=" + String.format("%.2f", e.distanceM) +
                " rx=" + String.format("%.2f", e.releaseX) + " ry=" + String.format("%.2f", e.releaseY))
        }
        ShotReport(
            attempts = summary.attempts, made = summary.made,
            layupMade = summary.layupMade, layupAtt = summary.layupAtt,
            twoMade = summary.twoMade, twoAtt = summary.twoAtt,
            threeMade = summary.threeMade, threeAtt = summary.threeAtt,
            ftMade = summary.ftMade, ftAtt = summary.ftAtt,
            shots = shotLog,
            calibrated = options.homography != null,
            autoRim = options.autoDetected,
            rimPx = (finalRim.w * (scaledW.takeIf { it > 0 } ?: 0)).toInt(),
            rimAdjustedPx = trackletResult.rimAdjustedPx,
            rimBallRatio = if (ballW.isEmpty()) 0f else {
                val med = ballW.sorted()[ballW.size / 2]
                val hw = finalRim.w
                if (med > 0f) hw / med else 0f
            },
            bestStreak = summary.bestStreak,
            bestMissStreak = summary.bestMissStreak,
            avgIntervalMs = summary.avgIntervalMs,
            avgReleaseAngle = releaseAngles.avgF(),
            avgElbowAngle = elbowAngles.avgF(),
            avgKneeAngle = kneeAngles.avgF(),
            avgBallInHandMs = inHandTimes.avgL(),
            framesAnalysed = frames,
            ballHitRate = if (frames == 0) 0f else hits.toFloat() / frames,
            error = fatal,
        )
    }

    /** 第一帧，给标定页用。永不抛。 */
    suspend fun firstFrame(uri: Uri, width: Int = 960, atMs: Long = 0L): Bitmap? = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val raw = r.getFrameAtTime(atMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@withContext null
            try { raw.scaledToWidth(width) } catch (t: Throwable) { raw }
        } catch (t: Throwable) {
            Log.w("ShotLab", "firstFrame failed", t); null
        } finally { try { r.release() } catch (_: Throwable) {} }
    }
}

/**
 * 出手瞬间缩略图：以出手点为中心裁一块（不是整帧），否则缩略图里全是字幕，
 * 用户根本没法核对"这一下到底是不是投篮"。
 */
private fun makeThumb(src: android.graphics.Bitmap?, rx: Float, ry: Float): android.graphics.Bitmap? {
    val b = src ?: return null
    return try {
        val winW = (b.width * 0.45f).toInt().coerceAtLeast(64)
        val winH = (b.height * 0.45f).toInt().coerceAtLeast(64)
        val cx = (rx * b.width).toInt()
        val cy = (ry * b.height).toInt()
        val x0 = (cx - winW / 2).coerceIn(0, (b.width - winW).coerceAtLeast(0))
        val y0 = (cy - winH / 2).coerceIn(0, (b.height - winH).coerceAtLeast(0))
        val cropped = android.graphics.Bitmap.createBitmap(b, x0, y0, winW, winH)
        val w = 240
        val h = (cropped.height * (w.toFloat() / cropped.width)).toInt().coerceAtLeast(1)
        android.graphics.Bitmap.createScaledBitmap(cropped, w, h, true)
    } catch (t: Throwable) { null }
}

/** 批量取帧缓存：避免每帧都从关键帧重解。 */
private var batchStartIdx = -1
private val batchFrames = ArrayList<android.graphics.Bitmap>()

private fun clearBatch() {
    for (b in batchFrames) if (!b.isRecycled) b.recycle()
    batchFrames.clear()
    batchStartIdx = -1
}

private fun batchFrame(
    retriever: MediaMetadataRetriever,
    tMs: Long,
    options: ShotAnalyzer.Options,
    sourceFps: Double,
): android.graphics.Bitmap? {
    val idx = VideoFrameClock.frameIndexAt(tMs, sourceFps)
    if (batchStartIdx < 0 || idx < batchStartIdx || idx >= batchStartIdx + 16) {
        clearBatch()
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val got = try { retriever.getFramesAtIndex(idx, 16) } catch (t: Throwable) { null }
            if (got != null && got.isNotEmpty()) {
                batchFrames.addAll(got)
                batchStartIdx = idx
            }
        }
        if (batchFrames.isEmpty()) {
            // 兜底：老系统或失败时退回单帧取
            return try {
                if (android.os.Build.VERSION.SDK_INT >= 27)
                    retriever.getScaledFrameAtTime(tMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, options.analyzeWidthPx, 720)
                else retriever.getFrameAtTime(tMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (t: Throwable) { null }
        }
    }
    return batchFrames.getOrNull(idx - batchStartIdx)
}

private fun List<Float>.avgF(): Float? = if (isEmpty()) null else average().toFloat()
private fun List<Long>.avgL(): Long? = if (isEmpty()) null else average().toLong()

/** 容器帧率优先；读取不到时再退到拍摄帧率。 */
private fun readSourceFps(
    context: Context,
    uri: Uri,
    retriever: MediaMetadataRetriever,
): Double? {
    var extractor: MediaExtractor? = null
    try {
        extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("video/")) continue
            val containerFps = formatFps(format)
            if (containerFps != null) return containerFps
            break
        }
    } catch (t: Throwable) {
        Log.w("ShotLab", "read container fps failed", t)
    } finally {
        try { extractor?.release() } catch (_: Throwable) {}
    }

    return try {
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            ?.toDoubleOrNull()
            ?.takeIf { it in 1.0..240.0 }
    } catch (_: Throwable) {
        null
    }
}

private fun formatFps(format: MediaFormat): Double? {
    if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) return null
    val value = try {
        format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
    } catch (_: Throwable) {
        try { format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() } catch (_: Throwable) { null }
    }
    return value?.takeIf { it in 1.0..240.0 }
}

/** MediaPipe 只收 ARGB_8888；MediaMetadataRetriever 给的是 RGB_565 / HARDWARE。 */
private fun Bitmap.ensureArgb8888(): Bitmap {
    val src = this
    if (src.config == Bitmap.Config.ARGB_8888) return src
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(out).drawBitmap(src, 0f, 0f, null)
    return out
}

private fun Bitmap.scaledToWidth(width: Int): Bitmap {
    val scaled = if (this.width <= width) this else {
        val h = (height * (width.toFloat() / this.width)).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(this, width, h, true)
    }
    return scaled.ensureArgb8888()
}
