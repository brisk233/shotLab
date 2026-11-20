package app.bball.court

import app.bball.court.core.NRect
import app.bball.court.core.ShotAggregator
import app.bball.court.core.TrackletEngine
import com.google.gson.JsonParser
import java.util.zip.GZIPInputStream
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullVideoMultisourceRegressionTest {

    @Test
    fun multisourceModelKeepsAllMadeShotsOnDeviceDump() {
        val resource = checkNotNull(
            javaClass.getResourceAsStream("/fixtures/tracklet/full_video_device_39_v77.json.gz"),
        )
        val root = GZIPInputStream(resource).bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject
        }
        val rimValues = root.getAsJsonArray("rim")
        val roughRim = NRect(
            rimValues[0].asFloat,
            rimValues[1].asFloat,
            rimValues[2].asFloat,
            rimValues[3].asFloat,
        )
        val frames = root.getAsJsonArray("frames").map { element ->
            val frame = element.asJsonObject
            val time = (frame.get("t").asDouble * 1000).roundToLong()
            val candidates = frame.getAsJsonArray("cands").map { candidateElement ->
                val candidate = candidateElement.asJsonArray
                TrackletEngine.Obs(
                    t = time,
                    x = (candidate[0].asFloat + candidate[2].asFloat) / 2f,
                    y = (candidate[1].asFloat + candidate[3].asFloat) / 2f,
                    conf = candidate[4].asFloat,
                )
            }
            val personElement = frame.get("person")
            val person = if (personElement == null || personElement.isJsonNull) null else {
                val box = personElement.asJsonArray
                NRect(box[0].asFloat, box[1].asFloat, box[2].asFloat, box[3].asFloat)
            }
            val persons = frame.getAsJsonArray("persons").map { personItem ->
                val box = personItem.asJsonArray
                NRect(box[0].asFloat, box[1].asFloat, box[2].asFloat, box[3].asFloat)
            }
            TrackletEngine.FrameObs(time, candidates, person, persons)
        }

        val result = TrackletEngine.runDetailed(
            frames,
            roughRim,
            root.get("w").asInt,
            root.get("h").asInt,
        )
        val summary = ShotAggregator.summarize(result.events)

        assertTrue("低分辨率机位记忆筐框应被穿筐轨迹校正", result.rimAdjustedPx > 0)
        assertEquals(
            listOf(
                0L, 3_432L, 5_676L, 9_372L, 11_946L, 13_332L, 15_708L, 18_216L,
                21_648L, 23_826L, 25_806L, 27_390L, 30_492L, 33_660L, 35_442L,
                37_092L, 38_478L, 40_986L, 45_342L, 47_586L, 50_622L, 52_206L,
                54_648L, 55_902L, 59_532L, 60_786L, 62_832L, 64_482L, 66_132L,
                68_706L, 74_382L, 77_286L, 86_592L, 93_786L, 98_406L, 101_046L,
                102_762L, 105_468L, 108_504L,
            ),
            summary.events.map { it.releaseMs },
        )
        assertEquals(39, summary.attempts)
        assertEquals(39, summary.made)
        assertEquals(14, summary.layupAtt)
        assertEquals(18, summary.twoAtt)
        assertEquals(7, summary.threeAtt)
        assertEquals(0, summary.ftAtt)
    }
}
