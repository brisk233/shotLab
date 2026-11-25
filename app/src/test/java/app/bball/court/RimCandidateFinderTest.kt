package app.bball.court

import app.bball.court.core.RimCandidateFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RimCandidateFinderTest {

    @Test
    fun rimShapedOrangeComponentOutranksFloorAndVerticalDistractors() {
        val width = 160
        val height = 240
        val pixels = IntArray(width * height) { rgb(232, 232, 232) }

        paintRect(pixels, width, 111, 94, 20, 6, rgb(190, 72, 30))
        paintRect(pixels, width, 0, 210, 160, 30, rgb(205, 126, 55))
        paintRect(pixels, width, 38, 72, 5, 38, rgb(210, 110, 40))

        val candidates = RimCandidateFinder.findCandidates(pixels, width, height)

        assertTrue(candidates.isNotEmpty())
        val best = candidates.first()
        assertEquals(0.756f, best.rect.cx, 0.035f)
        assertEquals(0.404f, best.rect.cy, 0.035f)
        assertTrue(best.rect.w / best.rect.h > 1.2f)
    }

    @Test
    fun largeOrangeFloorBlockProducesNoRimCandidate() {
        val width = 160
        val height = 240
        val pixels = IntArray(width * height) { rgb(235, 235, 235) }
        paintRect(pixels, width, 12, 135, 136, 80, rgb(210, 128, 55))

        val candidates = RimCandidateFinder.findCandidates(pixels, width, height)

        assertFalse(candidates.any { it.rect.cy > 0.55f })
    }

    @Test
    fun saturatedCyanBoardIsNotTreatedAsAnOrangeRim() {
        val width = 160
        val height = 240
        val pixels = IntArray(width * height) { rgb(235, 235, 235) }
        paintRect(pixels, width, 111, 94, 20, 6, rgb(90, 180, 190))

        val candidates = RimCandidateFinder.findCandidates(pixels, width, height)

        assertTrue(
            "青色广告牌不应进入橙色篮筐候选，actual=$candidates",
            candidates.none { kotlin.math.abs(it.rect.cx - 0.756f) < 0.05f },
        )
    }

    private fun paintRect(
        pixels: IntArray,
        width: Int,
        left: Int,
        top: Int,
        rectWidth: Int,
        rectHeight: Int,
        color: Int,
    ) {
        for (y in top until top + rectHeight) {
            for (x in left until left + rectWidth) {
                pixels[y * width + x] = color
            }
        }
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
