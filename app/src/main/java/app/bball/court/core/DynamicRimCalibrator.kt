package app.bball.court.core

import kotlin.math.abs
import kotlin.math.hypot

/** 自动篮筐检测器给出的单帧候选。坐标均为分析画面的归一化坐标。 */
data class RimCandidate(
    val rect: NRect,
    val confidence: Float,
)

/** 时序校准后的篮筐位置；短时丢检时返回位置和降低后的置信度。 */
data class RimTrackPoint(
    val tMs: Long,
    val rim: NRect,
    val confidence: Float,
    val segment: Int = 0,
)

/**
 * 手持视频的篮筐时序校准。
 *
 * 单帧检测会同时看到橙色地板、鞋、广告牌和篮筐。这里用上一帧速度预测做数据关联：
 * 平滑移动会持续命中预测附近，单帧远跳被拒绝；短时漏检用速度补间；
 * 调用方确认硬切时可清空旧速度并重新捕获；单帧可疑切镜不应直接重置整段轨迹。
 */
class DynamicRimCalibrator(
    private val maxGapMs: Long = 800L,
    private val minimumWidth: Float = 0.012f,
) {
    private var lastObservedMs = -1L
    private var lastRim: NRect? = null
    private var lastConfidence = 0f
    private var vxPerMs = 0f
    private var vyPerMs = 0f
    private var vwPerMs = 0f
    private var vhPerMs = 0f

    fun reset() {
        lastObservedMs = -1L
        lastRim = null
        lastConfidence = 0f
        vxPerMs = 0f
        vyPerMs = 0f
        vwPerMs = 0f
        vhPerMs = 0f
    }

    fun update(
        tMs: Long,
        candidates: List<RimCandidate>,
        sceneCut: Boolean = false,
    ): RimTrackPoint? {
        val continuation = if (sceneCut) choose(candidates, project(tMs)) else null
        val effectiveReset = sceneCut && continuation == null
        if (effectiveReset) reset()
        val bestCandidate = continuation ?: choose(
            candidates,
            if (effectiveReset) null else project(tMs),
        )
        if (bestCandidate != null) {
            val previous = lastRim
            if (previous == null || effectiveReset) {
                lastRim = bestCandidate.rect
            } else {
                val dt = (tMs - lastObservedMs).coerceAtLeast(1L)
                val cvx = if (sceneCut) 0f else (bestCandidate.rect.cx - previous.cx) / dt
                val cvy = if (sceneCut) 0f else (bestCandidate.rect.cy - previous.cy) / dt
                val cvw = if (sceneCut) 0f else (bestCandidate.rect.w - previous.w) / dt
                val cvh = if (sceneCut) 0f else (bestCandidate.rect.h - previous.h) / dt
                vxPerMs = blend(vxPerMs, cvx, VELOCITY_ALPHA)
                vyPerMs = blend(vyPerMs, cvy, VELOCITY_ALPHA)
                vwPerMs = blend(vwPerMs, cvw, VELOCITY_ALPHA)
                vhPerMs = blend(vhPerMs, cvh, VELOCITY_ALPHA)
                lastRim = smooth(previous, bestCandidate.rect)
            }
            lastObservedMs = tMs
            lastConfidence = bestCandidate.confidence.coerceIn(0f, 1f)
            return RimTrackPoint(tMs, lastRim!!, lastConfidence)
        }

        val current = lastRim ?: return null
        val gap = tMs - lastObservedMs
        if (gap < 0L || gap > maxGapMs) {
            reset()
            return null
        }
        val interp = project(tMs, current)
        val ratio = gap.toFloat() / maxGapMs.coerceAtLeast(1L).toFloat()
        return RimTrackPoint(
            tMs = tMs,
            rim = interp,
            confidence = (0.60f - 0.30f * ratio).coerceIn(0.15f, 0.60f),
        )
    }

    private fun choose(
        candidates: List<RimCandidate>,
        predicted: NRect?,
    ): RimCandidate? {
        val eligible = candidates.filter { it.rect.w >= minimumWidth }
        if (eligible.isEmpty()) return null
        if (predicted == null) return eligible.maxByOrNull { it.confidence }
        var best: RimCandidate? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (candidate in eligible) {
            val dx = abs(candidate.rect.cx - predicted.cx)
            val dy = abs(candidate.rect.cy - predicted.cy)
            if (dx > HORIZONTAL_GATE || dy > VERTICAL_GATE) continue
            val centerDistance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val sizeRatio = candidate.rect.w / predicted.w.coerceAtLeast(1e-4f)
            if (sizeRatio !in SIZE_RATIO_RANGE) continue
            val aspectRatio = (candidate.rect.w / candidate.rect.h.coerceAtLeast(1e-4f)) /
                (predicted.w / predicted.h.coerceAtLeast(1e-4f))
            if (aspectRatio !in ASPECT_RATIO_RANGE) continue
            val sizePenalty = abs(1f - sizeRatio).coerceAtMost(0.5f) * 0.70f
            val aspectPenalty = abs(1f - aspectRatio).coerceAtMost(0.5f) * 0.30f
            val score = candidate.confidence -
                centerDistance / HORIZONTAL_GATE * 0.45f -
                sizePenalty -
                aspectPenalty
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    private fun project(tMs: Long): NRect? {
        val rim = lastRim ?: return null
        return project(tMs, rim)
    }

    private fun project(tMs: Long, rim: NRect): NRect {
        val dt = (tMs - lastObservedMs).coerceAtLeast(0L)
        val cx = (rim.cx + vxPerMs * dt).coerceIn(rim.w / 2f, 1f - rim.w / 2f)
        val cy = (rim.cy + vyPerMs * dt).coerceIn(rim.h / 2f, 1f - rim.h / 2f)
        val width = (rim.w + vwPerMs * dt).coerceIn(MIN_WIDTH, MAX_WIDTH)
        val height = (rim.h + vhPerMs * dt).coerceIn(width * 0.18f, width * 0.80f)
        return NRect(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)
    }

    private fun smooth(previous: NRect, observed: NRect): NRect {
        val cx = blend(previous.cx, observed.cx, CENTER_ALPHA)
        val cy = blend(previous.cy, observed.cy, CENTER_ALPHA)
        val width = blend(previous.w, observed.w, SIZE_ALPHA)
        val height = blend(previous.h, observed.h, SIZE_ALPHA)
        return NRect(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)
    }

    private fun blend(previous: Float, observed: Float, alpha: Float): Float =
        previous + (observed - previous) * alpha

    private companion object {
        const val CENTER_ALPHA = 0.78f
        const val SIZE_ALPHA = 0.30f
        const val VELOCITY_ALPHA = 0.55f
        const val MIN_WIDTH = 0.012f
        const val MAX_WIDTH = 0.34f
        const val HORIZONTAL_GATE = 0.140f
        const val VERTICAL_GATE = 0.045f
        val SIZE_RATIO_RANGE = 0.65f..1.55f
        val ASPECT_RATIO_RANGE = 0.65f..1.45f
    }
}
