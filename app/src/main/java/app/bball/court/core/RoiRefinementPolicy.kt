package app.bball.court.core

import kotlin.math.abs

/**
 * 筐区二次精检只解决"球已在筐附近但主检测结果不可靠"的情况。
 * 高置信度主检测或篮筐外区域直接跳过，避免每帧重复跑同一模型。
 */
object RoiRefinementPolicy {

    private const val MIN_CONFIDENCE = 0.60f

    fun roiHalf(rim: NRect): Float = (rim.w * 6f).coerceIn(0.08f, 0.30f)

    fun shouldRun(ball: NPt?, confidence: Float, rim: NRect): Boolean {
        if (ball == null || confidence >= MIN_CONFIDENCE) return false
        val half = roiHalf(rim)
        return abs(ball.x - rim.cx) < half && abs(ball.y - rim.cy) < half
    }
}
