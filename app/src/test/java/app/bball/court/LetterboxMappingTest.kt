package app.bball.court

import app.bball.court.core.Letterbox
import org.junit.Assert.assertEquals
import org.junit.Test

class LetterboxMappingTest {

    @Test
    fun wideFrameIsPaddedVerticallyWithoutStretching() {
        val transform = Letterbox.fit(1920, 1080, 512, 512)

        assertEquals(512, transform.resizedWidth)
        assertEquals(288, transform.resizedHeight)
        assertEquals(0f, transform.padX, 0.01f)
        assertEquals(112f, transform.padY, 0.01f)
        assertEquals(512f / 1920f, transform.scale, 0.000001f)
    }

    @Test
    fun modelCenterMapsBackToSourceCenter() {
        val transform = Letterbox.fit(1920, 1080, 512, 512)

        assertEquals(0.5f, transform.toSourceX(256f), 0.0001f)
        assertEquals(0.5f, transform.toSourceY(256f), 0.0001f)
    }

    @Test
    fun squareSourceUsesTheWholeModelInput() {
        val transform = Letterbox.fit(640, 640, 512, 512)

        assertEquals(512, transform.resizedWidth)
        assertEquals(512, transform.resizedHeight)
        assertEquals(0f, transform.padX, 0.001f)
        assertEquals(0f, transform.padY, 0.001f)
        assertEquals(0.25f, transform.toSourceX(128f), 0.0001f)
        assertEquals(0.75f, transform.toSourceY(384f), 0.0001f)
    }
}
