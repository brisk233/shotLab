package app.bball.court.core

/**
 * 轨迹调试快照：保存轨迹引擎实际收到的候选点、人体框和输出事件。
 * 它使 Android 端结果可以直接交给 PC 引擎复算，不再依赖旧版缺失候选点的 track_dump。
 */
object TrackletDump {

    fun build(
        sourceFps: Double,
        analysisWidth: Int,
        analysisHeight: Int,
        rim: NRect,
        correctedRim: NRect? = null,
        rimTrack: List<RimTrackPoint> = emptyList(),
        frames: List<TrackletEngine.FrameObs>,
        events: List<TrackletEngine.Event>,
        rimAdjustedPx: Int = 0,
    ): String = buildString {
        append("{\"version\":1")
        append(",\"sourceFps\":").append(sourceFps)
        append(",\"analysisWidth\":").append(analysisWidth)
        append(",\"analysisHeight\":").append(analysisHeight)
        append(",\"w\":").append(analysisWidth)
        append(",\"h\":").append(analysisHeight)
        append(",\"rim\":").appendRect(rim)
        append(",\"correctedRim\":")
        appendNullableRect(correctedRim)
        append(",\"rimTrack\":[")
        rimTrack.forEachIndexed { index, point ->
            if (index > 0) append(',')
            append("{\"tMs\":").append(point.tMs)
            append(",\"rim\":").appendRect(point.rim)
            append(",\"confidence\":").append(point.confidence)
            append(",\"segment\":").append(point.segment)
            append('}')
        }
        append(']')
        append(",\"rimAdjustedPx\":").append(rimAdjustedPx)
        append(",\"frames\":[")
        frames.forEachIndexed { frameIndex, frame ->
            if (frameIndex > 0) append(',')
            append("{\"t\":").append(frame.t / 1000.0)
            append(",\"segment\":").append(frame.segment)
            append(",\"cands\":[")
            frame.cands.forEachIndexed { candidateIndex, candidate ->
                if (candidateIndex > 0) append(',')
                append('[')
                append((candidate.x - DUMP_HALF_BOX).coerceIn(0f, 1f)).append(',')
                append((candidate.y - DUMP_HALF_BOX).coerceIn(0f, 1f)).append(',')
                append((candidate.x + DUMP_HALF_BOX).coerceIn(0f, 1f)).append(',')
                append((candidate.y + DUMP_HALF_BOX).coerceIn(0f, 1f)).append(',')
                append(candidate.conf)
                append(']')
            }
            append("],\"person\":")
            appendNullableRect(frame.person)
            append(",\"persons\":[")
            frame.persons.forEachIndexed { personIndex, person ->
                if (personIndex > 0) append(',')
                appendRect(person)
            }
            append("]}")
        }
        append("],\"events\":[")
        events.forEachIndexed { eventIndex, event ->
            if (eventIndex > 0) append(',')
            append("{\"releaseMs\":").append(event.releaseMs)
            append(",\"resolveMs\":").append(event.resolveMs)
            append(",\"made\":").append(event.made)
            append(",\"x\":").append(event.x)
            append(",\"y\":").append(event.y)
            append(",\"distM\":").append(event.distM)
            append(",\"type\":\"").append(event.type.name).append('"')
            append(",\"segment\":").append(event.segment).append('}')
        }
        append("]}")
    }

    private fun StringBuilder.appendNullableRect(rect: NRect?) {
        if (rect == null) append("null") else appendRect(rect)
    }

    private fun StringBuilder.appendRect(rect: NRect) {
        append('[')
        append(rect.left).append(',')
        append(rect.top).append(',')
        append(rect.right).append(',')
        append(rect.bottom)
        append(']')
    }

    private const val DUMP_HALF_BOX = 0.005f
}
