package app.bball.court.core

import android.content.Context

/** 四点标定结果持久化：重开 App 不用重新点。 */
object CalibStore {
    private const val KEY = "court_points"

    fun save(ctx: Context, pts: List<Pair<Float, Float>>) {
        val s = pts.joinToString(";") { it.first.toString() + "," + it.second.toString() }
        ctx.getSharedPreferences("shotlab", Context.MODE_PRIVATE).edit().putString(KEY, s).apply()
    }

    fun load(ctx: Context): CourtHomography? {
        val s = ctx.getSharedPreferences("shotlab", Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        val pts = s.split(";").mapNotNull { one ->
            val p = one.split(",")
            if (p.size == 2) (p[0].toFloatOrNull() to p[1].toFloatOrNull())?.let {
                if (it.first != null && it.second != null) it.first!! to it.second!! else null
            } else null
        }
        if (pts.size != 4) return null
        return CourtHomography.solve(pts)
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences("shotlab", Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
