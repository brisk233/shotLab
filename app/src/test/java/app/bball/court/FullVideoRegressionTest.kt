package app.bball.court

import app.bball.court.core.NRect
import app.bball.court.core.ShotAggregator
import app.bball.court.core.TrackletEngine
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream
import kotlin.math.roundToLong

class FullVideoRegressionTest {

    @Test
    fun deviceTrackletDumpCorrectsRimAndKeepsAllMadeShots() {
        val resource = checkNotNull(
            javaClass.getResourceAsStream("/fixtures/tracklet/full_video_device_15fps.json.gz"),
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

        assertTrue("错误的机位记忆筐框应被穿筐轨迹校正", result.rimAdjustedPx > 0)
        val summary = ShotAggregator.summarize(result.events)
        val expectedReleases = listOf(
            4_686L, 11_946L, 13_266L, 15_774L, 18_216L, 21_648L, 23_826L, 25_806L,
            27_390L, 30_492L, 35_442L, 37_092L, 38_478L, 40_986L, 45_276L, 47_586L,
            49_962L, 52_272L, 54_582L, 55_902L, 59_532L, 60_786L, 63_162L, 64_746L,
            68_706L, 72_336L, 74_382L, 77_286L, 85_008L, 86_592L, 93_786L, 98_406L,
            102_762L, 108_438L,
        )
        assertEquals(expectedReleases, summary.events.map { it.releaseMs })
        assertEquals(expectedReleases.size, summary.made)
        assertEquals(10, summary.layupAtt)
        assertEquals(19, summary.twoAtt)
        assertEquals(5, summary.threeAtt)
        assertEquals(0, summary.ftAtt)
    }
}
