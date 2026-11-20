package app.bball.court

import app.bball.court.core.NRect
import app.bball.court.core.ShotAggregator
import app.bball.court.core.ShotType
import app.bball.court.core.TrackletEngine
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

class ShotAggregatorTest {

    private val rim = NRect(0.625f, 0.322f, 0.663f, 0.352f)

    @Test
    fun realPcFixturesKeepValidatedShotResults() {
        val cases = listOf(
            Triple("candV3_one.json", 2, 1),
            Triple("candV3_two.json", 5, 2),
            Triple("candV3_three.json", 4, 2),
        )

        for ((fixture, expectedAttempts, expectedMade) in cases) {
            val parsed = loadFixture(fixture)
            val events = TrackletEngine.run(parsed.frames, rim, parsed.width, parsed.height)
            val summary = ShotAggregator.summarize(events)
            assertEquals("$fixture attempts", expectedAttempts, summary.attempts)
            assertEquals(
                "$fixture made: " + events.joinToString { "${it.releaseMs}:${it.made}" },
                expectedMade,
                summary.made,
            )
            assertEquals("$fixture event order", summary.events.sortedBy { it.releaseMs }, summary.events)
        }
    }

    @Test
    fun duplicateEventsAreCollapsedAndPreferAMake() {
        val miss = event(1_000, made = false, ShotType.TWO)
        val made = event(1_200, made = true, ShotType.TWO)
        val laterMiss = event(3_000, made = false, ShotType.THREE)

        val summary = ShotAggregator.summarize(listOf(laterMiss, miss, made))

        assertEquals(2, summary.attempts)
        assertEquals(1, summary.made)
        assertEquals(listOf(1_200L, 3_000L), summary.events.map { it.releaseMs })
        assertEquals(1, summary.twoAtt)
        assertEquals(1, summary.twoMade)
        assertEquals(1, summary.threeAtt)
        assertEquals(0, summary.threeMade)
    }

    @Test
    fun separateShotsAboutOneSecondApartAreKept() {
        val first = event(1_000, made = true, ShotType.TWO)
        val second = event(2_100, made = false, ShotType.TWO)

        val summary = ShotAggregator.summarize(listOf(first, second))

        assertEquals("1.1 秒间隔可能是两次连续快投", 2, summary.attempts)
        assertEquals(1, summary.made)
    }

    @Test
    fun uncalibratedAndroidFixtureEstimatesShotTypes() {
        val parsed = loadFixture("android_two_round1.json")
        val events = TrackletEngine.run(parsed.frames, rim, parsed.width, parsed.height)
        val summary = ShotAggregator.summarize(events)

        val debugEvents = events.joinToString { "${it.releaseMs}:${it.made}:${it.distM}:${it.type}" }
        assertEquals("unexpected attempts: $debugEvents", 5, summary.attempts)
        assertEquals("unexpected makes: $debugEvents", 2, summary.made)
        assertTrue(
            "uncalibrated shot types must be estimated: " +
                events.joinToString { "${it.releaseMs}:${it.type}:${it.distM}" },
            events.all { it.type != ShotType.UNKNOWN && it.distM == 0f },
        )
    }

    @Test
    fun streakAndIntervalUseChronologicalOrder() {
        val events = listOf(
            event(4_500, made = true, ShotType.THREE),
            event(1_000, made = true, ShotType.LAYUP),
            event(3_000, made = false, ShotType.TWO),
        )

        val summary = ShotAggregator.summarize(events)

        assertEquals(1, summary.bestStreak)
        assertEquals(1, summary.bestMissStreak)
        assertEquals(1_750, summary.avgIntervalMs)
    }

    private fun event(t: Long, made: Boolean, type: ShotType) = TrackletEngine.Event(
        releaseMs = t,
        resolveMs = t + 800,
        made = made,
        x = 0.5f,
        y = 0.4f,
        distM = 3.0f,
        type = type,
    )

    private fun loadFixture(name: String): ParsedFixture {
        val stream = checkNotNull(javaClass.getResourceAsStream("/fixtures/tracklet/$name")) {
            "missing fixture $name"
        }
        val root = stream.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        val width = root.get("w").asInt
        val height = root.get("h").asInt
        val frames = root.getAsJsonArray("frames").map { frameElement ->
            val frame = frameElement.asJsonObject
            val t = (frame.get("t").asDouble * 1000.0).roundToLong()
            val candidates = frame.getAsJsonArray("cands").map { candidateElement ->
                val candidate = candidateElement.asJsonArray
                val x1 = candidate[0].asFloat
                val y1 = candidate[1].asFloat
                val x2 = candidate[2].asFloat
                val y2 = candidate[3].asFloat
                TrackletEngine.Obs(
                    t = t,
                    x = (x1 + x2) / 2f,
                    y = (y1 + y2) / 2f,
                    conf = candidate[4].asFloat,
                )
            }
            val personElement = frame.get("person")
            val person = if (personElement == null || personElement.isJsonNull) null else {
                val box = personElement.asJsonArray
                NRect(box[0].asFloat, box[1].asFloat, box[2].asFloat, box[3].asFloat)
            }
            TrackletEngine.FrameObs(t, candidates, person, listOfNotNull(person))
        }
        return ParsedFixture(width, height, frames)
    }

    private data class ParsedFixture(
        val width: Int,
        val height: Int,
        val frames: List<TrackletEngine.FrameObs>,
    )
}
