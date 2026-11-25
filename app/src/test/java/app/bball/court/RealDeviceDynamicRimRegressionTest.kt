package app.bball.court

import app.bball.court.core.NRect
import app.bball.court.core.RimTrackPoint
import app.bball.court.core.ShotType
import app.bball.court.core.TrackletEngine
import com.google.gson.JsonParser
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealDeviceDynamicRimRegressionTest {

    @Test
    fun realDeviceTraceWithMovingRimRecordsOneMakeAndTwoMisses() {
        val text = checkNotNull(
            javaClass.getResourceAsStream("/fixtures/dynamic_rim/device_tracklet_v78.json.gz"),
        ).use { input ->
            GZIPInputStream(input).bufferedReader().use { it.readText() }
        }
        val root = JsonParser.parseString(text).asJsonObject
        val rimBox = root.getAsJsonArray("rim")
        val rim = NRect(
            rimBox[0].asFloat,
            rimBox[1].asFloat,
            rimBox[2].asFloat,
            rimBox[3].asFloat,
        )
        val rimTrack = root.getAsJsonArray("rimTrack").map { element ->
            val point = element.asJsonObject
            val box = point.getAsJsonArray("rim")
            RimTrackPoint(
                tMs = point.get("tMs").asLong,
                rim = NRect(box[0].asFloat, box[1].asFloat, box[2].asFloat, box[3].asFloat),
                confidence = point.get("confidence").asFloat,
                segment = point.get("segment").asInt,
            )
        }
        val frames = root.getAsJsonArray("frames").map { element ->
            val frame = element.asJsonObject
            val timeMs = (frame.get("t").asDouble * 1000.0).toLong()
            val segment = frame.get("segment").asInt
            val candidates = frame.getAsJsonArray("cands").map { candidateElement ->
                val candidate = candidateElement.asJsonArray
                val x1 = candidate[0].asFloat
                val y1 = candidate[1].asFloat
                val x2 = candidate[2].asFloat
                val y2 = candidate[3].asFloat
                TrackletEngine.Obs(
                    t = timeMs,
                    x = (x1 + x2) / 2f,
                    y = (y1 + y2) / 2f,
                    conf = candidate[4].asFloat,
                    segment = segment,
                )
            }
            val personElement = frame.get("person")
            val person = if (personElement == null || personElement.isJsonNull) {
                null
            } else {
                val box = personElement.asJsonArray
                NRect(box[0].asFloat, box[1].asFloat, box[2].asFloat, box[3].asFloat)
            }
            val persons = frame.getAsJsonArray("persons").map { personItem ->
                val box = personItem.asJsonArray
                NRect(box[0].asFloat, box[1].asFloat, box[2].asFloat, box[3].asFloat)
            }
            TrackletEngine.FrameObs(timeMs, candidates, person, persons, segment)
        }

        val result = TrackletEngine.runDetailed(
            frames = frames,
            rim = rim,
            frameW = root.get("w").asInt,
            frameH = root.get("h").asInt,
            rimTrack = rimTrack,
        )

        assertEquals(3, result.events.size)
        assertEquals(listOf(true, false, false), result.events.map { it.made })
        assertEquals(listOf(0L, 6_006L, 12_144L), result.events.map { it.releaseMs })
        assertTrue(result.events.all { it.type == ShotType.THREE })
    }
}
