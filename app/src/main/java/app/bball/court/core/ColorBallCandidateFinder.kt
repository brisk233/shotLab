package app.bball.court.core

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 橙色飞行球候选，用于补 YOLO 在高空和运动模糊下的漏检。 */
data class ColorBallCandidate(
    val rect: NRect,
    val confidence: Float,
)

class ColorBallCandidateFinder {

    private val history = ArrayList<List<NPt>>()

    fun reset() {
        history.clear()
    }

    fun findCandidates(
        bitmap: Bitmap,
        rim: NRect,
        players: List<NRect> = emptyList(),
        sceneCut: Boolean = false,
        workWidth: Int = 320,
    ): List<ColorBallCandidate> {
        if (sceneCut) reset()
        if (bitmap.width < 32 || bitmap.height < 32) return emptyList()
        val scale = min(1f, workWidth.toFloat() / bitmap.width)
        val width = (bitmap.width * scale).toInt().coerceAtLeast(16)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(16)
        val small = if (width == bitmap.width && height == bitmap.height) bitmap else {
            Bitmap.createScaledBitmap(bitmap, width, height, false)
        }
        return try {
            val pixels = IntArray(width * height)
            small.getPixels(pixels, 0, width, 0, 0, width, height)
            findCandidates(pixels, width, height, rim, players)
        } finally {
            if (small !== bitmap && !small.isRecycled) small.recycle()
        }
    }

    fun findCandidates(
        pixels: IntArray,
        width: Int,
        height: Int,
        rim: NRect,
        players: List<NRect> = emptyList(),
    ): List<ColorBallCandidate> {
        if (width < 32 || height < 32 || pixels.size < width * height) return emptyList()
        val total = width * height
        val mask = ByteArray(total)
        for (index in 0 until total) {
            val color = pixels[index]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            if (!isOrangeOrBrickRed(r, g, b)) continue
            mask[index] = 1
        }

        val seen = ByteArray(total)
        val stack = IntArray(total)
        val raw = ArrayList<ScoredCandidate>()
        for (start in 0 until total) {
            if (mask[start].toInt() == 0 || seen[start].toInt() != 0) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = 1
            var count = 0
            var sumX = 0L
            var sumY = 0L
            var minX = width
            var maxX = 0
            var minY = height
            var maxY = 0
            while (sp > 0) {
                val index = stack[--sp]
                val x = index % width
                val y = index / width
                count++
                sumX += x
                sumY += y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny !in 0 until height) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx !in 0 until width) continue
                        val next = ny * width + nx
                        if (mask[next].toInt() != 0 && seen[next].toInt() == 0) {
                            seen[next] = 1
                            stack[sp++] = next
                        }
                    }
                }
            }

            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (count < MIN_AREA || bw < 3 || bh < 3) continue
            val aspect = bw.toFloat() / bh
            if (aspect !in MIN_ASPECT..MAX_ASPECT) continue
            val fill = count.toFloat() / (bw * bh)
            if (fill < MIN_FILL) continue
            val area = count.toFloat() / total
            if (area > MAX_AREA ||
                bw > width * MAX_FRAME_FRACTION ||
                bh > height * MAX_FRAME_FRACTION
            ) continue
            val cx = (sumX.toFloat() / count) / width
            val cy = (sumY.toFloat() / count) / height
            if (cy < 0.04f || cy > 0.94f) continue

            val normalizedW = bw.toFloat() / width
            val normalizedH = bh.toFloat() / height
            if (isStaticRimBlob(cx, cy, normalizedW, normalizedH, rim)) continue

            val score = fill * (0.4f + area * 8f)
            raw.add(ScoredCandidate(cx, cy, normalizedW, normalizedH, score))
        }

        val accepted = raw
            .filter { candidate ->
                val observations = history.count { frame ->
                    frame.any { point ->
                        hypot(candidate.cx - point.x, candidate.cy - point.y) < STATIC_DISTANCE
                    }
                }
                observations < STATIC_FRAMES ||
                    isNearPlayer(candidate.cx, candidate.cy, players)
            }
            .sortedByDescending { it.score }
            .take(MAX_CANDIDATES)

        history.add(raw.map { NPt(it.cx, it.cy) })
        while (history.size > HISTORY_FRAMES) history.removeAt(0)

        return accepted.map { candidate ->
            val left = (candidate.cx - candidate.width / 2f).coerceIn(0f, 1f)
            val top = (candidate.cy - candidate.height / 2f).coerceIn(0f, 1f)
            val right = (candidate.cx + candidate.width / 2f).coerceIn(0f, 1f)
            val bottom = (candidate.cy + candidate.height / 2f).coerceIn(0f, 1f)
            ColorBallCandidate(
                rect = NRect(left, top, right, bottom),
                confidence = (0.30f + candidate.score * 0.90f).coerceIn(0.30f, 0.95f),
            )
        }
    }

    private fun isOrangeOrBrickRed(r: Int, g: Int, b: Int): Boolean {
        if (r <= 45 || r <= g + 8 || r < b - 8) return false
        val max = max(r, max(g, b))
        val min = min(r, min(g, b))
        val saturation = if (max == 0) 0f else (max - min).toFloat() / max
        if (saturation < 0.35f || max < 35) return false
        val delta = max - min
        if (delta == 0) return false
        val hue = when (max) {
            r -> 60f * ((g - b).toFloat() / delta)
            g -> 60f * (2f + (b - r).toFloat() / delta)
            else -> 60f * (4f + (r - g).toFloat() / delta)
        }.let { if (it < 0f) it + 360f else it }
        // 保留橙色和绕回 360 度的红紫色端，排除青色、蓝色和紫色干扰。
        return hue <= 40f || hue >= 330f
    }

    private fun isStaticRimBlob(
        cx: Float,
        cy: Float,
        width: Float,
        height: Float,
        rim: NRect,
    ): Boolean {
        if (abs(cx - rim.cx) >= rim.w * 0.90f) return false
        if (abs(cy - rim.cy) >= rim.h * 2.80f) return false
        return width > rim.w * 0.75f || height > rim.h * 1.25f
    }

    private fun isNearPlayer(cx: Float, cy: Float, players: List<NRect>): Boolean =
        players.any { player ->
            val padX = player.w * 0.80f
            val padY = player.h * 0.35f
            cx in (player.left - padX)..(player.right + padX) &&
                cy in (player.top - padY)..(player.bottom + padY)
        }

    private data class ScoredCandidate(
        val cx: Float,
        val cy: Float,
        val width: Float,
        val height: Float,
        val score: Float,
    )

    private companion object {
        const val MIN_AREA = 9
        const val MIN_ASPECT = 0.55f
        const val MAX_ASPECT = 1.80f
        const val MIN_FILL = 0.28f
        const val MAX_AREA = 0.03f
        const val MAX_FRAME_FRACTION = 0.09f
        const val STATIC_DISTANCE = 0.022f
        const val STATIC_FRAMES = 3
        const val HISTORY_FRAMES = 5
        const val MAX_CANDIDATES = 8
    }
}
