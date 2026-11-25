package app.bball.court.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 轨迹法出手/命中判定（与 PC 端 engine_v7 保持一致）。
 *
 * 为什么不再用"状态机逐帧判定"：
 *   逐帧判定要求球每帧都被检测到；实测在飞行段会丢帧，速度全成噪声。
 *   轨迹法先把检测点串成短轨迹，再用"抛物线形状 + 与筐平面的交点"判中/不中，
 *   对丢帧天然免疫（这正是 PC 端能跑出真值的原因）。
 *
 * 规则：
 *   1. 建轨迹：按局部距离关联候选点；球进入人体框 = 手里的球 → 断轨
 *   2. 优先使用当前时刻的自动跟踪筐框；无动态轨迹时再用多条轨迹共同穿点校正
 *   3. 只对"起手→最高点→下落少许"这一段做二次拟合（避免落地弹跳污染）
 *   4. 拟合曲线必须上凸（a>0），校正后优先使用下降段交点
 *   5. 低分辨率筐框不再用横向投影误差制造假 miss，改按高弧下落轨迹计数
 *   6. 回球网反向轨迹使用首次接近筐口前的局部速度，不把回程算成新出手
 */
object TrackletEngine {

    data class Obs(
        val t: Long,
        val x: Float,
        val y: Float,
        val conf: Float,
        val segment: Int = 0,
    )

    data class FrameObs(
        val t: Long,
        val cands: List<Obs>,
        val person: NRect?,
        val persons: List<NRect> = emptyList(),
        val segment: Int = 0,
    )

    data class Event(
        val releaseMs: Long,
        val resolveMs: Long,
        val made: Boolean,
        val x: Float,
        val y: Float,
        val distM: Float,
        val type: ShotType,
        val segment: Int = 0,
    )

    data class Result(
        val events: List<Event>,
        val rim: NRect,
        val rimAdjustedPx: Int,
    )

    private data class Tr(val pts: ArrayList<Obs>, var missedFrames: Int = 0)

    fun run(
        frames: List<FrameObs>,
        rim: NRect,
        frameW: Int,
        frameH: Int,
        homography: CourtHomography? = null,
        rimTrack: List<RimTrackPoint> = emptyList(),
        allowLowResMade: Boolean = true,
        allowRimRefinement: Boolean = true,
    ): List<Event> = runDetailed(
        frames,
        rim,
        frameW,
        frameH,
        homography,
        rimTrack,
        allowLowResMade,
        allowRimRefinement,
    ).events

    fun runDetailed(
        frames: List<FrameObs>,
        rim: NRect,
        frameW: Int,
        frameH: Int,
        homography: CourtHomography? = null,
        rimTrack: List<RimTrackPoint> = emptyList(),
        allowLowResMade: Boolean = true,
        allowRimRefinement: Boolean = true,
    ): Result {
        if (frames.isEmpty() || rim.w <= 1e-4f || frameW <= 0 || frameH <= 0) {
            return Result(emptyList(), rim, 0)
        }

        // ---------- 1) 建轨迹 ----------
        val tracklets = ArrayList<Tr>()
        var active = ArrayList<Int>()
        for (fr in frames) {
            val newActive = ArrayList<Int>()
            val used = HashSet<Int>()
            for (idx in active) {
                val tr = tracklets[idx]
                if (tr.pts.isEmpty()) continue
                val last = tr.pts.last()
                if (last.segment != fr.segment) continue
                val dt = ((fr.t - last.t) / 1000f).coerceAtLeast(1e-3f)
                if (dt > MAX_TRACK_GAP_SECONDS) continue
                val previous = tr.pts.getOrNull(tr.pts.lastIndex - 1)
                val expected = if (previous != null) {
                    val observedDt = ((last.t - previous.t) / 1000f).coerceAtLeast(1e-3f)
                    NPt(
                        last.x + (last.x - previous.x) * (dt / observedDt),
                        last.y + (last.y - previous.y) * (dt / observedDt),
                    )
                } else {
                    NPt(last.x, last.y)
                }
                val gate = (TRACK_GATE + TRACK_GATE_PER_SECOND * dt).coerceAtMost(MAX_TRACK_GATE)
                var best: Obs? = null
                var bestD = Float.MAX_VALUE
                for ((ci, c) in fr.cands.withIndex()) {
                    if (ci in used) continue
                    val d = hypot(c.x - expected.x, c.y - expected.y)
                    if (d < gate && d < bestD) { bestD = d; best = c }
                }
                if (best != null) {
                    // 离手切分：只和"离球最近的那个人"比较——多人场景下不会被别人挡住
                    val pb = nearestPerson(best, fr)
                    if (pb != null) {
                        val padX = (pb.right - pb.left) * 0.15f
                        val bottomPad = (pb.bottom - pb.top) * 0.10f
                        // 人体框顶部就是离手边界，不再向上扩张，避免把头顶上方的飞行段误判为持球。
                        if (best.x >= pb.left - padX && best.x <= pb.right + padX &&
                            best.y >= pb.top && best.y <= pb.bottom + bottomPad) continue
                    }
                    used.add(fr.cands.indexOf(best))
                    tr.pts.add(best)
                    tr.missedFrames = 0
                    newActive.add(idx)
                } else {
                    // 单帧漏检时保留轨迹，下一次用速度预测重新连接；连续漏检过多才结束。
                    tr.missedFrames++
                    if (tr.missedFrames <= MAX_MISSED_FRAMES) newActive.add(idx)
                }
            }
            for ((ci, c) in fr.cands.withIndex()) {
                if (ci in used) continue
                val pb = nearestPerson(c, fr)
                if (pb != null) {
                    val padX = (pb.right - pb.left) * 0.15f
                    val bottomPad = (pb.bottom - pb.top) * 0.10f
                    if (c.x >= pb.left - padX && c.x <= pb.right + padX &&
                        c.y >= pb.top && c.y <= pb.bottom + bottomPad) continue
                }
                tracklets.add(Tr(arrayListOf(c)))
                newActive.add(tracklets.size - 1)
            }
            active = newActive
        }

        val trustedRimTrack = rimTrack.filter { it.confidence >= MIN_DYNAMIC_RIM_CONFIDENCE }
        val hasSceneCut = frames.any { it.segment > 0 }
        val refinedRim = if (hasSceneCut || !allowRimRefinement) {
            null
        } else {
            refineRimFromTrajectories(tracklets, rim, frameW, frameH)
        }
        val engineRim = refinedRim?.rim ?: rim
        val roughRimCy = (rim.top + rim.bottom) / 2f
        val roughRimLowRes = rim.w * frameW < MIN_RELIABLE_RIM_PX
        val relaxedRoughRimTolerance = roughRimLowRes &&
            frameW <= LOW_RES_TOLERANCE_MAX_FRAME_W &&
            rim.w * frameW >= MIN_ADAPTIVE_ROUGH_RIM_PX
        val lowResCorrectedRim = refinedRim != null && roughRimLowRes
        val lowResFallbackMade = lowResCorrectedRim && allowLowResMade

        // ---------- 2) 抛物线筛选 ----------
        val events = ArrayList<Event>()
        for (tr in tracklets) {
            val pts = tr.pts
            val minTrackPoints = if (lowResCorrectedRim) 4 else 5
            val minTrackSpan = if (lowResCorrectedRim) 0.20f else 0.30f
            if (pts.size < minTrackPoints) continue
            val span = (pts.last().t - pts.first().t) / 1000f
            if (span < minTrackSpan) continue
            val segment = pts.first().segment
            val segmentRimTrack = trustedRimTrack.filter { it.segment == segment }
            val trackCenterMs = (pts.first().t + pts.last().t) / 2
            val dynamicRim = nearestRim(segmentRimTrack, trackCenterMs)
                ?.takeIf { abs(it.tMs - trackCenterMs) <= MAX_DYNAMIC_RIM_AGE_MS }
            val dynamicRimTrusted = dynamicRim != null && dynamicRim.confidence >= MIN_DYNAMIC_RIM_CONFIDENCE
            val fallbackTrackRim = if (allowRimRefinement) {
                adaptRimFromLocalCrossings(
                    tracklets, segment, trackCenterMs, engineRim, frameH,
                )
            } else {
                engineRim
            }
            val trackRim = when {
                dynamicRimTrusted -> dynamicRim!!.rim
                else -> fallbackTrackRim
            }
            val peakY = pts.minOf { it.y }
            val apex = pts.indices.minByOrNull { pts[it].y } ?: continue
            val apexRim = rimAtTime(segmentRimTrack, pts[apex].t)
            val peakReferenceCy = apexRim?.cy ?: roughRimCy
            val peakReferenceW = apexRim?.w ?: rim.w
            if (peakY > peakReferenceCy + 1.4f * peakReferenceW) continue
            if (dynamicRimTrusted) {
                val start = pts.first()
                val startRim = rimAtTime(segmentRimTrack, start.t)
                val startedInFlight = startRim != null &&
                    start.y < startRim.cy - startRim.h * 4f
                val roseIntoArc = peakY <= start.y - MIN_SHOT_RISE
                if (!startedInFlight && !roseIntoArc) continue
            }

            val arc = pts.subList(0, min(pts.size, apex + 6))
            if (arc.size < minTrackPoints) continue
            val fit = fitQuad(arc)
            if (fit == null) {
                lowResPostRimEvent(
                    pts, trackRim, homography, lowResFallbackMade, dynamicRimTrusted,
                    segmentRimTrack, frameW, frameH,
                )?.let(events::add)
                continue
            }
            val a = fit[0]
            val b = fit[1]
            val c = fit[2]
            if (a <= 0.0) {
                lowResPostRimEvent(
                    pts, trackRim, homography, lowResFallbackMade, dynamicRimTrusted,
                    segmentRimTrack, frameW, frameH,
                )?.let(events::add)
                continue
            }
            val first = pts.first()
            // 实测下落穿筐可以不依赖抛物线外推；只有没有实测交点时才计算预测。
            val observedCrossing = observedRimCrossing(
                pts,
                apex,
                fallbackTrackRim,
                segmentRimTrack,
            )
            val dynamicImpact = if (dynamicRimTrusted) {
                localDynamicImpact(pts, apex, segmentRimTrack, frameW, frameH)
            } else {
                null
            }
            if (dynamicRimTrusted &&
                isOutboundReturnTrack(pts, apex, segmentRimTrack, frameW, frameH)
            ) {
                continue
            }
            var predictedMade = false
            var predictedResolveMs = 0L
            if (observedCrossing == null ||
                (refinedRim == null && !dynamicRimTrusted) ||
                (dynamicRimTrusted && observedCrossing?.crossedPlane == false)
            ) {
                val apexTime = -b / (2 * a)
                val predictionStartMs = first.t + (apexTime.coerceAtLeast(0.0) * 1000).toLong()
                val predictionEndMs = first.t + (MAX_PREDICTED_HIT_SECONDS * 1000).toLong()
                val hasPredictionRimCoverage = segmentRimTrack.any { point ->
                    point.tMs + MAX_DYNAMIC_RIM_AGE_MS >= predictionStartMs &&
                        point.tMs - MAX_DYNAMIC_RIM_AGE_MS <= predictionEndMs
                }
                val tHit = predictedHitTime(
                    firstMs = first.t,
                    a = a,
                    b = b,
                    c = c,
                    apexTime = apexTime,
                    fallbackCy = fallbackTrackRim.cy,
                    rimTrack = segmentRimTrack,
                    descendingOnly = refinedRim != null || hasPredictionRimCoverage,
                )
                if (tHit == null) {
                    lowResPostRimEvent(
                        pts, trackRim, homography, lowResFallbackMade, dynamicRimTrusted,
                        segmentRimTrack, frameW, frameH,
                    )?.let(events::add)
                    continue
                }
                if (tHit > 3.5) continue

                var st = 0.0; var sx = 0.0; var stt = 0.0; var stx = 0.0
                for (p in arc) {
                    val dt = (p.t - first.t) / 1000.0
                    st += dt; sx += p.x; stt += dt * dt; stx += dt * p.x
                }
                val n = arc.size.toDouble()
                val den = n * stt - st * st
                if (abs(den) < 1e-12) continue
                val kx = (n * stx - st * sx) / den
                val bx = (sx - kx * st) / n
                val xHit = kx * tHit + bx
                // 低分辨率且未形成多轨迹校正时，球心横向是一个球径级别的噪声；
                // 0.5 筐宽会把擦筐入网误判成不中。放宽到 0.75，但仍远小于 2 筐宽级伪轨迹。
                val predictionTolerance = when {
                    refinedRim != null || dynamicRimTrusted -> 0.55f
                    relaxedRoughRimTolerance -> 0.75f
                    else -> 0.50f
                }
                predictedResolveMs = first.t + (tHit * 1000).toLong()
                val hitRim = rimAtTime(segmentRimTrack, predictedResolveMs) ?: fallbackTrackRim
                predictedMade = abs(xHit - hitRim.cx) <= predictionTolerance * hitRim.w
            }

            val releaseRim = rimAtTime(segmentRimTrack, first.t) ?: trackRim
            val courtPoint = homography?.toCourt(first.x, first.y)
            val distM = courtPoint?.let { hypot(it.first.toDouble(), it.second.toDouble()).toFloat() } ?: 0f
            val rimRatio = hypot(
                (first.x - releaseRim.cx).toDouble() * frameW,
                (first.y - releaseRim.cy).toDouble() * frameH,
            ).toFloat() / (releaseRim.w * frameW)
            // 37px 级筐框的横向误差会超过筐宽；已校正且进入高弧轨迹的出手不再判成假 miss。
            val observedCanConfirm = observedCrossing?.made == true &&
                span < 1.15f &&
                abs(observedCrossing.x - trackRim.cx) <=
                (if (relaxedRoughRimTolerance) 0.75f else 0.52f) * trackRim.w
            val made = when {
                dynamicRimTrusted -> {
                    when {
                        observedCrossing != null -> observedCrossing.crossedPlane && observedCrossing.made
                        dynamicImpact != null -> dynamicImpact.made
                        else -> predictedMade
                    }
                }
                lowResFallbackMade -> true
                refinedRim != null -> observedCrossing?.made ?: predictedMade
                // 30~60px 的粗筐框无法稳定给出球心横向位置；此时完整抛物线
                // 的外推交点比被篮网/球员短暂遮挡的单帧观测更可靠。
                relaxedRoughRimTolerance -> predictedMade || observedCanConfirm
                observedCrossing?.made == false -> false
                else -> predictedMade || observedCanConfirm
            }
            val releaseMs = first.t
            // 命中时间跟证据源保持一致：有真实穿筐交点时优先采用实测时间，
            // 只有缺少穿筐观测或框后数据不足时才退回抛物线预测。
            val predictionDeterminesResolution =
                predictedMade && (observedCrossing == null || !observedCrossing.crossedPlane)
            val dynamicImpactDeterminesResolution =
                dynamicRimTrusted &&
                    observedCrossing?.made != true &&
                    dynamicImpact?.made == true
            val resolveMs = when {
                dynamicImpactDeterminesResolution -> dynamicImpact!!.timeMs
                predictionDeterminesResolution -> predictedResolveMs
                else -> observedCrossing?.timeMs ?: predictedResolveMs
            }
            events.add(
                Event(
                    releaseMs = releaseMs,
                    resolveMs = resolveMs,
                    made = made,
                    x = first.x,
                    y = first.y,
                    distM = distM,
                    type = homography?.shotType(first.x, first.y)
                        ?: ShotType.estimateFromRimRatio(rimRatio),
                    segment = segment,
                )
            )
        }

        // 去重：只合并同一镜头段内的重复轨迹，集锦切换后即使时间很近也必须保留。
        events.sortBy { it.releaseMs }
        val uniq = ArrayList<Event>()
        for (e in events) {
            val last = uniq.lastOrNull()
            if (last != null && e.segment == last.segment && e.releaseMs - last.releaseMs < 1050) {
                if (e.made && !last.made) uniq[uniq.size - 1] = e
                continue
            }
            uniq.add(e)
        }
        val dynamicOutput = trustedRimTrack.lastOrNull()
        val outputRim = dynamicOutput?.rim ?: refinedRim?.rim ?: rim
        val outputAdjustment = if (dynamicOutput != null) {
            dynamicRimShiftPx(rim, listOf(dynamicOutput), frameW, frameH)
        } else {
            refinedRim?.shiftPx ?: 0
        }
        return Result(
            events = uniq,
            rim = outputRim,
            rimAdjustedPx = outputAdjustment,
        )
    }

    private fun nearestRim(track: List<RimTrackPoint>, timeMs: Long): RimTrackPoint? =
        track.minByOrNull { abs(it.tMs - timeMs) }

    private fun dynamicRimShiftPx(
        rough: NRect,
        track: List<RimTrackPoint>,
        frameW: Int,
        frameH: Int,
    ): Int {
        val final = track.lastOrNull()?.rim ?: return 0
        val dx = (final.cx - rough.cx) * frameW
        val dy = (final.cy - rough.cy) * frameH
        return hypot(dx.toDouble(), dy.toDouble()).roundToInt()
    }

    private fun lowResPostRimEvent(
        points: List<Obs>,
        rim: NRect,
        homography: CourtHomography?,
        enabled: Boolean,
        requireRimEvidence: Boolean,
        rimTrack: List<RimTrackPoint>,
        frameW: Int,
        frameH: Int,
    ): Event? {
        if (!enabled || points.isEmpty()) return null
        val first = points.first()
        val last = points.last()
        val evidenceRim = if (requireRimEvidence) {
            rimAtTime(rimTrack, last.t) ?: rim
        } else {
            rim
        }
        if (requireRimEvidence && abs(last.x - evidenceRim.cx) > 0.75f * evidenceRim.w) return null
        if (first.y < rim.cy || last.y - first.y < 0.03f) return null
        val courtPoint = homography?.toCourt(first.x, first.y)
        val distM = courtPoint?.let { hypot(it.first.toDouble(), it.second.toDouble()).toFloat() } ?: 0f
        val releaseRim = rimAtTime(rimTrack, first.t) ?: rim
        val rimRatio = hypot(
            (first.x - releaseRim.cx).toDouble() * frameW,
            (first.y - releaseRim.cy).toDouble() * frameH,
        ).toFloat() / (releaseRim.w * frameW)
        return Event(
            releaseMs = first.t,
            resolveMs = last.t,
            made = true,
            x = first.x,
            y = first.y,
            distM = distM,
            type = homography?.shotType(first.x, first.y)
                ?: ShotType.estimateFromRimRatio(rimRatio),
            segment = first.segment,
        )
    }

    private data class RimRefinement(val rim: NRect, val shiftPx: Int)

    private data class RimTrack(val points: List<Obs>, val apex: Int)

    /**
     * 用多条下落轨迹的共同穿点校正机位记忆里的粗筐框。
     * 真实投篮会在同一高度密集穿过筐口；静止支架、篮网和橙色反光不会形成这种交集。
     */
    private fun refineRimFromTrajectories(
        tracklets: List<Tr>,
        rough: NRect,
        frameW: Int,
        frameH: Int,
    ): RimRefinement? {
        val candidates = tracklets.mapNotNull { track ->
            val points = track.pts
            if (points.size < 5) return@mapNotNull null
            if ((points.last().t - points.first().t) / 1000f < 0.30f) return@mapNotNull null
            val apex = points.indices.minByOrNull { points[it].y } ?: return@mapNotNull null
            if (points[apex].y >= rough.cy) return@mapNotNull null
            RimTrack(points, apex)
        }
        if (candidates.size < MIN_RIM_REFINE_TRACKS) return null

        val nearMinY = (rough.cy - 2f * rough.w).coerceAtLeast(0f)
        val minY = (rough.cy - max(6f * rough.w, 0.30f)).coerceAtLeast(0f)
        val maxY = (rough.cy + 0.5f * rough.w).coerceAtMost(1f)
        val step = 1f / frameH.toFloat()
        val clusterWidth = rough.w * 1.2f
        val minSupport = max(MIN_RIM_REFINE_TRACKS, (candidates.size * 0.45f).roundToInt())

        fun densestCluster(crossings: List<Float>): Pair<Int, Float> {
            if (crossings.isEmpty()) return 0 to 0f
            val sorted = crossings.sorted()
            var start = 0
            var support = 0
            var center = 0f
            for (end in sorted.indices) {
                while (sorted[end] - sorted[start] > clusterWidth) start++
                val count = end - start + 1
                if (count > support) {
                    support = count
                    center = (sorted[start] + sorted[end]) / 2f
                }
            }
            return support to center
        }

        var bestY = 0f
        var bestX = 0f
        var bestSupport = 0
        var y = minY
        while (y <= maxY) {
            val crossings = candidates.mapNotNull { downwardCrossAt(it, y) }
            val (support, center) = densestCluster(crossings)
            if (support > bestSupport) {
                bestSupport = support
                bestY = y
                bestX = center
            }
            y += step
        }
        if (bestSupport < minSupport) return null

        val (roughSupport, roughCenter) = densestCluster(
            candidates.mapNotNull { downwardCrossAt(it, rough.cy) },
        )
        val grossUpwardShift = bestY < nearMinY - step * 2f
        if (grossUpwardShift && roughSupport > 1) return null
        if (bestY <= minY + step * 2f || bestY >= maxY - step * 2f) return null

        val bestCenterError = abs(bestX - rough.cx) / rough.w
        val roughCenterError = abs(roughCenter - rough.cx) / rough.w
        val supportImproved = bestSupport >= roughSupport + 3
        val centerImproved = bestSupport >= roughSupport && roughCenterError - bestCenterError >= 0.5f
        if (!supportImproved && !centerImproved) return null

        val shiftX = (bestX - rough.cx) * frameW
        val shiftY = (bestY - rough.cy) * frameH
        val shiftPx = hypot(shiftX.toDouble(), shiftY.toDouble()).roundToInt()
        val minShiftPx = max(4f, rough.w * frameW * 0.35f)
        if (shiftPx < minShiftPx) return null

        return RimRefinement(
            rim = NRect(
                (bestX - rough.w / 2f).coerceIn(0f, 1f),
                (bestY - rough.h / 2f).coerceIn(0f, 1f),
                (bestX + rough.w / 2f).coerceIn(0f, 1f),
                (bestY + rough.h / 2f).coerceIn(0f, 1f),
            ),
            shiftPx = shiftPx,
        )
    }

    /**
     * 同一机位段的真实下落点会形成稳定的二维簇。
     * 这用于处理集锦里裁切或变焦导致的篮筐位置变化。
     */
    private fun adaptRimFromLocalCrossings(
        tracklets: List<Tr>,
        segment: Int,
        centerMs: Long,
        base: NRect,
        frameH: Int,
    ): NRect {
        val localTracks = tracklets.filter { track ->
            val points = track.pts
            if (points.first().segment != segment) return@filter false
            if (points.size < 5) return@filter false
            if ((points.last().t - points.first().t) / 1000f < 0.30f) return@filter false
            val trackCenter = (points.first().t + points.last().t) / 2
            abs(trackCenter - centerMs) <= LOCAL_RIM_WINDOW_MS
        }
        if (localTracks.size < 3) return base

        val minY = (base.cy - 1.5f * base.w).coerceAtLeast(0f)
        val maxY = (base.cy + 1.5f * base.w).coerceAtMost(1f)
        val step = 1f / frameH.toFloat()
        val clusterWidth = base.w * 1.2f
        var support = 0
        var centerX = base.cx
        var centerY = base.cy
        var y = minY
        while (y <= maxY) {
            val crossings = localTracks.mapNotNull { track ->
                val points = track.pts
                val apex = points.indices.minByOrNull { points[it].y } ?: return@mapNotNull null
                if (points[apex].y >= y) return@mapNotNull null
                downwardCrossAt(RimTrack(points, apex), y)
            }.sorted()
            var start = 0
            for (end in crossings.indices) {
                while (crossings[end] - crossings[start] > clusterWidth) start++
                val count = end - start + 1
                val candidateX = (crossings[start] + crossings[end]) / 2f
                val score = count - abs(y - base.cy) / base.w * 0.02f
                val currentScore = support - abs(centerY - base.cy) / base.w * 0.02f
                if (score > currentScore) {
                    support = count
                    centerX = candidateX
                    centerY = y
                }
            }
            y += step
        }
        if (support < 3) return base
        val shiftX = abs(centerX - base.cx)
        val shiftY = abs(centerY - base.cy)
        if (shiftX < base.w * 0.25f && shiftY < base.w * 0.25f) return base

        return NRect(
            (centerX - base.w / 2f).coerceIn(0f, 1f),
            (centerY - base.h / 2f).coerceIn(0f, 1f),
            (centerX + base.w / 2f).coerceIn(0f, 1f),
            (centerY + base.h / 2f).coerceIn(0f, 1f),
        )
    }

    private fun downwardCrossAt(track: RimTrack, y: Float): Float? {
        val points = track.points
        for (index in (track.apex + 1).coerceAtLeast(1) until points.size) {
            val previous = points[index - 1]
            val current = points[index]
            if (current.t - previous.t > MAX_OBSERVED_CROSS_GAP_MS) continue
            if (previous.y >= y || current.y < y) continue
            val span = current.y - previous.y
            if (abs(span) < 1e-6f) continue
            val fraction = ((y - previous.y) / span).coerceIn(0f, 1f)
            return previous.x + (current.x - previous.x) * fraction
        }
        return null
    }

    private fun nearestPerson(o: Obs, fr: FrameObs): NRect? {
        val pool = if (fr.persons.isNotEmpty()) fr.persons else listOfNotNull(fr.person)
        if (pool.isEmpty()) return null
        return pool.minByOrNull { p ->
            val dx = (o.x - (p.left + p.right) / 2f)
            val dy = (o.y - (p.top + p.bottom) / 2f)
            dx * dx + dy * dy
        }
    }

    /**
     * 从最高点之后的真实检测点复核是否向下穿过筐平面。
     * 返回 null 表示框后数据不足，保留抛物线预测；false 表示已经看到球从筐外下落或弹开。
     */
    private data class RimCrossing(
        val made: Boolean,
        val timeMs: Long,
        val x: Float,
        val crossedPlane: Boolean,
    )

    private data class DynamicImpact(
        val made: Boolean,
        val timeMs: Long,
        val x: Float,
    )

    /**
     * 回球网会把已入筐的球沿反方向送回球员。取最高点之后的第一个局部最近点，
     * 用进入回球网前的最后三段速度外推到筐面，避免后续回程被当成第二次穿筐。
     */
    private fun localDynamicImpact(
        pts: List<Obs>,
        apex: Int,
        rimTrack: List<RimTrackPoint>,
        frameW: Int,
        frameH: Int,
    ): DynamicImpact? {
        if (rimTrack.isEmpty() || apex >= pts.lastIndex) return null
        val start = pts.first()
        val startRim = rimAtTime(rimTrack, start.t) ?: return null
        val peakY = pts[apex].y
        val startedInFlight = start.y < startRim.cy - startRim.h * 4f
        if (!startedInFlight && peakY >= start.y - MIN_SHOT_RISE) return null

        val distances = ArrayList<Float>()
        val pointIndices = ArrayList<Int>()
        for (index in apex until pts.size) {
            val point = pts[index]
            if (index > apex && point.t - pts[index - 1].t > MAX_OBSERVED_CROSS_GAP_MS) break
            if (point.t - pts[apex].t > MAX_IMPACT_APPROACH_MS) break
            val rim = rimAtTime(rimTrack, point.t) ?: break
            distances.add(rimDistancePx(point, rim, frameW, frameH))
            pointIndices.add(index)
        }
        if (distances.size < 4) return null

        val minGain = max(8f, startRim.w * frameW * 0.20f)
        var localMin = -1
        for (index in 1 until distances.lastIndex) {
            if (distances[index] <= distances[index - 1] &&
                distances[index] < distances[index + 1] &&
                distances.first() - distances[index] >= minGain
            ) {
                localMin = index
                break
            }
        }
        if (localMin < 0) return null

        val endIndex = pointIndices[localMin]
        val firstFit = max(apex, endIndex - 2)
        val fitPoints = pts.subList(firstFit, endIndex + 1)
        if (fitPoints.size < 3) return null
        val meanT = fitPoints.map { it.t / 1000.0 }.average()
        val meanX = fitPoints.map { it.x.toDouble() }.average()
        val meanY = fitPoints.map { it.y.toDouble() }.average()
        var denominator = 0.0
        var tx = 0.0
        var ty = 0.0
        for (point in fitPoints) {
            val dt = point.t / 1000.0 - meanT
            denominator += dt * dt
            tx += dt * (point.x - meanX)
            ty += dt * (point.y - meanY)
        }
        if (denominator < 1e-9) return null
        val vx = tx / denominator
        val vy = ty / denominator
        if (vy <= 0.02) return null
        val interceptX = meanX - vx * meanT
        val interceptY = meanY - vy * meanT

        val last = pts[endIndex]
        var previousY = vy * (last.t / 1000.0) + interceptY
        var previousRimY = (rimAtTime(rimTrack, last.t) ?: return null).cy
        for (step in 1..MAX_IMPACT_EXTRAPOLATION_STEPS) {
            val timeMs = last.t + step * IMPACT_STEP_MS
            val rim = rimAtTime(rimTrack, timeMs) ?: break
            val timeSeconds = timeMs / 1000.0
            val y = vy * timeSeconds + interceptY
            if (previousY <= previousRimY && y >= rim.cy) {
                val x = (vx * timeSeconds + interceptX).toFloat()
                val errorWidths = abs(x - rim.cx) / rim.w.coerceAtLeast(1e-4f)
                return DynamicImpact(
                    made = errorWidths <= MAX_LOCAL_IMPACT_ERROR_WIDTHS,
                    timeMs = timeMs,
                    x = x,
                )
            }
            previousY = y
            previousRimY = rim.cy
        }
        return null
    }

    private fun isOutboundReturnTrack(
        pts: List<Obs>,
        apex: Int,
        rimTrack: List<RimTrackPoint>,
        frameW: Int,
        frameH: Int,
    ): Boolean {
        val start = pts.first()
        val startRim = rimAtTime(rimTrack, start.t) ?: return false
        val startDistance = rimDistancePx(start, startRim, frameW, frameH)
        val closeToRim = startRim.w * frameW * OUTBOUND_START_RIM_WIDTHS
        if (startDistance > closeToRim) return false
        val end = pts.last()
        val endRim = rimAtTime(rimTrack, end.t) ?: return false
        val endDistance = rimDistancePx(end, endRim, frameW, frameH)
        var minDistance = startDistance
        for (index in apex until pts.size) {
            val rim = rimAtTime(rimTrack, pts[index].t) ?: continue
            minDistance = minOf(minDistance, rimDistancePx(pts[index], rim, frameW, frameH))
        }
        return minDistance >= startDistance * 0.90f && endDistance > startDistance * 1.15f
    }

    private fun rimDistancePx(point: Obs, rim: NRect, frameW: Int, frameH: Int): Float =
        hypot((point.x - rim.cx).toDouble() * frameW, (point.y - rim.cy).toDouble() * frameH)
            .toFloat()

    private fun observedRimCrossing(
        pts: List<Obs>,
        apex: Int,
        fallbackRim: NRect,
        rimTrack: List<RimTrackPoint>,
    ): RimCrossing? {
        var firstOutside: RimCrossing? = null
        for (i in (apex + 1).coerceAtLeast(1) until pts.size) {
            val previous = pts[i - 1]
            val current = pts[i]
            if (current.t - previous.t > MAX_OBSERVED_CROSS_GAP_MS) continue
            val span = (current.y - previous.y).toDouble()
            val boundaries = ArrayList<Long>()
            boundaries.add(previous.t)
            var rimIndex = lowerBoundTime(rimTrack, previous.t - MAX_DYNAMIC_RIM_AGE_MS)
            while (rimIndex < rimTrack.size &&
                rimTrack[rimIndex].tMs - MAX_DYNAMIC_RIM_AGE_MS < current.t
            ) {
                val point = rimTrack[rimIndex]
                for (boundary in longArrayOf(
                    point.tMs - MAX_DYNAMIC_RIM_AGE_MS,
                    point.tMs,
                    point.tMs + MAX_DYNAMIC_RIM_AGE_MS,
                )) {
                    if (boundary > previous.t && boundary < current.t) boundaries.add(boundary)
                }
                val next = rimTrack.getOrNull(rimIndex + 1)
                if (next != null && next.tMs - point.tMs > MAX_RIM_INTERPOLATION_GAP_MS) {
                    val switch = point.tMs + (next.tMs - point.tMs) / 2L
                    if (switch > previous.t && switch < current.t) boundaries.add(switch)
                }
                rimIndex++
            }
            boundaries.add(current.t)
            boundaries.sort()

            for (index in 0 until boundaries.lastIndex) {
                val t0 = boundaries[index]
                val t1 = boundaries[index + 1]
                if (t1 <= t0) continue
                val midRim = rimAtTime(rimTrack, t0 + (t1 - t0) / 2L)
                val rim0 = if (midRim == null) fallbackRim else rimAtTime(rimTrack, t0) ?: midRim
                val rim1 = if (midRim == null) fallbackRim else rimAtTime(rimTrack, t1) ?: midRim
                val ball0 = previous.y + (current.y - previous.y) *
                    ((t0 - previous.t).toFloat() / (current.t - previous.t).coerceAtLeast(1L))
                val ball1 = previous.y + (current.y - previous.y) *
                    ((t1 - previous.t).toFloat() / (current.t - previous.t).coerceAtLeast(1L))
                val d0 = ball0 - rim0.cy
                val d1 = ball1 - rim1.cy
                if (d0 > 1e-6 || d1 < -1e-6 || d1 - d0 <= 1e-6) continue
                val localFraction = if (abs(d1 - d0) < 1e-6) 0f else (-d0 / (d1 - d0)).coerceIn(0f, 1f)
                val crossTime = t0 + ((t1 - t0) * localFraction).toLong()
                val ballFraction = ((crossTime - previous.t).toFloat() /
                    (current.t - previous.t).coerceAtLeast(1L)).coerceIn(0f, 1f)
                val crossX = previous.x + (current.x - previous.x) * ballFraction
                val crossRim = rimAtTime(rimTrack, crossTime) ?: fallbackRim
                val crossing = RimCrossing(
                    made = abs(crossX - crossRim.cx) <= 0.55f * crossRim.w,
                    timeMs = crossTime,
                    x = crossX,
                    crossedPlane = true,
                )
                if (crossing.made) return crossing
                if (firstOutside == null) firstOutside = crossing
            }
        }
        if (firstOutside != null) return firstOutside

        val belowRim = pts.subList((apex + 1).coerceAtMost(pts.size), pts.size)
            .firstOrNull { point ->
                val rim = rimAtTime(rimTrack, point.t) ?: fallbackRim
                point.y >= rim.bottom && point.t - pts[apex].t <= MAX_OBSERVED_CROSS_GAP_MS * 2L
            }
            ?: return null
        val belowGeometry = rimAtTime(rimTrack, belowRim.t) ?: fallbackRim
        return RimCrossing(
            made = abs(belowRim.x - belowGeometry.cx) <= 0.55f * belowGeometry.w,
            timeMs = belowRim.t,
            x = belowRim.x,
            crossedPlane = false,
        )
    }

    private fun rimAtTime(track: List<RimTrackPoint>, timeMs: Long): NRect? {
        if (track.isEmpty()) return null
        var low = 0
        var high = track.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (track[mid].tMs < timeMs) low = mid + 1 else high = mid
        }
        val after = track.getOrNull(low)
        val before = track.getOrNull(low - 1)
        val nearestAge = minOf(
            before?.let { abs(timeMs - it.tMs) } ?: Long.MAX_VALUE,
            after?.let { abs(it.tMs - timeMs) } ?: Long.MAX_VALUE,
        )
        if (nearestAge > MAX_DYNAMIC_RIM_AGE_MS) return null
        if (before == null) return after?.rim
        if (after == null || after.tMs == before.tMs) return before.rim
        if (after.tMs - before.tMs > MAX_RIM_INTERPOLATION_GAP_MS) {
            return if (abs(timeMs - before.tMs) <= abs(after.tMs - timeMs)) before.rim else after.rim
        }
        val span = (after.tMs - before.tMs).toFloat().coerceAtLeast(1f)
        val fraction = ((timeMs - before.tMs) / span).coerceIn(0f, 1f)
        val a = before.rim
        val b = after.rim
        return NRect(
            a.left + (b.left - a.left) * fraction,
            a.top + (b.top - a.top) * fraction,
            a.right + (b.right - a.right) * fraction,
            a.bottom + (b.bottom - a.bottom) * fraction,
        )
    }

    private fun lowerBoundTime(track: List<RimTrackPoint>, timeMs: Long): Int {
        var low = 0
        var high = track.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (track[mid].tMs < timeMs) low = mid + 1 else high = mid
        }
        return low
    }

    private fun predictedHitTime(
        firstMs: Long,
        a: Double,
        b: Double,
        c: Double,
        apexTime: Double,
        fallbackCy: Float,
        rimTrack: List<RimTrackPoint>,
        descendingOnly: Boolean,
    ): Double? {
        if (rimTrack.isEmpty()) {
            val discriminant = b * b - 4 * a * (c - fallbackCy)
            if (discriminant < 0) return null
            val root = sqrt(discriminant)
            val roots = doubleArrayOf((-b + root) / (2 * a), (-b - root) / (2 * a))
            return if (descendingOnly) {
                roots.filter { it > 0.0 && it >= apexTime }.minOrNull()
            } else {
                roots.filter { it > 0.0 }.minOrNull()
            }
        }

        val start = apexTime.coerceAtLeast(0.0)
        val end = MAX_PREDICTED_HIT_SECONDS
        val boundaries = ArrayList<Double>()
        boundaries.add(start)
        rimTrack.forEach { point ->
            val local = (point.tMs - firstMs) / 1000.0
            if (local > start && local < end) boundaries.add(local)
            val availableStart = (point.tMs - MAX_DYNAMIC_RIM_AGE_MS - firstMs) / 1000.0
            val availableEnd = (point.tMs + MAX_DYNAMIC_RIM_AGE_MS - firstMs) / 1000.0
            if (availableStart > start && availableStart < end) boundaries.add(availableStart)
            if (availableEnd > start && availableEnd < end) boundaries.add(availableEnd)
        }
        for (index in 0 until rimTrack.lastIndex) {
            val first = rimTrack[index]
            val second = rimTrack[index + 1]
            if (second.tMs - first.tMs > MAX_RIM_INTERPOLATION_GAP_MS) {
                val switch = (first.tMs + (second.tMs - first.tMs) / 2L - firstMs) / 1000.0
                if (switch > start && switch < end) boundaries.add(switch)
            }
        }
        boundaries.add(end)
        val knots = boundaries.distinct().sorted()

        var best: Double? = null
        for (index in 0 until knots.lastIndex) {
            val t0 = knots[index]
            val t1 = knots[index + 1]
            if (t1 <= t0) continue
            val midMs = firstMs + ((t0 + t1) * 500.0).toLong()
            val midRim = rimAtTime(rimTrack, midMs)
            val rim0Cy = if (midRim == null) {
                fallbackCy
            } else {
                rimAtTime(rimTrack, firstMs + (t0 * 1000).toLong())?.cy ?: midRim.cy
            }
            val rim1Cy = if (midRim == null) {
                fallbackCy
            } else {
                rimAtTime(rimTrack, firstMs + (t1 * 1000).toLong())?.cy ?: midRim.cy
            }
            val rimSlope = ((rim1Cy - rim0Cy) / (t1 - t0).toFloat()).toDouble()
            val rimIntercept = (rim0Cy - rimSlope * t0).toDouble()
            val qa = a
            val qb = b - rimSlope
            val qc = c - rimIntercept
            val roots = when {
                abs(qa) < 1e-12 -> {
                    if (abs(qb) < 1e-12) continue
                    doubleArrayOf(-qc / qb)
                }
                else -> {
                    val disc = qb * qb - 4.0 * qa * qc
                    if (disc < 0.0) continue
                    val sq = sqrt(disc)
                    doubleArrayOf((-qb + sq) / (2.0 * qa), (-qb - sq) / (2.0 * qa))
                }
            }
            val candidate = roots
                .filter {
                    val derivative = 2.0 * qa * it + qb
                    it >= t0 && it <= t1 && it > 0.0 &&
                        (!descendingOnly || (it >= apexTime && derivative > 1e-9))
                }
                .minOrNull()
                ?: continue
            if (best == null || candidate < best!!) best = candidate
        }
        return best
    }

    /** y(t) 二次拟合，返回 (a,b,c)；时间原点固定为轨迹首点。 */
    private fun fitQuad(pts: List<Obs>): DoubleArray? {
        val t0 = pts.first().t / 1000.0
        var S0 = 0.0; var S1 = 0.0; var S2 = 0.0; var S3 = 0.0; var S4 = 0.0
        var Sy0 = 0.0; var Sy1 = 0.0; var Sy2 = 0.0
        for (p in pts) {
            val dt = p.t / 1000.0 - t0
            var pw = 1.0
            S0 += pw; pw *= dt
            S1 += pw; pw *= dt
            S2 += pw; pw *= dt
            S3 += pw; pw *= dt
            S4 += pw
            Sy0 += p.y
            Sy1 += dt * p.y
            Sy2 += dt * dt * p.y
        }
        val m = arrayOf(
            doubleArrayOf(S4, S3, S2, Sy2),
            doubleArrayOf(S3, S2, S1, Sy1),
            doubleArrayOf(S2, S1, S0, Sy0),
        )
        for (col in 0..2) {
            var piv = col
            for (r in col + 1..2) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-12) return null
            if (piv != col) { val tmp = m[piv]; m[piv] = m[col]; m[col] = tmp }
            val d = m[col][col]
            for (k in col..3) m[col][k] /= d
            for (r in 0..2) {
                if (r == col) continue
                val f = m[r][col]
                for (k in col..3) m[r][k] -= f * m[col][k]
            }
        }
        return doubleArrayOf(m[0][3], m[1][3], m[2][3])
    }

    private const val TRACK_GATE = 0.08f
    private const val TRACK_GATE_PER_SECOND = 0.12f
    private const val MAX_TRACK_GATE = 0.20f
    private const val MAX_TRACK_GAP_SECONDS = 0.60f
    private const val MAX_MISSED_FRAMES = 2
    private const val MIN_RIM_REFINE_TRACKS = 4
    private const val MIN_RELIABLE_RIM_PX = 60f
    private const val MIN_ADAPTIVE_ROUGH_RIM_PX = 30f
    private const val LOW_RES_TOLERANCE_MAX_FRAME_W = 720
    private const val MIN_DYNAMIC_RIM_CONFIDENCE = 0.28f
    private const val MAX_DYNAMIC_RIM_AGE_MS = 1_000L
    private const val MAX_RIM_INTERPOLATION_GAP_MS = 1_000L
    private const val MAX_PREDICTED_HIT_SECONDS = 3.5
    private const val LOCAL_RIM_WINDOW_MS = 8_000L
    private const val MAX_OBSERVED_CROSS_GAP_MS = 800L
    private const val MAX_IMPACT_APPROACH_MS = 1_400L
    private const val MAX_IMPACT_EXTRAPOLATION_STEPS = 30
    private const val IMPACT_STEP_MS = 20L
    private const val MAX_LOCAL_IMPACT_ERROR_WIDTHS = 0.65f
    private const val OUTBOUND_START_RIM_WIDTHS = 4.0f
    private const val MIN_SHOT_RISE = 0.03f
}
