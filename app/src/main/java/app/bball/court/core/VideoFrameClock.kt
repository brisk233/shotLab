package app.bball.court.core

import kotlin.math.roundToInt

/** 把分析时间映射到源视频帧号，避免固定按 30 fps 取错 60 fps / 24 fps 素材。 */
object VideoFrameClock {

    private const val DEFAULT_FPS = 30.0
    private const val MIN_FPS = 1.0
    private const val MAX_FPS = 240.0

    fun sanitizeFps(value: Double?): Double {
        if (value == null || !value.isFinite() || value !in MIN_FPS..MAX_FPS) return DEFAULT_FPS
        return value
    }

    fun frameIndexAt(tMs: Long, fps: Double): Int {
        val safeMs = tMs.coerceAtLeast(0L)
        return (safeMs / 1000.0 * sanitizeFps(fps)).roundToInt()
    }
}
