package app.bball.court.core

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * 自动贴合篮筐：在用户给的粗框附近找"橙色圆环"像素，收紧成真实筐框。
 *
 * 为什么需要：用户很难把框拖得刚好等于筐内径（45.7cm），而筐宽是我们唯一的米制尺度来源。
 * 框偏大 1 倍 → 所有距离/命中容差都偏 2 倍。
 */
object RimSnap {

    /** @return 收紧后的筐框（归一化），找不到返回 null */
    fun snap(frame: Bitmap, rough: NRect, expand: Float = 0.6f): NRect? {
        val w = frame.width; val h = frame.height
        if (w < 32 || h < 32) return null

        // 搜索区域：粗框外扩
        val padW = rough.w * expand
        val padH = rough.w * expand
        val l = ((rough.left - padW) * w).toInt().coerceIn(0, w - 2)
        val r = ((rough.right + padW) * w).toInt().coerceIn(l + 2, w)
        val t = ((rough.top - padH) * h).toInt().coerceIn(0, h - 2)
        val b = ((rough.bottom + padH) * h).toInt().coerceIn(t + 2, h)

        val pw = r - l; val ph = b - t
        if (pw < 8 || ph < 8) return null

        val px = IntArray(pw * ph)
        frame.getPixels(px, 0, pw, l, t, pw, ph)

        var minX = Int.MAX_VALUE; var maxX = -1
        var minY = Int.MAX_VALUE; var maxY = -1
        var count = 0
        val hsv = FloatArray(3)
        for (yy in 0 until ph) {
            for (xx in 0 until pw) {
                val c = px[yy * pw + xx]
                Color.colorToHSV(c, hsv)
                // 篮筐橙：H 5..40, S>0.35, V>0.25
                // 放宽：室内筐偏暗、且是空心圆环（填充率低），不能用严格填充率筛
                if (hsv[0] >= 0f && hsv[0] <= 45f && hsv[1] > 0.22f && hsv[2] > 0.12f) {
                    if (xx < minX) minX = xx
                    if (xx > maxX) maxX = xx
                    if (yy < minY) minY = yy
                    if (yy > maxY) maxY = yy
                    count++
                }
            }
        }
        android.util.Log.i("ShotLab", "snap cand count=" + count + " box=" + minX + "," + minY + "," + maxX + "," + maxY)
        if (count < 8 || maxX <= minX || maxY <= minY) return null

        // 命中的橙色像素必须在该区域内是"成块"的（占比够高），否则可能是球/地板反光
        val boxArea = (maxX - minX + 1) * (maxY - minY + 1)
        if (count.toFloat() / boxArea < 0.10f) return null

        val nx1 = (l + minX).toFloat() / w
        val nx2 = (l + maxX + 1).toFloat() / w
        val ny1 = (t + minY).toFloat() / h
        val ny2 = (t + maxY + 1).toFloat() / h
        // 稍微放一点点余量，避免只框住筐的一小段
        val padX = (nx2 - nx1) * 0.06f
        val padY = (ny2 - ny1) * 0.10f
        return NRect(
            (nx1 - padX).coerceIn(0f, 1f), (ny1 - padY).coerceIn(0f, 1f),
            (nx2 + padX).coerceIn(0f, 1f), (ny2 + padY).coerceIn(0f, 1f),
        )
    }
}
