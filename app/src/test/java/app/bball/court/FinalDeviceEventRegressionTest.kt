package app.bball.court

import app.bball.court.core.ShotAggregator
import app.bball.court.core.ShotType
import app.bball.court.core.TrackletEngine
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalDeviceEventRegressionTest {

    @Test
    fun optimizedReleaseKeepsValidatedDeviceResult() {
        val text = checkNotNull(
            javaClass.getResourceAsStream("/fixtures/events/final34_v77.json"),
        ).bufferedReader().use { it.readText() }
        val events = JsonParser.parseString(text).asJsonArray.map { item ->
            val event = item.asJsonObject
            val release = event.get("releaseMs").asLong
            TrackletEngine.Event(
                releaseMs = release,
                resolveMs = release + 1_000L,
                made = event.get("made").asBoolean,
                x = event.get("x").asFloat,
                y = event.get("y").asFloat,
                distM = 0f,
                type = ShotType.valueOf(event.get("type").asString),
            )
        }

        val summary = ShotAggregator.summarize(events)

        assertEquals(34, summary.attempts)
        assertEquals(34, summary.made)
        assertEquals(11, summary.layupAtt)
        assertEquals(17, summary.twoAtt)
        assertEquals(6, summary.threeAtt)
        assertEquals(0, summary.ftAtt)
        assertTrue(summary.events.all { it.made })
    }
}
