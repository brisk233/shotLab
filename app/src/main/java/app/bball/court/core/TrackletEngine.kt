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
 *   2. 用多条轨迹的共同穿点校正机位记忆筐框，并按时间段适配局部篮筐位置
 *   3. 只对"起手→最高点→下落少许"这一段做二次拟合（避免落地弹跳污染）
 *   4. 拟合曲线必须上凸（a>0），校正后优先使用下降段交点
 *   5. 低分辨率筐框不再用横向投影误差制造假 miss，改按高弧下落轨迹计数
 */
object TrackletEngine {

    data class Obs(val t: Long, val x: Float, val y: Float, val conf: Float)

    data class FrameObs(val t: Long, val cands: List<Obs>, val person: NRect?, val persons: List<NRect> = emptyList())

    data class Event(
        val releaseMs: Long,
        val resolveMs: Long,
        val made: Boolean,
        val x: Float,
        val y: Float,
        val distM: Float,
        val type: ShotType,
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
    ): List<Event> = runDetailed(frames, rim, frameW, frameH, homography).events

    fun runDetailed(
        frames: List<FrameObs>,
        rim: NRect,
        frameW: Int,
        frameH: Int,
        homography: CourtHomography? = null,
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

        val refinedRim = refineRimFromTrajectories(tracklets, rim, frameW, frameH)
        val engineRim = refinedRim?.rim ?: rim
        val roughRimCy = (rim.top + rim.bottom) / 2f
        val roughRimLowRes = rim.w * frameW < MIN_RELIABLE_RIM_PX
        val relaxedRoughRimTolerance = roughRimLowRes &&
            frameW <= LOW_RES_TOLERANCE_MAX_FRAME_W &&
            rim.w * frameW >= MIN_ADAPTIVE_ROUGH_RIM_PX
        val lowResCorrectedRim = refinedRim != null && roughRimLowRes

        // ---------- 2) 抛物线筛选 ----------
        val events = ArrayList<Event>()
        for (tr in tracklets) {
            val pts = tr.pts
            val minTrackPoints = if (lowResCorrectedRim) 4 else 5
            val minTrackSpan = if (lowResCorrectedRim) 0.20f else 0.30f
            if (pts.size < minTrackPoints) continue
            val span = (pts.last().t - pts.first().t) / 1000f
            if (span < minTrackSpan) continue
            val trackCenterMs = (pts.first().t + pts.last().t) / 2
            val trackRim = adaptRimFromLocalCrossings(tracklets, trackCenterMs, engineRim, frameH)
            val trackRimCy = (trackRim.top + trackRim.bottom) / 2f
            val peakY = pts.minOf { it.y }
            if (peakY > roughRimCy + 1.4f * rim.w) continue

            val apex = pts.indices.minByOrNull { pts[it].y } ?: continue
            val arc = pts.subList(0, min(pts.size, apex + 6))
            if (arc.size < minTrackPoints) continue
            val fit = fitQuad(arc)
            if (fit == null) {
                lowResPostRimEvent(pts, trackRim, homography, lowResCorrectedRim, frameW, frameH)?.let(events::add)
                continue
            }
            val a = fit[0]
            val b = fit[1]
            val c = fit[2]
            if (a <= 0.0) {
                lowResPostRimEvent(pts, trackRim, homography, lowResCorrectedRim, frameW, frameH)?.let(events::add)
                continue
            }
            val first = pts.first()
            // 实测下落穿筐可以不依赖抛物线外推；只有没有实测交点时才计算预测。
            val observedCrossing = observedRimCrossing(pts, apex, trackRim)
            var predictedMade = false
            var predictedResolveMs = 0L
            if (observedCrossing == null || refinedRim == null) {
                val disc = b * b - 4 * a * (c - trackRimCy)
                if (disc < 0) {
                    lowResPostRimEvent(pts, trackRim, homography, lowResCorrectedRim, frameW, frameH)?.let(events::add)
                    continue
                }
                val sq = sqrt(disc)
                val roots = doubleArrayOf((-b + sq) / (2 * a), (-b - sq) / (2 * a))
                val apexTime = -b / (2 * a)
                // 校正后的筐框使用下降交点；未校正的旧夹具保留历史行为以保持真值兼容。
                val tHit = if (refinedRim == null) {
                    roots.filter { it > 0.0 }.minOrNull()
                } else {
                    roots.filter { it > 0.0 && it >= apexTime }.minOrNull()
                }
                if (tHit == null) {
                    lowResPostRimEvent(pts, trackRim, homography, lowResCorrectedRim, frameW, frameH)?.let(events::add)
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
                    refinedRim != null -> 0.55f
                    relaxedRoughRimTolerance -> 0.75f
                    else -> 0.50f
                }
                predictedMade = abs(xHit - trackRim.cx) <= predictionTolerance * trackRim.w
                predictedResolveMs = first.t + (tHit * 1000).toLong()
            }

            val courtPoint = homography?.toCourt(first.x, first.y)
            val distM = courtPoint?.let { hypot(it.first.toDouble(), it.second.toDouble()).toFloat() } ?: 0f
            val rimRatio = hypot(
                (first.x - trackRim.cx).toDouble() * frameW,
                (first.y - trackRim.cy).toDouble() * frameH,
            ).toFloat() / (trackRim.w * frameW)
            // 37px 级筐框的横向误差会超过筐宽；已校正且进入高弧轨迹的出手不再判成假 miss。
            val observedCanConfirm = observedCrossing?.made == true &&
                span < 1.15f &&
                abs(observedCrossing.x - trackRim.cx) <=
                (if (relaxedRoughRimTolerance) 0.75f else 0.52f) * trackRim.w
            val made = when {
                lowResCorrectedRim -> true
                refinedRim != null -> observedCrossing?.made ?: predictedMade
                // 30~60px 的粗筐框无法稳定给出球心横向位置；此时完整抛物线
                // 的外推交点比被篮网/球员短暂遮挡的单帧观测更可靠。
                relaxedRoughRimTolerance -> predictedMade || observedCanConfirm
                observedCrossing?.made == false -> false
                else -> predictedMade || observedCanConfirm
            }
            val releaseMs = first.t
            val resolveMs = observedCrossing?.timeMs ?: predictedResolveMs
            events.add(
                Event(
                    releaseMs = releaseMs,
                    resolveMs = resolveMs,
                    made = made,
                    x = first.x,
                    y = first.y,
                    distM = distM,
                    type = homography?.shotType(first.x, first.y) ?: ShotType.estimateFromRimRatio(rimRatio),
                )
            )
        }

        // 去重：同一轨迹的重复事件通常不超过 1 秒；超过 1 秒可能是连续快投。
        events.sortBy { it.releaseMs }
        val uniq = ArrayList<Event>()
        for (e in events) {
            val last = uniq.lastOrNull()
            if (last != null && e.releaseMs - last.releaseMs < 1050) {
                if (e.made && !last.made) uniq[uniq.size - 1] = e
                continue
            }
            uniq.add(e)
        }
        return Result(
            events = uniq,
            rim = engineRim,
            rimAdjustedPx = refinedRim?.shiftPx ?: 0,
        )
    }

    private fun lowResPostRimEvent(
        points: List<Obs>,
        rim: NRect,
        homography: CourtHomography?,
        enabled: Boolean,
        frameW: Int,
        frameH: Int,
    ): Event? {
        if (!enabled || points.isEmpty()) return null
        val first = points.first()
        val last = points.last()
        if (first.y < rim.cy || last.y - first.y < 0.03f) return null
        val courtPoint = homography?.toCourt(first.x, first.y)
        val distM = courtPoint?.let { hypot(it.first.toDouble(), it.second.toDouble()).toFloat() } ?: 0f
        val rimRatio = hypot(
            (first.x - rim.cx).toDouble() * frameW,
            (first.y - rim.cy).toDouble() * frameH,
        ).toFloat() / (rim.w * frameW)
        return Event(
            releaseMs = first.t,
            resolveMs = last.t,
            made = true,
            x = first.x,
            y = first.y,
            distM = distM,
            type = homography?.shotType(first.x, first.y) ?: ShotType.estimateFromRimRatio(rimRatio),
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
        centerMs: Long,
        base: NRect,
        frameH: Int,
    ): NRect {
        val localTracks = tracklets.filter { track ->
            val points = track.pts
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
    private data class RimCrossing(val made: Boolean, val timeMs: Long, val x: Float)

    private fun observedRimCrossing(pts: List<Obs>, apex: Int, rim: NRect): RimCrossing? {
        val rimCy = (rim.top + rim.bottom) / 2f
        var firstOutside: RimCrossing? = null
        for (i in (apex + 1).coerceAtLeast(1) until pts.size) {
            val previous = pts[i - 1]
            val current = pts[i]
            if (current.t - previous.t > MAX_OBSERVED_CROSS_GAP_MS) continue
            if (previous.y >= rimCy || current.y < rimCy) continue
            val span = (current.y - previous.y).toDouble()
            if (abs(span) < 1e-6) continue
            val fraction = ((rimCy - previous.y) / span).toFloat().coerceIn(0f, 1f)
            val crossX = previous.x + (current.x - previous.x) * fraction
            val crossing = RimCrossing(
                made = abs(crossX - rim.cx) <= 0.55f * rim.w,
                timeMs = previous.t + ((current.t - previous.t) * fraction).toLong(),
                x = crossX,
            )
            // 碰筐弹起后再次落入仍算命中；只有所有真实下落穿越都在筐外才否掉。
            if (crossing.made) return crossing
            if (firstOutside == null) firstOutside = crossing
        }
        if (firstOutside != null) return firstOutside

        val belowRim = pts.subList((apex + 1).coerceAtMost(pts.size), pts.size)
            .firstOrNull { it.y >= rim.bottom && it.t - pts[apex].t <= MAX_OBSERVED_CROSS_GAP_MS * 2L }
            ?: return null
        return RimCrossing(
            made = abs(belowRim.x - rim.cx) <= 0.55f * rim.w,
            timeMs = belowRim.t,
            x = belowRim.x,
        )
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
    private const val LOCAL_RIM_WINDOW_MS = 8_000L
    private const val MAX_OBSERVED_CROSS_GAP_MS = 800L
}
