package app.bball.court

import app.bball.court.core.VideoFrameClock
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFrameClockTest {

    @Test
    fun invalidFrameRatesFallBackToThirty() {
        assertEquals(30.0, VideoFrameClock.sanitizeFps(null), 0.0)
        assertEquals(30.0, VideoFrameClock.sanitizeFps(0.0), 0.0)
        assertEquals(30.0, VideoFrameClock.sanitizeFps(500.0), 0.0)
    }

    @Test
    fun sixtyFpsUsesSourceFrameIndexNotThirty() {
        assertEquals(6, VideoFrameClock.frameIndexAt(100L, 60.0))
        assertEquals(20, VideoFrameClock.frameIndexAt(333L, 60.0))
    }

    @Test
    fun fractionalFrameRateStaysAlignedOverTime() {
        assertEquals(30, VideoFrameClock.frameIndexAt(1_000L, 29.97))
        assertEquals(0, VideoFrameClock.frameIndexAt(-50L, 30.0))
    }
}
