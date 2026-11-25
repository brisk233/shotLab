package app.bball.court.core

import android.content.Context

/**
 * 记住"机位 → 筐框"。同一个机位（同一部手机、同一个支架位置）下次拍摄，
 * 直接自动套用上次的框，用户不用再拖。
 *
 * 机位指纹 = 首帧的 8×8 亮度网格（SceneCut.signature 已有），按曼哈顿距离匹配。
 * 这是自动篮筐定位之外的第二层便利机制：
 * 用户只需在**新机位**设一次，之后全自动。
 */
object RimStore {
    private const val KEY = "rim_store_v1"
    private const val MAX_ENTRIES = 6

    private fun loadRaw(ctx: Context): MutableList<String> {
        val s = ctx.getSharedPreferences("shotlab", Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return if (s.isBlank()) mutableListOf() else s.split("|").toMutableList()
    }

    /** 存一条：sig 用逗号分隔的 64 个亮度值 */
    fun save(ctx: Context, sig: FloatArray, rim: NRect, manual: Boolean = true) {
        val sigStr = sig.joinToString(",") { it.toInt().toString() }
        val rimStr = listOf(rim.left, rim.top, rim.right, rim.bottom).joinToString(",")
        val entry = sigStr + ";" + rimStr + ";" + (if (manual) "1" else "0")
        val list = loadRaw(ctx)
        list.add(0, entry)
        while (list.size > MAX_ENTRIES) list.removeAt(list.size - 1)
        ctx.getSharedPreferences("shotlab", Context.MODE_PRIVATE).edit().putString(KEY, list.joinToString("|")).apply()
    }

    /** 最近一次用过的筐框（不校验机位），用于给新机位做候选。 */
    fun lastUsed(ctx: Context): NRect? {
        for (e in loadRaw(ctx)) {
            val parts = e.split(";")
            if (parts.size < 2) continue
            val r = parts[1].split(",").mapNotNull { it.toFloatOrNull() }
            if (r.size == 4) return NRect(r[0], r[1], r[2], r[3])
        }
        return null
    }

    /** 已存机位：[序号] → 筐框（序号用于删除）。 */
    fun list(ctx: Context): List<NRect> {
        val out = ArrayList<NRect>()
        for (e in loadRaw(ctx)) {
            val parts = e.split(";")
            if (parts.size < 2) continue
            val r = parts[1].split(",").mapNotNull { it.toFloatOrNull() }
            if (r.size == 4) out.add(NRect(r[0], r[1], r[2], r[3]))
        }
        return out
    }

    /** 删除第 index 个机位记录。 */
    fun deleteAt(ctx: Context, index: Int) {
        val list = loadRaw(ctx)
        if (index in list.indices) {
            list.removeAt(index)
            ctx.getSharedPreferences("shotlab", Context.MODE_PRIVATE).edit()
                .putString(KEY, list.joinToString("|")).apply()
        }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences("shotlab", Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    /** 找最相近的机位；距离超阈值就不算匹配 */
    fun find(ctx: Context, sig: FloatArray, maxMeanDist: Float = 14f): NRect? {
        var best: NRect? = null
        var bestD = Float.MAX_VALUE
        for (e in loadRaw(ctx)) {
            val parts = e.split(";")
            if (parts.size < 2) continue
            if (parts.size >= 3 && parts[2] != "1") continue
            val s = parts[0].split(",").mapNotNull { it.toFloatOrNull() }
            if (s.size != sig.size) continue
            var d = 0f
            for (i in sig.indices) d += kotlin.math.abs(s[i] - sig[i])
            d /= sig.size                       // 平均绝对差，避免维度越多阈值越难满足
            if (d < bestD) {
                bestD = d
                val r = parts[1].split(",").mapNotNull { it.toFloatOrNull() }
                if (r.size == 4) best = NRect(r[0], r[1], r[2], r[3])
            }
        }
        android.util.Log.i("ShotLab", "rimStore.find best=" + bestD + " thr=" + maxMeanDist + " hit=" + (bestD <= maxMeanDist))
        return if (bestD <= maxMeanDist) best else null
    }
}
