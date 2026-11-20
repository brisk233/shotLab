package app.bball.court

import app.bball.court.core.CourtHomography
import app.bball.court.core.NRect
import app.bball.court.core.ShotType
import app.bball.court.core.TrackletEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackletEngineRegressionTest {

    @Test
    fun rimIntersectionUsesTheDescendingRootAfterTheApex() {
        val rim = NRect(0.49f, 0.49f, 0.51f, 0.51f)
        val frames = frames(
            0 to (0.510f to 0.52f),
            50 to (0.510f to 0.47f),
            100 to (0.510f to 0.42f),
            150 to (0.510f to 0.37f),
            200 to (0.510f to 0.33f),
            250 to (0.510f to 0.32f),
            300 to (0.510f to 0.33f),
            350 to (0.510f to 0.37f),
            400 to (0.510f to 0.42f),
            450 to (0.510f to 0.47f),
            500 to (0.510f to 0.52f),
            550 to (0.510f to 0.58f),
        )

        val events = TrackletEngine.run(frames, rim, 720, 1280)

        assertEquals(1, events.size)
        assertTrue("下降段穿筐应判为命中", events.single().made)
        assertEquals("不应使用上升段交点", 480L, events.single().resolveMs)
    }

    @Test
    fun trackletReconnectsAcrossOneShortDetectionGap() {
        val rim = NRect(0.49f, 0.42f, 0.51f, 0.48f)
        val frames = frames(
            0 to (0.500f to 0.58f),
            100 to (0.500f to 0.51f),
            200 to (0.500f to 0.47f),
            300 to (0.500f to 0.45f),
            660 to (0.500f to 0.47f),
            760 to (0.500f to 0.53f),
            860 to (0.500f to 0.61f),
            960 to (0.500f to 0.70f),
        ).toMutableList().apply {
            add(
                4,
                TrackletEngine.FrameObs(
                    t = 400L,
                    cands = listOf(TrackletEngine.Obs(400L, 0.10f, 0.90f, 0.9f)),
                    person = null,
                ),
            )
        }

        val events = TrackletEngine.run(frames, rim, 720, 1280)

        assertEquals("短时丢检不应把一次出手拆成两段", 1, events.size)
        assertTrue(events.single().made)
    }

    @Test
    fun candidatesInsidePersonDoNotSeedShotTracklets() {
        val rim = NRect(0.49f, 0.42f, 0.51f, 0.48f)
        val person = NRect(0.40f, 0.10f, 0.60f, 0.90f)
        val frames = listOf(
            TrackletEngine.FrameObs(
                t = 0L,
                cands = listOf(TrackletEngine.Obs(0L, 0.500f, 0.35f, 0.9f)),
                person = person,
                persons = listOf(person),
            ),
            TrackletEngine.FrameObs(
                t = 100L,
                cands = listOf(TrackletEngine.Obs(100L, 0.500f, 0.39f, 0.9f)),
                person = null,
            ),
            TrackletEngine.FrameObs(
                t = 200L,
                cands = listOf(TrackletEngine.Obs(200L, 0.500f, 0.44f, 0.9f)),
                person = null,
            ),
            TrackletEngine.FrameObs(
                t = 300L,
                cands = listOf(TrackletEngine.Obs(300L, 0.500f, 0.50f, 0.9f)),
                person = null,
            ),
            TrackletEngine.FrameObs(
                t = 400L,
                cands = listOf(TrackletEngine.Obs(400L, 0.500f, 0.57f, 0.9f)),
                person = null,
            ),
        )

        val events = TrackletEngine.run(frames, rim, 720, 1280)

        assertEquals("球员框内的候选点不能作为新出手轨迹的起点", 0, events.size)
    }

    @Test
    fun uncalibratedNearRimTrackIsEstimatedAsLayup() {
        val rim = NRect(0.49f, 0.49f, 0.51f, 0.51f)
        val frames = frames(
            0 to (0.510f to 0.52f),
            50 to (0.510f to 0.47f),
            100 to (0.510f to 0.42f),
            150 to (0.510f to 0.37f),
            200 to (0.510f to 0.33f),
            250 to (0.510f to 0.32f),
            300 to (0.510f to 0.33f),
            350 to (0.510f to 0.37f),
            400 to (0.510f to 0.42f),
            450 to (0.510f to 0.47f),
            500 to (0.510f to 0.52f),
        )

        val events = TrackletEngine.run(frames, rim, 720, 1280)

        assertEquals(1, events.size)
        assertEquals("未标定时近筐轨迹应估算为上篮", "LAYUP", events.single().type.name)
    }

    @Test
    fun calibratedTrackletUsesCourtDistanceForShotType() {
        val homography = CourtHomography.solve(
            listOf(
                0.510f to 0.52f,
                0.20f to 0.55f,
                0.80f to 0.55f,
                0.50f to 0.80f,
            ),
        )!!
        val rim = NRect(0.49f, 0.49f, 0.51f, 0.51f)
        val frames = frames(
            0 to (0.510f to 0.52f),
            50 to (0.510f to 0.47f),
            100 to (0.510f to 0.42f),
            150 to (0.510f to 0.37f),
            200 to (0.510f to 0.33f),
            250 to (0.510f to 0.32f),
            300 to (0.510f to 0.33f),
            350 to (0.510f to 0.37f),
            400 to (0.510f to 0.42f),
            450 to (0.510f to 0.47f),
            500 to (0.510f to 0.52f),
            550 to (0.510f to 0.58f),
        )

        val events = TrackletEngine.run(frames, rim, 720, 1280, homography)

        assertEquals(1, events.size)
        assertEquals(ShotType.LAYUP, events.single().type)
        assertEquals(0f, events.single().distM, 0.1f)
    }

    @Test
    fun rememberedRimIsCorrectedFromConvergingMadeTrajectories() {
        val roughRim = NRect(0.48f, 0.15f, 0.52f, 0.16f)
        val frames = ArrayList<TrackletEngine.FrameObs>()
        repeat(6) { shot ->
            val startMs = shot * 2_000L
            val path = listOf(
                0.20f to 0.60f,
                0.15f to 0.56f,
                0.12f to 0.52f,
                0.10f to 0.50f,
                0.12f to 0.48f,
                0.15f to 0.44f,
                0.20f to 0.40f,
            )
            path.forEachIndexed { index, (y, x) ->
                val time = startMs + index * 100L
                frames.add(
                    TrackletEngine.FrameObs(
                        t = time,
                        cands = listOf(TrackletEngine.Obs(time, x, y, 0.9f)),
                        person = null,
                    ),
                )
            }
        }

        val result = TrackletEngine.runDetailed(frames, roughRim, 720, 1280)
        val events = result.events

        assertEquals("六次真实穿筐都应保留", 6, events.size)
        assertTrue(
            "错误下移的筐框应由穿筐轨迹自动校正，实际 rim=${result.rim}",
            events.all { it.made },
        )
    }

    @Test
    fun localCrossingClusterAdaptsRimXAcrossCameraSegments() {
        val roughRim = NRect(0.48f, 0.14f, 0.52f, 0.16f)
        val frames = ArrayList<TrackletEngine.FrameObs>()

        fun addShots(startSec: Int, centerX: Float) {
            repeat(6) { shot ->
                val startMs = (startSec + shot * 2) * 1_000L
                val path = listOf(
                    0.20f to (centerX + 0.10f),
                    0.15f to (centerX + 0.06f),
                    0.12f to (centerX + 0.02f),
                    0.10f to centerX,
                    0.12f to (centerX - 0.02f),
                    0.15f to (centerX - 0.06f),
                    0.20f to (centerX - 0.10f),
                )
                path.forEachIndexed { index, (y, x) ->
                    val time = startMs + index * 100L
                    frames.add(
                        TrackletEngine.FrameObs(
                            t = time,
                            cands = listOf(TrackletEngine.Obs(time, x, y, 0.9f)),
                            person = null,
                        ),
                    )
                }
            }
        }

        addShots(startSec = 2, centerX = 0.35f)
        addShots(startSec = 32, centerX = 0.65f)

        val events = TrackletEngine.run(frames, roughRim, 720, 1280)

        assertEquals(12, events.size)
        assertTrue(
            "不同机位段应使用局部穿筐中心: " +
                events.joinToString { "${it.releaseMs}:${it.made}:${it.x}" },
            events.all { it.made },
        )
    }

    @Test
    fun correctedLowResolutionRimUsesWiderCenterTolerance() {
        val roughRim = NRect(0.48f, 0.14f, 0.52f, 0.16f)
        val frames = ArrayList<TrackletEngine.FrameObs>()

        fun addTrack(startMs: Long, rimX: Float) {
            val path = listOf(
                0.20f to (rimX + 0.08f),
                0.15f to (rimX + 0.05f),
                0.12f to (rimX + 0.02f),
                0.10f to rimX,
                0.12f to (rimX - 0.02f),
                0.15f to (rimX - 0.05f),
                0.20f to (rimX - 0.08f),
            )
            path.forEachIndexed { index, (y, x) ->
                val time = startMs + index * 100L
                frames.add(
                    TrackletEngine.FrameObs(
                        t = time,
                        cands = listOf(TrackletEngine.Obs(time, x, y, 0.9f)),
                        person = null,
                    ),
                )
            }
        }

        repeat(4) { addTrack(it * 2_000L, 0.50f) }
        addTrack(20_000L, 0.570f)

        val events = TrackletEngine.run(frames, roughRim, 720, 1280)

        assertEquals(5, events.size)
        assertTrue(
            "低分辨率筐框校正后，边缘穿筐不应被误判为不中: " +
                events.joinToString { "${it.releaseMs}:${it.made}:${it.x}" },
            events.all { it.made },
        )
    }

    @Test
    fun roughLowResolutionRimAcceptsBallRadiusCenterError() {
        val roughRim = NRect(0.45f, 0.44f, 0.55f, 0.48f)
        val frames = listOf(
            0 to (0.500f to 0.600f),
            100 to (0.550f to 0.520f),
            150 to (0.570f to 0.460f),
            200 to (0.580f to 0.430f),
            250 to (0.590f to 0.420f),
            300 to (0.595f to 0.430f),
            350 to (0.600f to 0.460f),
            400 to (0.605f to 0.500f),
            450 to (0.610f to 0.560f),
            500 to (0.615f to 0.640f),
        ).map { (time, point) ->
            TrackletEngine.FrameObs(
                t = time.toLong(),
                cands = listOf(
                    TrackletEngine.Obs(time.toLong(), point.first, point.second, 0.9f),
                ),
                person = null,
            )
        }

        val events = TrackletEngine.run(frames, roughRim, 360, 640)

        assertEquals(1, events.size)
        assertTrue(
            "360p 下 36px 粗筐框应容忍约一个球半径的球心误差",
            events.single().made,
        )
    }

    private fun frames(vararg points: Pair<Int, Pair<Float, Float>>): List<TrackletEngine.FrameObs> =
        points.map { (timeMs, point) ->
            TrackletEngine.FrameObs(
                t = timeMs.toLong(),
                cands = listOf(TrackletEngine.Obs(timeMs.toLong(), point.first, point.second, 0.9f)),
                person = null,
            )
        }
}
