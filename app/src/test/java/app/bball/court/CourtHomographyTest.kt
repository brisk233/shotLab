package app.bball.court

import app.bball.court.core.CourtHomography
import app.bball.court.core.ShotType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CourtHomographyTest {

    // 假设一个"正对篮筐"的相机：图像 y 与球场 Y 成正比，x 与 X 成正比
    private val img = listOf(
        0.50f to 0.20f,   // 筐下地面
        0.20f to 0.55f,   // 罚球线左
        0.80f to 0.55f,   // 罚球线右
        0.50f to 0.80f,   // 三分弧顶
    )

    @Test
    fun maps_reference_points_back() {
        val h = CourtHomography.solve(img)
        assertNotNull(h)
        val (x0, y0) = h!!.toCourt(0.50f, 0.20f)
        assertEquals(0.0, x0.toDouble(), 0.05)
        assertEquals(0.0, y0.toDouble(), 0.05)
        val (x1, y1) = h.toCourt(0.20f, 0.55f)
        assertEquals(-2.45, x1.toDouble(), 0.1)
        assertEquals(4.225, y1.toDouble(), 0.1)
    }

    @Test
    fun classifies_distance_bands() {
        val h = CourtHomography.solve(img)!!
        // 点在筐下 → 上篮
        assertEquals(ShotType.LAYUP, h.shotType(0.50f, 0.22f))
        // 罚球线中点 → 罚球
        assertEquals(ShotType.FREE_THROW, h.shotType(0.50f, 0.55f))
        // 三分弧顶 → 三分
        assertEquals(ShotType.THREE, h.shotType(0.50f, 0.80f))
        // 罚球线与三分线之间 → 两分
        assertEquals(ShotType.TWO, h.shotType(0.50f, 0.68f))
    }
}
