package app.bball.court.core

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * 橙色先验补点：篮球是橙色/砖红色，这在图像上是很强的先验。
 *
 * 为什么需要它：COCO 的 `sports ball` 在 26px 的小球上召回只有个位数百分比；
 * 而"橙色、近似圆形、块面积在合理区间"这条规则在 320px 缩略图上只要 1~2ms，
 * 正好补上检测器的短板。它只负责给跟踪器一个种子，精度由 NCC 跟踪保证。
 */
class ColorBallFinder {

    private var lastX = -1f
    private var lastY = -1f
    private var staticRun = 0

    /**
     * 在缩略图上找最像球的橙色块。
     *
     * 三个约束缺一不可（实测：不加约束时，你的球场那排红叶灌木会被当成球，
     * 旧版在真实视频上"38% 命中"里几乎全是假阳性）：
     *   1. 形状：外接框接近正方形 + 实心度高（花丛是不规则块）
     *   2. 运动：同一个位置连续命中 2 次以上 = 静止物体（球一定在动）
     *   3. 人球接近：只在球员框附近找（播种时球一定在手上）
     */
    fun find(bmp: Bitmap, players: List<NRect> = emptyList(), workWidth: Int = 320): Pair<NPt, Float>? {
        val w = bmp.width; val h = bmp.height
        if (w < 32 || h < 32) return null
        val scale = (workWidth.toFloat() / w).coerceAtLeast(0.05f).coerceAtMost(1f)
        val sw = (w * scale).toInt().coerceAtLeast(16)
        val sh = (h * scale).toInt().coerceAtLeast(16)
        val small = try { Bitmap.createScaledBitmap(bmp, sw, sh, false) } catch (t: Throwable) { return null }
        val px = IntArray(sw * sh)
        return try {
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            val mask = BooleanArray(sw * sh)
            for (i in px.indices) {
                val c = px[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val max = maxOf(r, g, b); val min = minOf(r, g, b)
                val v = max
                val s = if (max == 0) 0 else (max - min) * 100 / max
                // 橙：红明显高于绿蓝，饱和度高，亮度不太暗
                mask[i] = r > 90 && r > g + 30 && g >= b - 10 && s > 45 && v > 55
            }
            // 连通域（4 邻域，迭代栈）
            val seen = BooleanArray(sw * sh)
            var bestScore = 0f
            var best: Pair<NPt, Float>? = null
            val stack = IntArray(sw * sh)
            for (start in px.indices) {
                if (!mask[start] || seen[start]) continue
                var sp = 0
                stack[sp++] = start; seen[start] = true
                var count = 0
                var sx = 0L; var sy = 0L
                var minX = sw; var maxX = 0; var minY = sh; var maxY = 0
                while (sp > 0) {
                    val idx = stack[--sp]
                    val x = idx % sw; val y = idx / sw
                    count++; sx += x; sy += y
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                    if (x > 0 && mask[idx - 1] && !seen[idx - 1]) { seen[idx - 1] = true; stack[sp++] = idx - 1 }
                    if (x < sw - 1 && mask[idx + 1] && !seen[idx + 1]) { seen[idx + 1] = true; stack[sp++] = idx + 1 }
                    if (y > 0 && mask[idx - sw] && !seen[idx - sw]) { seen[idx - sw] = true; stack[sp++] = idx - sw }
                    if (y < sh - 1 && mask[idx + sw] && !seen[idx + sw]) { seen[idx + sw] = true; stack[sp++] = idx + sw }
                }
                val bw = maxX - minX + 1; val bh = maxY - minY + 1
                if (count < 12 || bw < 4 || bh < 4) continue
                val aspect = bw.toFloat() / bh
                if (aspect < 0.70f || aspect > 1.40f) continue      // 更接近圆
                val fill = count.toFloat() / (bw * bh)
                if (fill < 0.60f) continue                          // 实心（球 ≈0.78，花丛 <0.5）
                val cx = (sx.toFloat() / count) / sw
                val cy = (sy.toFloat() / count) / sh
                // 越大越可能是球，但占满全屏的橙块（衣服/地胶）要排掉
                val area = count.toFloat() / (sw * sh)
                if (area > 0.05f) continue
                if (players.isNotEmpty()) {
                    var near = false
                    for (r in players) {
                        val pw = r.w; val ph = r.h
                        if (cx >= r.left - 0.8f * pw && cx <= r.right + 0.8f * pw &&
                            cy >= r.top - 0.3f * ph && cy <= r.bottom + 0.3f * ph) { near = true; break }
                    }
                    if (!near) continue
                }
                val score = fill * (0.4f + area * 8f)
                if (score > bestScore) {
                    bestScore = score
                    best = NPt(cx, cy) to (bw.toFloat() / sw / 2f)
                }
            }
            // 静止过滤：同一位置连续命中 → 判为静止物体
            val b = best
            if (b != null) {
                val dx = Math.abs(b.first.x - lastX)
                val dy = Math.abs(b.first.y - lastY)
                if (dx < 0.012f && dy < 0.012f) {
                    staticRun++
                    if (staticRun >= 2) return null
                } else {
                    staticRun = 0
                }
                lastX = b.first.x; lastY = b.first.y
            }
            b
        } catch (t: Throwable) {
            null
        } finally {
            if (small !== bmp) small.recycle()
        }
    }
}
