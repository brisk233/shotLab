package app.bball.court.core

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 无人工粗框的篮筐候选提取。
 *
 * 篮筐在画面中通常表现为上中部、横向橙色环。这里只产生少量时序候选，
 * 由 [DynamicRimCalibrator] 根据运动连续性完成最终选择；单帧阈值不直接决定结果。
 */
object RimCandidateFinder {

    fun findCandidates(bitmap: Bitmap, workWidth: Int = 480): List<RimCandidate> {
        if (bitmap.width < 16 || bitmap.height < 16) return emptyList()
        val scale = min(1f, workWidth.toFloat() / bitmap.width)
        val width = (bitmap.width * scale).toInt().coerceAtLeast(16)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(16)
        val small = if (width == bitmap.width && height == bitmap.height) bitmap else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        return try {
            val pixels = IntArray(width * height)
            small.getPixels(pixels, 0, width, 0, 0, width, height)
            findCandidates(pixels, width, height)
        } finally {
            if (small !== bitmap && !small.isRecycled) small.recycle()
        }
    }

    /**
     * 纯像素入口，便于 JVM 回归。返回按置信度降序的最多 [MAX_CANDIDATES] 个框。
     */
    fun findCandidates(pixels: IntArray, width: Int, height: Int): List<RimCandidate> {
        if (width < 16 || height < 16 || pixels.size < width * height) return emptyList()
        val total = width * height
        val minArea = max(6, total / 20_000)
        val maxArea = max(24, (total * 0.020f).toInt())
        val mask = ByteArray(total)
        for (i in 0 until total) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val y = i / width
            if (y > height * 0.55f) continue
            if (isRimOrange(r, g, b)) mask[i] = 1
        }

        val seen = ByteArray(total)
        val stack = IntArray(total)
        val found = ArrayList<RimCandidate>()
        for (start in 0 until total) {
            if (mask[start].toInt() == 0 || seen[start].toInt() != 0) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = 1
            var area = 0
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
                area++
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

            if (area !in minArea..maxArea) continue
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (bw < 3 || bh < 2 || bw > width * 0.14f || bh > height * 0.08f) continue
            val aspect = bw.toFloat() / bh
            if (aspect !in 1.10f..4.8f) continue
            val fill = area.toFloat() / (bw * bh)
            if (fill < 0.08f) continue
            val px = (sumX.toFloat() / area) / width
            val py = (sumY.toFloat() / area) / height
            if (py !in 0.055f..0.55f) continue

            val aspectScore = (1f - (abs(ln(aspect / 2.0f)) / 1.6f)).coerceIn(0f, 1f)
            val areaScore = (area.toFloat() / maxArea).coerceIn(0f, 1f)
            val upperScore = (1f - abs(py - 0.38f) / 0.42f).coerceIn(0f, 1f)
            val backboardScore = backboardScore(
                pixels = pixels,
                width = width,
                height = height,
                minX = minX,
                maxX = maxX,
                minY = minY,
                bw = bw,
                bh = bh,
            )
            val edgeScore = edgeScore(
                pixels = pixels,
                width = width,
                height = height,
                minX = minX,
                maxX = maxX,
                minY = minY,
                maxY = maxY,
                bw = bw,
                bh = bh,
            )
            val confidence = (
                0.06f +
                    0.14f * aspectScore +
                    0.06f * areaScore +
                    0.06f * upperScore +
                    0.24f * backboardScore +
                    0.44f * edgeScore
                )
                .coerceIn(0f, 1f)
            if (confidence < 0.20f) continue

            val padX = bw * 0.08f
            val padY = bh * 0.18f
            val rect = NRect(
                ((minX - padX) / width).coerceIn(0f, 1f),
                ((minY - padY) / height).coerceIn(0f, 1f),
                ((maxX + 1 + padX) / width).coerceIn(0f, 1f),
                ((maxY + 1 + padY) / height).coerceIn(0f, 1f),
            )
            found.add(RimCandidate(rect, confidence))
        }
        return found
            .sortedByDescending { it.confidence }
            .take(MAX_CANDIDATES)
    }

    private fun isRimOrange(r: Int, g: Int, b: Int): Boolean {
        val max = max(r, max(g, b))
        val min = min(r, min(g, b))
        if (max < 45) return false
        val delta = max - min
        val saturation = if (max == 0) 0f else delta.toFloat() / max
        if (saturation < 0.32f || r < 70) return false
        if (delta == 0) return false
        val hue = when (max) {
            r -> 60f * ((g - b).toFloat() / delta)
            g -> 60f * (2f + (b - r).toFloat() / delta)
            else -> 60f * (4f + (r - g).toFloat() / delta)
        }.let { if (it < 0f) it + 360f else it }
        // 保留橙红以及绕回 360 度的偏红端，排除青色、蓝色和绿色干扰。
        return hue <= 40f || hue >= 345f
    }

    private fun backboardScore(
        pixels: IntArray,
        width: Int,
        height: Int,
        minX: Int,
        maxX: Int,
        minY: Int,
        bw: Int,
        bh: Int,
    ): Float {
        val left = (minX - bw).coerceAtLeast(0)
        val right = (maxX + bw).coerceAtMost(width - 1)
        val top = (minY - bh * 4).coerceAtLeast(0)
        val bottom = (minY - 1).coerceAtMost(height - 1)
        if (right <= left || bottom <= top) return 0f
        var white = 0
        var count = 0
        var saturationTotal = 0f
        for (y in top..bottom) {
            val row = y * width
            for (x in left..right) {
                val color = pixels[row + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val max = max(r, max(g, b))
                val min = min(r, min(g, b))
                val saturation = if (max == 0) 0f else (max - min).toFloat() / max
                saturationTotal += saturation
                if (max >= 165 && saturation <= 0.28f) white++
                count++
            }
        }
        if (count == 0) return 0f
        val whiteRatio = (white.toFloat() / count).coerceIn(0f, 1f)
        val meanSaturation = saturationTotal / count
        val lowSaturation = (1f - meanSaturation / 0.60f).coerceIn(0f, 1f)
        return whiteRatio * (0.45f + 0.55f * lowSaturation)
    }

    private fun edgeScore(
        pixels: IntArray,
        width: Int,
        height: Int,
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int,
        bw: Int,
        bh: Int,
    ): Float {
        val left = (minX - bw).coerceAtLeast(0)
        val right = (maxX + bw).coerceAtMost(width - 2)
        val top = (minY - bh * 2).coerceAtLeast(0)
        val bottom = (maxY + bh * 3).coerceAtMost(height - 2)
        if (right <= left + 1 || bottom <= top + 1) return 0f
        var total = 0L
        var count = 0
        var y = top
        while (y <= bottom) {
            var x = left
            while (x <= right) {
                val index = y * width + x
                val gray = gray(pixels[index])
                val dx = kotlin.math.abs(gray - gray(pixels[index + 1]))
                val dy = kotlin.math.abs(gray - gray(pixels[index + width]))
                total += dx + dy
                count++
                x += 2
            }
            y += 2
        }
        if (count == 0) return 0f
        val meanGradient = total.toFloat() / (count * 2)
        return (meanGradient / 48f).coerceIn(0f, 1f)
    }

    private fun gray(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }

    private const val MAX_CANDIDATES = 8
}
