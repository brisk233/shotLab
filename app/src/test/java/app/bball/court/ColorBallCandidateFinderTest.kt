package app.bball.court

import app.bball.court.core.ColorBallCandidateFinder
import app.bball.court.core.NRect
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorBallCandidateFinderTest {

    private val rim = NRect(0.705f, 0.4134f, 0.7533f, 0.4342f)

    @Test
    fun findsOrangeBallDuringTheOpeningFlight() {
        val (pixels, width, height) = image("/fixtures/dynamic_rim/frame_00.ppm.gz")
        val candidates = ColorBallCandidateFinder().findCandidates(
            pixels,
            width,
            height,
            rim,
        )

        assertTrue("opening flight ball must be found; actual=$candidates", candidates.isNotEmpty())
        val ball = candidates.minByOrNull { candidate ->
            abs(candidate.rect.cx - 0.679f) + abs(candidate.rect.cy - 0.215f)
        }
        assertTrue(
            "closest candidate should match the actual ball; actual=$candidates",
            ball != null &&
                abs(ball.rect.cx - 0.679f) + abs(ball.rect.cy - 0.215f) < 0.055f,
        )
    }

    @Test
    fun repeatedBallRackCandidatesAreSuppressedAsStaticObjects() {
        val (pixels, width, height) = image("/fixtures/dynamic_rim/device_frame_14_0.ppm.gz")
        val finder = ColorBallCandidateFinder()
        var last = emptyList<app.bball.court.core.ColorBallCandidate>()

        repeat(4) {
            last = finder.findCandidates(pixels, width, height, rim)
        }

        assertEquals("a stationary orange rack should not keep producing ball points", 0, last.size)
    }

    @Test
    fun bluishPurpleComponentIsNotAcceptedAsBasketball() {
        val width = 160
        val height = 240
        val pixels = IntArray(width * height) { rgb(235, 235, 235) }
        paintRect(pixels, width, 40, 60, 12, 12, rgb(100, 50, 107))

        val candidates = ColorBallCandidateFinder().findCandidates(
            pixels,
            width,
            height,
            rim,
        )

        assertEquals("bluish purple must not enter the orange ball mask", 0, candidates.size)
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

    private fun image(name: String): Triple<IntArray, Int, Int> {
        val data = checkNotNull(javaClass.getResourceAsStream(name)) { name }.use { input ->
            GZIPInputStream(input).use { gzip ->
                ByteArrayOutputStream().use { out ->
                    gzip.copyTo(out)
                    out.toByteArray()
                }
            }
        }
        var offset = 0
        fun token(): String {
            while (offset < data.size && data[offset].toInt().toChar().isWhitespace()) offset++
            val start = offset
            while (offset < data.size && !data[offset].toInt().toChar().isWhitespace()) offset++
            return String(data, start, offset - start, Charsets.US_ASCII)
        }
        assertEquals("P6", token())
        val width = token().toInt()
        val height = token().toInt()
        assertEquals("255", token())
        while (offset < data.size && data[offset].toInt().toChar().isWhitespace()) offset++
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val r = data[offset++].toInt() and 0xFF
            val g = data[offset++].toInt() and 0xFF
            val b = data[offset++].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Triple(pixels, width, height)
    }
}
