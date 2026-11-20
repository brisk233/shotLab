package app.bball.court

import app.bball.court.core.NRect
import app.bball.court.core.ShotType
import app.bball.court.core.TrackletDump
import app.bball.court.core.TrackletEngine
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackletDumpTest {

    @Test
    fun dumpContainsTheActualTrackletInputsAndEvents() {
        val person = NRect(0.1f, 0.2f, 0.3f, 0.8f)
        val frames = listOf(
            TrackletEngine.FrameObs(
                t = 100L,
                cands = listOf(TrackletEngine.Obs(100L, 0.5f, 0.4f, 0.91f)),
                person = person,
                persons = listOf(person),
            ),
        )
        val events = listOf(
            TrackletEngine.Event(
                releaseMs = 100L,
                resolveMs = 900L,
                made = true,
                x = 0.5f,
                y = 0.4f,
                distM = 3.2f,
                type = ShotType.TWO,
            ),
        )

        val root = JsonParser.parseString(
            TrackletDump.build(
                sourceFps = 60.0,
                analysisWidth = 720,
                analysisHeight = 405,
                rim = NRect(0.625f, 0.322f, 0.663f, 0.352f),
                correctedRim = NRect(0.620f, 0.300f, 0.658f, 0.330f),
                frames = frames,
                events = events,
                rimAdjustedPx = 56,
            ),
        ).asJsonObject

        assertEquals(1, root.get("version").asInt)
        assertEquals(60.0, root.get("sourceFps").asDouble, 0.0)
        assertEquals(720, root.get("w").asInt)
        assertEquals(405, root.get("h").asInt)
        assertEquals(56, root.get("rimAdjustedPx").asInt)
        assertEquals(0.625, root.getAsJsonArray("rim")[0].asDouble, 0.00001)
        assertEquals(0.620, root.getAsJsonArray("correctedRim")[0].asDouble, 0.00001)
        assertEquals(0.1, root.getAsJsonArray("frames")[0].asJsonObject.get("t").asDouble, 0.00001)
        val candidate = root.getAsJsonArray("frames")[0].asJsonObject.getAsJsonArray("cands")[0].asJsonArray
        assertEquals(0.5, (candidate[0].asDouble + candidate[2].asDouble) / 2.0, 0.00001)
        assertEquals(0.91, candidate[4].asDouble, 0.00001)
        assertEquals(0.1, root.getAsJsonArray("frames")[0].asJsonObject
            .getAsJsonArray("persons")[0].asJsonArray[0].asDouble, 0.00001)
        assertEquals(100, root.getAsJsonArray("events")[0].asJsonObject
            .get("releaseMs").asLong)
        assertTrue(root.getAsJsonArray("events")[0].asJsonObject.get("made").asBoolean)
    }
}
