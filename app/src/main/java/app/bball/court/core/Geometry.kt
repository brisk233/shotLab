package app.bball.court.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Normalised point (0..1 of the video frame) — resolution independent. */
data class NPt(val x: Float, val y: Float)

/** Normalised rect; [top] < [bottom], [left] < [right]. */
data class NRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f
    val w get() = (right - left)
    val h get() = (bottom - top)
    fun contains(x: Float, y: Float, pad: Float = 0f): Boolean =
        x >= left - pad && x <= right + pad && y >= top - pad && y <= bottom + pad
}

/** One detected ball position with its timestamp in the video. */
data class BallSample(val tMs: Long, val p: NPt, val score: Float, val radius: Float = 0.03f)

/** Parabola y = a x^2 + b x + c fitted to a short ball track. */
data class Parabola(val a: Double, val b: Double, val c: Double) {
    fun y(x: Double) = a * x * x + b * x + c
    val isOpeningDown get() = a > 0            // image y grows downward, so a>0 == arc opening up in the world
}

object Geometry {

    /**
     * Least-squares parabola through up to `maxPts` recent samples.
     * Normal equations for the 3 coefficients, solved by Gaussian elimination —
     * no SciPy on a phone, and 3x3 is a few microseconds.
     */
    fun fitParabola(points: List<NPt>, maxPts: Int = 12): Parabola? {
        val pts = points.takeLast(maxPts)
        if (pts.size < 4) return null
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
        for (p in pts) {
            val x = p.x.toDouble(); val y = p.y.toDouble()
            val x2 = x * x
            s0 += 1.0; s1 += x; s2 += x2; s3 += x2 * x; s4 += x2 * x2
            t0 += y; t1 += x * y; t2 += x2 * y
        }
        val m = arrayOf(
            doubleArrayOf(s4, s3, s2, t2),
            doubleArrayOf(s3, s2, s1, t1),
            doubleArrayOf(s2, s1, s0, t0),
        )
        for (col in 0..2) {
            var pivot = col
            for (row in col + 1..2) if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
            if (abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            for (row in col + 1..2) {
                val f = m[row][col] / m[col][col]
                for (k in col..3) m[row][k] -= f * m[col][k]
            }
        }
        val c = DoubleArray(3)
        for (row in 2 downTo 0) {
            var sum = m[row][3]
            for (k in row + 1..2) sum -= m[row][k] * c[k]
            c[row] = sum / m[row][row]
        }
        return Parabola(c[0], c[1], c[2])
    }

    /** Angle at [b] formed by a-b-c, in degrees (0..180). */
    fun angleDeg(a: NPt, b: NPt, c: NPt): Float {
        val v1x = a.x - b.x; val v1y = a.y - b.y
        val v2x = c.x - b.x; val v2y = c.y - b.y
        val n1 = hypot(v1x, v1y); val n2 = hypot(v2x, v2y)
        if (n1 < 1e-5f || n2 < 1e-5f) return 0f
        val cosv = ((v1x * v2x + v1y * v2y) / (n1 * n2)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosv).toDouble()).toFloat()
    }

    /**
     * Did this track go through the rim? A make needs both:
     *  - the ball centre crossing the rim's top edge inside the rim's x-range, and
     *  - the ball still moving downward afterwards (it went through, not bounced up).
     */
    /**
     * 穿筐：用轨迹线段与筐面（y = hoop.top）求交，允许跨多帧。
     * 旧版要求"相邻两帧一上一下"，4fps 采样下球会整段跳过 → 大量漏判。
     */
    fun isMade(track: List<BallSample>, hoop: NRect): Boolean {
        if (track.size < 3) return false
        var crossedX: Float? = null
        for (i in 1 until track.size) {
            val a = track[i - 1].p
            val b = track[i].p
            val ya = a.y - hoop.top
            val yb = b.y - hoop.top
            if ((ya < 0f) == (yb < 0f)) continue
            val t = if (yb - ya == 0f) 0f else -ya / (yb - ya)
            val x = a.x + (b.x - a.x) * t
            if (x in hoop.left..hoop.right && b.y > a.y) crossedX = x
        }
        return crossedX != null
    }

    /** Release angle: direction of ball travel over the last ~0.25 s, degrees above horizontal. */
    fun releaseAngle(track: List<BallSample>): Float? {
        if (track.size < 3) return null
        val a = track[track.size - 3].p
        val b = track.last().p
        val dx = b.x - a.x
        val dy = b.y - a.y
        if (hypot(dx, dy) < 1e-4f) return null
        val deg = Math.toDegrees(kotlin.math.atan2(-dy.toDouble(), dx.toDouble())).toFloat()
        // 出手角只可能在 5°~85°；超出的说明这段轨迹是运球/传球/跟踪漂移，不是投篮
        return if (deg in 5f..85f) deg else null
    }
}

/** 出手类型（用篮筐实际宽度 45cm 反推米制距离）。 */
enum class ShotType(val label: String) {
    LAYUP("上篮"), TWO("两分"), THREE("三分"), FREE_THROW("罚球"), UNKNOWN("未分类");

    companion object {
        /**
         * 未做球场单应标定时的保守估算。
         * 使用出手点到校正筐口的像素距离除以筐宽，抵消镜头缩放后再分段。
         */
        fun estimateFromRimRatio(ratio: Float): ShotType = when {
            !ratio.isFinite() -> UNKNOWN
            ratio <= 2.0f -> LAYUP
            ratio >= 4.8f -> THREE
            else -> TWO
        }

        /**
         * @param releaseX/releaseY 出手点（归一化坐标）
         * @param rim 用户框定的篮筐（归一化）
         * @param rimRealWidthM 篮筐内径，FIBA/NBA 都是 0.45 m
         */
        @Deprecated("未做球场单应标定时禁止用画面像素距离猜测投篮类型")
        fun classify(releaseX: Float, releaseY: Float, rim: NRect, rimRealWidthM: Float = 0.45f): ShotType {
            if (rim.w <= 0.001f) return TWO
            // 框宽 ≈ 篮筐内径 45cm → 归一化像素差除以框宽就是"多少个筐宽"
            val dxRim = (releaseX - rim.cx) / rim.w
            val dyRim = (releaseY - rim.cy) / rim.w * 0.6f   // 画面 y 被拉伸，压回近似圆形
            val meters = kotlin.math.hypot(dxRim.toDouble(), dyRim.toDouble()).toFloat() * rimRealWidthM
            return when {
                meters < 1.5f -> LAYUP
                meters >= 6.5f -> THREE
                meters in 4.0f..5.3f -> FREE_THROW
                else -> TWO
            }
        }
    }
}

/** 单次出手记录。 */
data class ShotEvent(
    val startFrame: Int,
    val tMs: Long = 0L,
    val type: ShotType,
    val made: Boolean,
    val releaseX: Float,
    val releaseY: Float,
    val distanceM: Float,
    val releaseAngle: Float?,
    /** 出手瞬间的缩略图，供"出手明细"页人工核对。 */
    val thumb: android.graphics.Bitmap? = null,
    /** 球场米制坐标（单应解算；未标定时为 0）。 */
    val courtX: Float = 0f,
    val courtY: Float = 0f,
    /** 判定为命中的时刻（毫秒）；未命中为 0。集锦按这个时间收尾。 */
    val makeMs: Long = 0L,
    /** 轨迹穿过筐平面的判定时刻（毫秒），命中和不中都有值。 */
    val resolveMs: Long = 0L,
)

/** Accumulated numbers the report screen shows. */
data class ShotReport(
    val attempts: Int = 0,
    val made: Int = 0,
    val layupMade: Int = 0, val layupAtt: Int = 0,
    val twoMade: Int = 0, val twoAtt: Int = 0,
    val threeMade: Int = 0, val threeAtt: Int = 0,
    val ftMade: Int = 0, val ftAtt: Int = 0,
    val avgReleaseAngle: Float? = null,
    val avgElbowAngle: Float? = null,
    val avgKneeAngle: Float? = null,
    val avgBallInHandMs: Long? = null,
    val framesAnalysed: Int = 0,
    val ballHitRate: Float = 0f,
    val error: String? = null,
    val shots: List<ShotEvent> = emptyList(),
    val calibrated: Boolean = false,
    /** 筐框来自机位记忆（自动套用，无人工介入）。 */
    val autoRim: Boolean = false,
    /** 筐宽 ÷ 球宽。真实值约 1.9；明显偏大说明框把篮板也算进去了。 */
    val rimBallRatio: Float = 0f,
    /** 筐框宽度（像素）——命中判定的空间分辨率指标，<90px 就不稳。 */
    val rimPx: Int = 0,
    /** 穿筐轨迹把机位记忆中的筐框自动校正了多少像素。 */
    val rimAdjustedPx: Int = 0,
    val bestStreak: Int = 0,
    val bestMissStreak: Int = 0,
    val avgIntervalMs: Long = 0L,
) {
    val missed get() = (attempts - made).coerceAtLeast(0)
    val accuracy get() = if (attempts == 0) 0f else made.toFloat() / attempts
    val unknownMade get() = (made - layupMade - twoMade - threeMade - ftMade).coerceAtLeast(0)
    val unknownAtt get() = (attempts - layupAtt - twoAtt - threeAtt - ftAtt).coerceAtLeast(0)
}
