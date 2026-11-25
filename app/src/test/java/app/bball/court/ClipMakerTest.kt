package app.bball.court

import app.bball.court.core.ClipMaker
import app.bball.court.core.ShotEvent
import app.bball.court.core.ShotType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun seekedClipsMuxOnlyVideoToAvoidOutOfOrderAudioTimestamps() {
        assertTrue(ClipMaker.shouldMuxTrack("video/hevc"))
        assertTrue(ClipMaker.shouldMuxTrack("video/avc"))
        assertFalse(ClipMaker.shouldMuxTrack("audio/mp4a-latm"))
    }

    @Test
    fun shiftedTimelinePreservesBFrameCompositionOrder() {
        val samplesUs = listOf(3_800_000L, 3_700_000L, 3_766_667L, 3_733_333L)
        val shifted = samplesUs.map { ClipMaker.shiftedPresentationUs(it, 3_700_000L, 0L) }

        assertEquals(listOf(100_000L, 0L, 66_667L, 33_333L), shifted)
    }

    @Test
    fun clipStartsAtTheFirstDecodableKeyframe() {
        val syncFlags = listOf(false, false, true, false, true)

        assertEquals(2, ClipMaker.firstSyncSampleIndex(syncFlags))
        assertEquals(-1, ClipMaker.firstSyncSampleIndex(listOf(false, false)))
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
