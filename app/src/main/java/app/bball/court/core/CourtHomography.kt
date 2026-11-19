package app.bball.court.core

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 图像坐标 → 球场米制坐标（单应矩阵，DLT 求解）。
 *
 * 为什么必须做这一步：
 *   只看像素距离无法区分三分/罚球 —— 同一个"离筐 200 像素"，
 *   在近景和远景里对应的真实距离完全不同。用户点 4 个标准点位后，
 *   就能把任意图像点换算成球场上的米制坐标，距离、热区、三分线才有意义。
 *
 * 标定点（FIBA 标准，单位米，原点 = 篮筐正下方地面投影）：
 *   P0 篮筐正下方地面      ( 0.00, 0.00)
 *   P1 罚球线左端          (-2.45, 4.225)
 *   P2 罚球线右端          ( 2.45, 4.225)
 *   P3 三分弧顶地面        ( 0.00, 6.75)
 */
class CourtHomography private constructor(private val h: DoubleArray) {

    /** 图像点（归一化 0..1）→ 球场米制坐标。 */
    fun toCourt(x: Float, y: Float): Pair<Float, Float> {
        val d = h[6] * x + h[7] * y + h[8]
        if (abs(d) < 1e-9) return 0f to 0f
        val X = (h[0] * x + h[1] * y + h[2]) / d
        val Y = (h[3] * x + h[4] * y + h[5]) / d
        return X.toFloat() to Y.toFloat()
    }

    /** 出手点到篮筐的米制距离（篮筐在原点）。 */
    fun distanceToRim(x: Float, y: Float): Float {
        val (X, Y) = toCourt(x, y)
        return hypot(X.toDouble(), Y.toDouble()).toFloat()
    }

    /** 按 FIBA 规则判定出手类型。 */
    fun shotType(x: Float, y: Float): ShotType {
        val (X, Y) = toCourt(x, y)
        val dist = hypot(X.toDouble(), Y.toDouble()).toFloat()
        val cornerThree = abs(X) >= 6.60f          // 底角三分线离边线 0.9m
        return when {
            dist < 1.5f -> ShotType.LAYUP
            dist >= 6.75f || cornerThree -> ShotType.THREE
            dist in 4.0f..4.7f -> ShotType.FREE_THROW
            else -> ShotType.TWO
        }
    }

    companion object {
        /** 标准点位（米），顺序固定，UI 按这个顺序引导用户点。 */
        val REFERENCE = listOf(
            "篮筐正下方地面" to (0.0 to 0.0),
            "罚球线左端" to (-2.45 to 4.225),
            "罚球线右端" to (2.45 to 4.225),
            "三分弧顶地面" to (0.0 to 6.75),
        )

        /**
         * 由 4 组对应点解单应矩阵（8 个未知数，8 个方程）。
         * @param img 图像点（归一化 0..1），顺序与 REFERENCE 一致
         */
        fun solve(img: List<Pair<Float, Float>>): CourtHomography? {
            if (img.size != 4) return null
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val (x, y) = img[i]
                val (X, Y) = REFERENCE[i].second
                val r = i * 2
                a[r][0] = x.toDouble(); a[r][1] = y.toDouble(); a[r][2] = 1.0
                a[r][6] = -x * X; a[r][7] = -y * X; a[r][8] = X
                a[r + 1][3] = x.toDouble(); a[r + 1][4] = y.toDouble(); a[r + 1][5] = 1.0
                a[r + 1][6] = -x * Y; a[r + 1][7] = -y * Y; a[r + 1][8] = Y
            }
            // 高斯消元（列主元）
            for (col in 0 until 8) {
                var piv = col
                for (r in col + 1 until 8) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
                if (abs(a[piv][col]) < 1e-12) return null
                if (piv != col) { val t = a[piv]; a[piv] = a[col]; a[col] = t }
                val d = a[col][col]
                for (c in col..8) a[col][c] /= d
                for (r in 0 until 8) {
                    if (r == col) continue
                    val f = a[r][col]
                    if (abs(f) < 1e-15) continue
                    for (c in col..8) a[r][c] -= f * a[col][c]
                }
            }
            val h = DoubleArray(9)
            for (i in 0 until 8) h[i] = a[i][8]
            h[8] = 1.0
            val hm = CourtHomography(h)
            // 自检：四点回代误差过大说明点位几乎共线
            for (i in 0 until 4) {
                val (X, Y) = hm.toCourt(img[i].first, img[i].second)
                val (tx, ty) = REFERENCE[i].second
                if (hypot((X - tx).toDouble(), (Y - ty).toDouble()) > 0.35) return null
            }
            return hm
        }
    }
}
