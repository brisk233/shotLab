package app.bball.court

import app.bball.court.core.ClipMaker
import app.bball.court.core.ShotEvent
import app.bball.court.core.ShotType
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipMakerTest {

    @Test
    fun highlightIncludesOnlyMadeShotsAndUsesTheRequestedWindow() {
        val made = shot(t = 5_000, made = true, makeMs = 6_500)
        val missed = shot(t = 10_000, made = false)

        val segments = ClipMaker.segmentsForShots(listOf(made, missed))

        assertEquals(1, segments.size)
        assertEquals(3_000L, segments.single().first)
        assertEquals(7_700L, segments.single().last)
    }

    @Test
    fun singleShotPreviewIncludesReleaseAndResolutionForMakesAndMisses() {
        val made = shot(t = 5_000, made = true, makeMs = 6_500, resolveMs = 6_400)
        val missed = shot(t = 5_000, made = false, resolveMs = 6_200)

        assertEquals(3_000L..7_700L, ClipMaker.segmentForShot(made))
        assertEquals(3_000L..7_400L, ClipMaker.segmentForShot(missed))
    }

    private fun shot(
        t: Long,
        made: Boolean,
        makeMs: Long = 0L,
        resolveMs: Long = 0L,
    ) = ShotEvent(
        startFrame = 0,
        tMs = t,
        type = ShotType.TWO,
        made = made,
        releaseX = 0.4f,
        releaseY = 0.3f,
        distanceM = 3.0f,
        releaseAngle = null,
        makeMs = makeMs,
        resolveMs = resolveMs,
    )
}
