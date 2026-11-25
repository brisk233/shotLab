package app.bball.court

import app.bball.court.core.SceneCut
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneCutTest {

    @Test
    fun gateSuppressesRepeatedMotionSpikesWithinHalfASecond() {
        val dark = FloatArray(64) { 20f }
        val bright = FloatArray(64) { 220f }
        val gate = SceneCut.Gate()

        assertFalse(gate.update(0L, null, dark))
        assertTrue(gate.update(100L, dark, bright))
        assertFalse(gate.update(200L, bright, dark))
        assertFalse(gate.update(500L, dark, bright))
        assertTrue(gate.update(600L, bright, dark))
    }

    @Test
    fun smallLocalizedChangeDoesNotTriggerSceneCut() {
        val first = FloatArray(64) { 100f }
        val second = first.copyOf().also { signature ->
            repeat(20) { index -> signature[index] = 220f }
        }

        assertFalse(SceneCut.isCut(first, second))
    }
}
