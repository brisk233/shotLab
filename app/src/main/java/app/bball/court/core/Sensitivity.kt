package app.bball.court.core

import android.content.Context

/**
 * 出手判定灵敏度。同一段视频在不同档位下的出手数差别很大（实测 25 / 12 / 4），
 * 因为"什么是投篮"这件事在不同视频里差别太大 —— 与其我猜，不如给用户一个可校准的旋钮。
 */
enum class Sensitivity(val label: String, val riseFactor: Float, val minIntervalMs: Long, val requireRise: Boolean) {
    LOOSE("宽松", 0.5f, 800L, false),
    STANDARD("标准", 1.0f, 1200L, true),
    STRICT("严格", 1.6f, 1800L, true);

    companion object {
        private const val KEY = "shot_sensitivity"

        fun load(ctx: Context): Sensitivity {
            val v = ctx.getSharedPreferences("shotlab", Context.MODE_PRIVATE).getString(KEY, LOOSE.name)
            return runCatching { valueOf(v ?: STANDARD.name) }.getOrDefault(STANDARD)
        }

        fun save(ctx: Context, s: Sensitivity) {
            ctx.getSharedPreferences("shotlab", Context.MODE_PRIVATE).edit().putString(KEY, s.name).apply()
        }
    }
}
