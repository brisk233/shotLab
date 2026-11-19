package app.bball.court

import app.bball.court.core.NPt
import app.bball.court.core.NRect
import app.bball.court.core.RoiRefinementPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoiRefinementPolicyTest {

    private val rim = NRect(0.625f, 0.322f, 0.663f, 0.352f)

    @Test
    fun lowConfidenceBallNearRimNeedsRefinement() {
        assertTrue(RoiRefinementPolicy.shouldRun(NPt(0.64f, 0.34f), 0.35f, rim))
    }

    @Test
    fun confidentBallNearRimSkipsDuplicateInference() {
        assertFalse(RoiRefinementPolicy.shouldRun(NPt(0.64f, 0.34f), 0.80f, rim))
    }

    @Test
    fun ballOutsideRimRoiSkipsRefinement() {
        assertFalse(RoiRefinementPolicy.shouldRun(NPt(0.10f, 0.80f), 0.10f, rim))
    }

    @Test
    fun missingSampleUsesLastKnownPointForRecovery() {
        assertTrue(RoiRefinementPolicy.shouldRun(NPt(0.62f, 0.35f), 0f, rim))
    }
}
