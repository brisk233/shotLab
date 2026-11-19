package app.bball.court.core

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * B 的一半：零依赖的模板跟踪器（归一化互相关 + 匀速预测）。
 *
 * 为什么不用 OpenCV 的 KCF/CSRT：那是 ~20MB 的额外 so，而这里只需要跟住一个
 * 40x40 的小球；自带的 NCC 搜索在 480~960px 的帧上只要几毫秒，还省掉一次
 * native 依赖。检测器（A）每 N 帧校正一次，跟踪器负责中间帧。
 */
class TemplateTracker(
    defaultHalf: Int = 20,             // 默认 template 半径，直径 41px
    private val searchPad: Int = 26,   // 预测点周围搜索半径
    private val step: Int = 2,         // 搜索步长（2 = 四分之一候选点，速度 x4）
) {
    private var tpl: FloatArray? = null
    private var tplW = 0
    private var tplH = 0
    private var tplMean = 0f
    private var tplNorm = 0f

    private var centerX = 0f
    private var centerY = 0f
    private var vx = 0f
    private var vy = 0f

    private var coast = 0
    var lastScore = 0f
        private set
    val hasTarget get() = tpl != null

    fun reset() {
        tpl = null; lastScore = 0f; vx = 0f; vy = 0f
    }

    /** 用一帧和归一化中心点初始化模板。 */
    /** 模板半径：跟着检测框走，球大模板大，避免把背景一起锁进去。 */
    var half = defaultHalf
        private set

    fun init(bmp: Bitmap, p: NPt, radius: Float = 0f): Boolean {
        val cx = (p.x * bmp.width).toInt()
        val cy = (p.y * bmp.height).toInt()
        if (radius > 0f) {
            half = (radius * bmp.width).toInt().coerceIn(10, 34)
        }
        return initPx(bmp, cx, cy)
    }

    fun initPx(bmp: Bitmap, cx: Int, cy: Int): Boolean {
        val x0 = cx - half; val y0 = cy - half
        val w = half * 2 + 1; val h = w
        if (x0 < 0 || y0 < 0 || x0 + w > bmp.width || y0 + h > bmp.height) return false
        val px = IntArray(bmp.width * bmp.height)
        return try {
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val g = FloatArray(w * h)
            var sum = 0f
            for (y in 0 until h) for (x in 0 until w) {
                val c = px[(y0 + y) * bmp.width + (x0 + x)]
                val v = (((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8).toFloat()
                g[y * w + x] = v; sum += v
            }
            val mean = sum / g.size
            var sq = 0f
            for (i in g.indices) { val d = g[i] - mean; g[i] = d; sq += d * d }
            tpl = g; tplW = w; tplH = h; tplMean = mean; tplNorm = sqrt(sq).coerceAtLeast(1e-3f)
            centerX = cx.toFloat(); centerY = cy.toFloat(); vx = 0f; vy = 0f; lastScore = 1f
            true
        } catch (t: Throwable) { false }
    }

    /** 在预测位置附近搜索，返回归一化坐标；分数过低返回 null。 */
    fun update(bmp: Bitmap, minScore: Float = 0.48f): NPt? {
        val t = tpl ?: return null
        val px = IntArray(bmp.width * bmp.height)
        return try {
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val predX = centerX + vx
            val predY = centerY + vy
            // 动得越快，搜索窗越大（球在 4fps 采样下可能瞬移 30~50px）
            val pad = (searchPad + kotlin.math.hypot(vx, vy) * 2f).toInt().coerceIn(searchPad, 90)
            var bestX = -1; var bestY = -1; var best = -1f
            val x0 = max(half, (predX - pad).toInt())
            val y0 = max(half, (predY - pad).toInt())
            val x1 = min(bmp.width - half - 1, (predX + pad).toInt())
            val y1 = min(bmp.height - half - 1, (predY + pad).toInt())
            var yy = y0
            while (yy <= y1) {
                var xx = x0
                while (xx <= x1) {
                    val s = ncc(px, bmp.width, xx, yy, t)
                    if (s > best) { best = s; bestX = xx; bestY = yy }
                    xx += step
                }
                yy += step
            }
            if (bestX < 0 || best < minScore) {
                lastScore = best.coerceAtLeast(0f)
                // 允许短时滑行：按预测位置继续给点，检测器下一轮会把它拉回来
                if (coast < 2 && hasTarget) {
                    coast++
                    centerX = (centerX + vx).coerceIn(half.toFloat(), (bmp.width - half).toFloat())
                    centerY = (centerY + vy).coerceIn(half.toFloat(), (bmp.height - half).toFloat())
                    lastScore = 0.35f
                    return NPt(centerX / bmp.width, centerY / bmp.height)
                }
                return null
            }
            coast = 0
            // 亚像素没必要：2px 网格够用于 40px 的球
            val nx = bestX.toFloat(); val ny = bestY.toFloat()
            vx = (vx * 0.5f + (nx - centerX) * 0.5f).coerceIn(-40f, 40f)
            vy = (vy * 0.5f + (ny - centerY) * 0.5f).coerceIn(-40f, 40f)
            centerX = nx; centerY = ny; lastScore = best
            NPt(nx / bmp.width, ny / bmp.height)
        } catch (t2: Throwable) { null }
    }

    private fun ncc(px: IntArray, stride: Int, cx: Int, cy: Int, t: FloatArray): Float {
        val w = tplW; val h = tplH
        val x0 = cx - half; val y0 = cy - half
        var sum = 0f
        var i = 0
        for (y in 0 until h) {
            val row = (y0 + y) * stride + x0
            for (x in 0 until w) {
                val c = px[row + x]
                sum += (((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8).toFloat()
                i++
            }
        }
        val mean = sum / (w * h)
        var num = 0f
        var den = 0f
        i = 0
        for (y in 0 until h) {
            val row = (y0 + y) * stride + x0
            for (x in 0 until w) {
                val c = px[row + x]
                val v = (((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8).toFloat() - mean
                num += v * t[i]
                den += v * v
                i++
            }
        }
        return if (den < 1e-3f) 0f else num / (sqrt(den) * tplNorm)
    }
}
