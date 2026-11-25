package app.bball.court.core

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * 剪辑过的视频（集锦/小红书）在片段之间是硬切。
 * 不处理硬切 → 球在两段之间"瞬移"，跟踪器把它当成一次出手 → 假数据。
 *
 * 做法：把画面降采样成 8x8 的亮度网格，前后两帧逐格比较，
 * 差异过大的格子占比超过阈值就判为切镜。
 */
object SceneCut {

    private const val GRID = 8
    private const val CELL_DIFF = 26          // 单格亮度差
    private const val CUT_RATIO = 0.50f       // 变化格占比超过这个就判切镜
    private const val MIN_CUT_INTERVAL_MS = 500L

    fun signature(bmp: Bitmap): FloatArray {
        // 先缩到 GRID×GRID（一次缩放开销远小于逐点 getPixel）
        val tiny = Bitmap.createScaledBitmap(bmp, GRID, GRID, false)
        val px = IntArray(GRID * GRID)
        tiny.getPixels(px, 0, GRID, 0, 0, GRID, GRID)
        if (tiny !== bmp) tiny.recycle()
        val out = FloatArray(GRID * GRID)
        for (i in px.indices) {
            val c = px[i]
            out[i] = (((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8).toFloat()
        }
        return out
    }

    fun isCut(prev: FloatArray?, cur: FloatArray): Boolean {
        if (prev == null) return false
        var changed = 0
        for (i in prev.indices) if (abs(prev[i] - cur[i]) > CELL_DIFF) changed++
        return changed.toFloat() / prev.size > CUT_RATIO
    }

    /**
     * 镜头缩放、快速横移和局部遮挡也可能让单帧差异突然变大。
     * 真正硬切仍会立即通过，但 500ms 内重复变化只保留第一次，避免动态筐被连续重置。
     */
    class Gate(
        private val minIntervalMs: Long = MIN_CUT_INTERVAL_MS,
    ) {
        private var lastAcceptedMs = Long.MIN_VALUE / 2

        fun update(tMs: Long, prev: FloatArray?, cur: FloatArray): Boolean {
            if (!isCut(prev, cur)) return false
            if (tMs - lastAcceptedMs < minIntervalMs) return false
            lastAcceptedMs = tMs
            return true
        }
    }
}
