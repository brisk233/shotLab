package app.bball.court.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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
 *   2. 只保留"升到筐口附近"的轨迹（peakY <= rimCy + rimW）
 *   3. 只对"起手→最高点→下落少许"这一段做二次拟合（避免落地弹跳污染）
 *   4. 拟合曲线必须上凸（a>0），预测交点须在 0.55 筐宽内
 *   5. 有筐后真实检测时，必须从筐内向下穿出；碰筐弹出和从边滑出判不中
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

    private data class Tr(val pts: ArrayList<Obs>)

    fun run(frames: List<FrameObs>, rim: NRect, frameW: Int, frameH: Int): List<Event> {
        if (frames.isEmpty() || rim.w <= 1e-4f) return emptyList()
        val rimCy = (rim.top + rim.bottom) / 2f
        val pxPerM = (rim.w * frameW) / 0.4572f

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
                if (dt > 0.35f) continue
                var best: Obs? = null
                var bestD = Float.MAX_VALUE
                for ((ci, c) in fr.cands.withIndex()) {
                    if (ci in used) continue
                    val d = hypot(c.x - last.x, c.y - last.y)
                    if (d < TRACK_GATE && d < bestD) { bestD = d; best = c }
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
                    newActive.add(idx)
                }
            }
            for ((ci, c) in fr.cands.withIndex()) {
                if (ci in used) continue
                tracklets.add(Tr(arrayListOf(c)))
                newActive.add(tracklets.size - 1)
            }
            active = newActive
        }

        // ---------- 2) 抛物线筛选 ----------
        val events = ArrayList<Event>()
        for (tr in tracklets) {
            val pts = tr.pts
            if (pts.size < 5) continue
            val span = (pts.last().t - pts.first().t) / 1000f
            if (span < 0.30f) continue
            val peakY = pts.minOf { it.y }
            if (peakY > rimCy + 1.4f * rim.w) continue

            val apex = pts.indices.minByOrNull { pts[it].y } ?: continue
            val arc = pts.subList(0, min(pts.size, apex + 6))
            if (arc.size < 5) continue
            val fit = fitQuad(arc) ?: continue
            val a = fit[0]
            val b = fit[1]
            val c = fit[2]
            if (a <= 0.0) continue
            val disc = b * b - 4 * a * (c - rimCy)
            if (disc < 0) continue
            val sq = sqrt(disc)
            val roots = doubleArrayOf((-b + sq) / (2 * a), (-b - sq) / (2 * a))
            val tHit = roots.filter { it > 0.0 }.minOrNull() ?: continue
            if (tHit > 3.5) continue

            // x 线性拟合
            var st = 0.0; var sx = 0.0; var stt = 0.0; var stx = 0.0
            for (p in arc) {
                // 以起手时刻为零点；t0 已经是该时刻的秒值，不能再次从相对时间里扣减。
                val dt = (p.t - pts.first().t) / 1000.0
                st += dt; sx += p.x; stt += dt * dt; stx += dt * p.x
            }
            val n = arc.size.toDouble()
            val den = n * stt - st * st
            if (abs(den) < 1e-12) continue
            val kx = (n * stx - st * sx) / den
            val bx = (sx - kx * st) / n
            val xHit = kx * tHit + bx

            val first = pts.first()
            val distM = hypot((first.x - rim.cx) * frameW, (first.y - rimCy) * frameH) / pxPerM
            val predictedMade = abs(xHit - rim.cx) <= 0.55f * rim.w
            // 物理解释优先：有筐后观测时，用真实下落段复核；碰筐弹出会在这里被否掉。
            val observedMade = observedRimCrossing(pts, apex, rim)
            val made = predictedMade && observedMade != false
            val releaseMs = first.t
            val resolveMs = pts.first().t + (tHit * 1000).toLong()
            events.add(
                Event(
                    releaseMs = releaseMs,
                    resolveMs = resolveMs,
                    made = made,
                    x = first.x,
                    y = first.y,
                    distM = distM,
                    type = when {
                        distM < 1.5f -> ShotType.LAYUP
                        distM >= 6.75f -> ShotType.THREE
                        else -> ShotType.TWO
                    },
                )
            )
        }

        // 去重：1.2 秒内只留一个（优先命中）
        events.sortBy { it.releaseMs }
        val uniq = ArrayList<Event>()
        for (e in events) {
            val last = uniq.lastOrNull()
            if (last != null && e.releaseMs - last.releaseMs < 1200) {
                if (e.made && !last.made) uniq[uniq.size - 1] = e
                continue
            }
            uniq.add(e)
        }
        return uniq
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
    private fun observedRimCrossing(pts: List<Obs>, apex: Int, rim: NRect): Boolean? {
        val rimCy = (rim.top + rim.bottom) / 2f
        var sawCrossing = false
        for (i in (apex + 1).coerceAtLeast(1) until pts.size) {
            val previous = pts[i - 1]
            val current = pts[i]
            if (current.t - previous.t > MAX_OBSERVED_CROSS_GAP_MS) continue
            if (previous.y >= rimCy || current.y < rimCy) continue
            val span = (current.y - previous.y).toDouble()
            if (abs(span) < 1e-6) continue
            val fraction = ((rimCy - previous.y) / span).toFloat().coerceIn(0f, 1f)
            val crossX = previous.x + (current.x - previous.x) * fraction
            sawCrossing = true
            // 碰筐弹起后再次落入仍算命中；只有所有真实下落穿越都在筐外才否掉。
            if (abs(crossX - rim.cx) <= 0.55f * rim.w) return true
        }
        if (sawCrossing) return false

        val belowRim = pts.subList((apex + 1).coerceAtMost(pts.size), pts.size)
            .firstOrNull { it.y >= rim.bottom && it.t - pts[apex].t <= MAX_OBSERVED_CROSS_GAP_MS * 2L }
            ?: return null
        return abs(belowRim.x - rim.cx) <= 0.55f * rim.w
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
    private const val MAX_OBSERVED_CROSS_GAP_MS = 800L
}
