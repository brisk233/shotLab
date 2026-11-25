package app.bball.court

import app.bball.court.core.DynamicRimCalibrator
import app.bball.court.core.RimCandidateFinder
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicRimVideoRegressionTest {

    @Test
    fun tracksHandheldRimAcrossRealVideoFrames() {
        val expected = listOf(
            0.729f to 0.420f,
            0.729f to 0.430f,
            0.808f to 0.431f,
            0.932f to 0.427f,
            0.983f to 0.425f,
            0.983f to 0.424f,
            0.978f to 0.426f,
            0.951f to 0.430f,
            0.947f to 0.429f,
            0.937f to 0.429f,
            0.899f to 0.422f,
            0.877f to 0.417f,
            0.825f to 0.388f,
            0.819f to 0.390f,
            0.840f to 0.393f,
            0.882f to 0.400f,
        )
        val calibrator = DynamicRimCalibrator()

        expected.forEachIndexed { index, (cx, cy) ->
            val (pixels, width, height) = image("/fixtures/dynamic_rim/frame_%02d.ppm.gz".format(index))
            val candidates = RimCandidateFinder.findCandidates(pixels, width, height)
            val tracked = calibrator.update(index * 1_000L, candidates)

            assertNotNull("frame $index should have a tracked rim; candidates=$candidates", tracked)
            if (index == 0) {
                assertTrue("bootstrap confidence should enable automatic analysis", tracked!!.confidence >= 0.34f)
            }
            val error = abs(tracked!!.rim.cx - cx) + abs(tracked.rim.cy - cy)
            assertTrue(
                "frame $index center error=$error tracked=${tracked.rim} expected=($cx,$cy) candidates=$candidates",
                error < 0.13f,
            )
        }
    }

    @Test
    fun lateRimFramesPreferHoopOverBallRack() {
        val expected = listOf(
            "/fixtures/dynamic_rim/frame_12.ppm.gz" to (12_000L to (0.825f to 0.388f)),
            "/fixtures/dynamic_rim/device_frame_13_7.ppm.gz" to (13_700L to (0.832f to 0.391f)),
            "/fixtures/dynamic_rim/device_frame_14_0.ppm.gz" to (14_000L to (0.840f to 0.393f)),
            "/fixtures/dynamic_rim/device_frame_14_5.ppm.gz" to (14_500L to (0.861f to 0.397f)),
            "/fixtures/dynamic_rim/device_frame_15_0.ppm.gz" to (15_000L to (0.882f to 0.400f)),
        )
        val calibrator = DynamicRimCalibrator()

        expected.forEach { (name, timedCenter) ->
            val (timeMs, center) = timedCenter
            val (pixels, width, height) = image(name)
            val candidates = RimCandidateFinder.findCandidates(pixels, width, height)

            assertTrue(
                "$name should contain the real hoop; actual=$candidates",
                candidates.any {
                    abs(it.rect.cx - center.first) + abs(it.rect.cy - center.second) < 0.09f
                },
            )
            val tracked = calibrator.update(timeMs, candidates)
            assertNotNull("$name should keep tracking a rim; candidates=$candidates", tracked)
            val error = abs(tracked!!.rim.cx - center.first) + abs(tracked.rim.cy - center.second)
            assertTrue(
                "$name dynamic rim should stay on the hoop: " +
                    "tracked=${tracked.rim} expected=$center candidates=$candidates",
                error < 0.09f,
            )
        }
    }

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
