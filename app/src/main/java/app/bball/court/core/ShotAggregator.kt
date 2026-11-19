package app.bball.court.core

/**
 * 轨迹事件的唯一统计出口。
 *
 * 检测与轨迹阶段可以保留多个调试来源，但最终出手、命中、类型和连续记录只能从这里进入报告。
 * 汇总前先按出手时间排序，并合并 1.2 秒内的重复轨迹；同一球同时被判中和不中时保留命中。
 */
object ShotAggregator {

    data class Summary(
        val events: List<TrackletEngine.Event>,
        val attempts: Int,
        val made: Int,
        val layupMade: Int,
        val layupAtt: Int,
        val twoMade: Int,
        val twoAtt: Int,
        val threeMade: Int,
        val threeAtt: Int,
        val ftMade: Int,
        val ftAtt: Int,
        val bestStreak: Int,
        val bestMissStreak: Int,
        val avgIntervalMs: Long,
    )

    fun summarize(rawEvents: List<TrackletEngine.Event>): Summary {
        val events = deduplicate(rawEvents)
        var made = 0
        var layupMade = 0
        var layupAtt = 0
        var twoMade = 0
        var twoAtt = 0
        var threeMade = 0
        var threeAtt = 0
        var ftMade = 0
        var ftAtt = 0

        for (event in events) {
            if (event.made) made++
            when (event.type) {
                ShotType.LAYUP -> {
                    layupAtt++
                    if (event.made) layupMade++
                }
                ShotType.TWO -> {
                    twoAtt++
                    if (event.made) twoMade++
                }
                ShotType.THREE -> {
                    threeAtt++
                    if (event.made) threeMade++
                }
                ShotType.FREE_THROW -> {
                    ftAtt++
                    if (event.made) ftMade++
                }
            }
        }

        var streak = 0
        var bestStreak = 0
        var missRun = 0
        var bestMissStreak = 0
        var gapsTotal = 0L
        for (i in events.indices) {
            val event = events[i]
            if (event.made) {
                streak++
                missRun = 0
                if (streak > bestStreak) bestStreak = streak
            } else {
                missRun++
                streak = 0
                if (missRun > bestMissStreak) bestMissStreak = missRun
            }
            if (i > 0) gapsTotal += event.releaseMs - events[i - 1].releaseMs
        }
        val avgIntervalMs = if (events.size > 1) gapsTotal / (events.size - 1) else 0L

        return Summary(
            events = events,
            attempts = events.size,
            made = made,
            layupMade = layupMade,
            layupAtt = layupAtt,
            twoMade = twoMade,
            twoAtt = twoAtt,
            threeMade = threeMade,
            threeAtt = threeAtt,
            ftMade = ftMade,
            ftAtt = ftAtt,
            bestStreak = bestStreak,
            bestMissStreak = bestMissStreak,
            avgIntervalMs = avgIntervalMs,
        )
    }

    private fun deduplicate(rawEvents: List<TrackletEngine.Event>): List<TrackletEngine.Event> {
        val sorted = rawEvents.sortedBy { it.releaseMs }
        val unique = ArrayList<TrackletEngine.Event>(sorted.size)
        for (event in sorted) {
            val previous = unique.lastOrNull()
            if (previous != null && event.releaseMs - previous.releaseMs < DUPLICATE_WINDOW_MS) {
                if (event.made && !previous.made) unique[unique.lastIndex] = event
                continue
            }
            unique.add(event)
        }
        return unique
    }

    private const val DUPLICATE_WINDOW_MS = 1_200L
}
