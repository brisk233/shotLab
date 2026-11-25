package app.bball.court

import app.bball.court.core.DynamicRimCalibrator
import app.bball.court.core.NRect
import app.bball.court.core.RimCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DynamicRimCalibratorTest {

    @Test
    fun followsRimAcrossSmoothHandheldMotion() {
        val calibrator = DynamicRimCalibrator()
        val results = (0..6).map { frame ->
            val cx = 0.30f + frame * 0.03f
            val cy = 0.42f - frame * 0.01f
            calibrator.update(
                tMs = frame * 66L,
                candidates = listOf(RimCandidate(rect(cx, cy, 0.080f), 0.92f)),
            )
        }

        val last = assertNotNull(results.last()).let { results.last()!! }
        assertEquals(0.48f, last.rim.cx, 0.012f)
        assertEquals(0.36f, last.rim.cy, 0.012f)
        assertEquals(0.080f, last.rim.w, 0.012f)
    }

    @Test
    fun bridgesShortDetectionGapWithInterpolatedPosition() {
        val calibrator = DynamicRimCalibrator()
        calibrator.update(0L, listOf(RimCandidate(rect(0.30f, 0.40f, 0.08f), 0.95f)))
        calibrator.update(100L, listOf(RimCandidate(rect(0.32f, 0.40f, 0.08f), 0.95f)))

        val gap1 = calibrator.update(200L, emptyList())
        val gap2 = calibrator.update(300L, emptyList())
        calibrator.update(400L, listOf(RimCandidate(rect(0.38f, 0.40f, 0.08f), 0.95f)))

        assertNotNull(gap1)
        assertNotNull(gap2)
        assertEquals(0.34f, gap1!!.rim.cx, 0.025f)
        assertEquals(0.36f, gap2!!.rim.cx, 0.025f)
        assertEquals(0.45f, gap2.confidence, 0.15f)
    }

    @Test
    fun rejectsSingleFrameTeleportWithoutSceneCut() {
        val calibrator = DynamicRimCalibrator()
        calibrator.update(0L, listOf(RimCandidate(rect(0.30f, 0.40f, 0.08f), 0.95f)))
        calibrator.update(66L, listOf(RimCandidate(rect(0.31f, 0.40f, 0.08f), 0.95f)))

        val result = calibrator.update(
            132L,
            listOf(RimCandidate(rect(0.82f, 0.18f, 0.08f), 0.99f)),
        )

        assertNotNull(result)
        assertEquals(0.32f, result!!.rim.cx, 0.04f)
        assertEquals(0.40f, result.rim.cy, 0.04f)
    }

    @Test
    fun nearbyBallRackBelowHoopDoesNotCaptureTheTracker() {
        val calibrator = DynamicRimCalibrator()
        calibrator.update(0L, listOf(RimCandidate(wideRect(0.82f, 0.39f, 0.06f), 0.95f)))
        calibrator.update(66L, listOf(RimCandidate(wideRect(0.82f, 0.39f, 0.06f), 0.95f)))

        val tracked = calibrator.update(
            132L,
            listOf(
                RimCandidate(rect(0.79f, 0.50f, 0.05f), 0.99f),
                RimCandidate(wideRect(0.83f, 0.40f, 0.06f), 0.42f),
            ),
        )

        assertNotNull(tracked)
        assertEquals(0.40f, tracked!!.rim.cy, 0.025f)
        assertEquals(0.83f, tracked.rim.cx, 0.025f)
    }

    @Test
    fun sceneCutDropsOldPredictionAndReacquiresNewRim() {
        val calibrator = DynamicRimCalibrator()
        calibrator.update(0L, listOf(RimCandidate(rect(0.30f, 0.40f, 0.08f), 0.95f)))
        calibrator.update(66L, listOf(RimCandidate(rect(0.31f, 0.40f, 0.08f), 0.95f)))

        val lost = calibrator.update(132L, emptyList(), sceneCut = true)
        val reacquired = calibrator.update(
            200L,
            listOf(RimCandidate(rect(0.82f, 0.18f, 0.08f), 0.99f)),
        )

        assertNull(lost)
        assertNotNull(reacquired)
        assertEquals(0.82f, reacquired!!.rim.cx, 0.01f)
        assertEquals(0.18f, reacquired.rim.cy, 0.01f)
    }

    @Test
    fun sceneCutRejectsReacquisitionSmallerThanTrustedRimFloor() {
        val calibrator = DynamicRimCalibrator(minimumWidth = 0.040f)
        calibrator.update(0L, listOf(RimCandidate(rect(0.30f, 0.40f, 0.08f), 0.95f)))

        val lost = calibrator.update(
            100L,
            listOf(RimCandidate(rect(0.80f, 0.20f, 0.019f), 0.99f)),
            sceneCut = true,
        )
        val reacquired = calibrator.update(
            200L,
            listOf(RimCandidate(rect(0.80f, 0.20f, 0.065f), 0.80f)),
        )

        assertNull(lost)
        assertNotNull(reacquired)
        assertEquals(0.80f, reacquired!!.rim.cx, 0.01f)
    }

    @Test
    fun sceneCutKeepsNearbyHoopInsteadOfLargeDistractor() {
        val calibrator = DynamicRimCalibrator()
        calibrator.update(0L, listOf(RimCandidate(rect(0.50f, 0.30f, 0.08f), 0.95f)))
        calibrator.update(66L, listOf(RimCandidate(rect(0.51f, 0.30f, 0.08f), 0.95f)))

        val tracked = calibrator.update(
            132L,
            listOf(
                RimCandidate(rect(0.86f, 0.20f, 0.09f), 0.99f),
                RimCandidate(rect(0.55f, 0.30f, 0.08f), 0.42f),
            ),
            sceneCut = true,
        )

        assertNotNull(tracked)
        assertEquals(0.55f, tracked!!.rim.cx, 0.02f)
        assertEquals(0.30f, tracked.rim.cy, 0.02f)
    }

    @Test
    fun longDetectionLossResetsAndAllowsFreshReacquisition() {
        val calibrator = DynamicRimCalibrator()
        calibrator.update(0L, listOf(RimCandidate(rect(0.30f, 0.40f, 0.08f), 0.95f)))
        calibrator.update(66L, listOf(RimCandidate(rect(0.31f, 0.40f, 0.08f), 0.95f)))
        assertNull(calibrator.update(1_000L, emptyList()))

        val reacquired = calibrator.update(
            1_100L,
            listOf(RimCandidate(rect(0.80f, 0.16f, 0.08f), 0.92f)),
        )

        assertNotNull(reacquired)
        assertEquals(0.80f, reacquired!!.rim.cx, 0.01f)
    }

    @Test
    fun smoothsSingleFrameBoxSizeNoise() {
        val calibrator = DynamicRimCalibrator()
        calibrator.update(0L, listOf(RimCandidate(rect(0.30f, 0.40f, 0.08f), 0.90f)))
        calibrator.update(66L, listOf(RimCandidate(rect(0.30f, 0.40f, 0.08f), 0.90f)))

        val noisy = calibrator.update(
            132L,
            listOf(RimCandidate(rect(0.30f, 0.40f, 0.16f), 0.90f)),
        )

        assertNotNull(noisy)
        assertEquals(0.11f, noisy!!.rim.w, 0.035f)
    }

    private fun rect(cx: Float, cy: Float, width: Float): NRect {
        val height = width * 0.40f
        return NRect(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)
    }

    private fun wideRect(cx: Float, cy: Float, width: Float): NRect {
        val height = width / 2.5f
        return NRect(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)
    }
}
