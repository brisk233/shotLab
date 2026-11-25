package app.bball.court

import app.bball.court.core.CourtHomography
import app.bball.court.core.NRect
import app.bball.court.core.RimTrackPoint
import app.bball.court.core.ShotAggregator
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
    fun trustedFixedRimIsNotSelfCorrectedByDistantTrackClusters() {
        val roughRim = NRect(0.48f, 0.14f, 0.52f, 0.16f)
        val frames = ArrayList<TrackletEngine.FrameObs>()
        val path = listOf(
            0.20f to 0.95f,
            0.15f to 0.93f,
            0.12f to 0.91f,
            0.10f to 0.90f,
            0.12f to 0.91f,
            0.15f to 0.93f,
            0.20f to 0.95f,
        )
        repeat(4) { track ->
            path.forEachIndexed { index, (y, x) ->
                val time = track * 2_000L + index * 100L
                frames.add(
                    TrackletEngine.FrameObs(
                        t = time,
                        cands = listOf(TrackletEngine.Obs(time, x, y, 0.9f)),
                        person = null,
                    ),
                )
            }
        }

        val result = TrackletEngine.runDetailed(
            frames,
            roughRim,
            720,
            1280,
            allowLowResMade = false,
            allowRimRefinement = false,
        )

        assertEquals(0.50f, result.rim.cx, 0.001f)
        assertTrue(
            "固定可信筐不得被远处伪轨迹簇自校正并判中: " +
                result.events.joinToString { "${it.releaseMs}:${it.made}:${it.x}:${it.resolveMs}" },
            result.events.none { it.made },
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

    @Test
    fun shotValidationUsesRimPositionFromTheCurrentCameraFrame() {
        val firstRim = NRect(0.25f, 0.28f, 0.35f, 0.32f)
        val secondRim = NRect(0.65f, 0.28f, 0.75f, 0.32f)
        val rimTrack = listOf(
            RimTrackPoint(0L, firstRim, 0.95f),
            RimTrackPoint(2_000L, firstRim, 0.95f),
            RimTrackPoint(3_000L, secondRim, 0.95f),
            RimTrackPoint(5_000L, secondRim, 0.95f),
        )
        val frames = ArrayList<TrackletEngine.FrameObs>()

        fun addShot(startMs: Long, rimCX: Float) {
            listOf(
                0L to 0.60f,
                50L to 0.54f,
                100L to 0.48f,
                150L to 0.42f,
                200L to 0.36f,
                250L to 0.30f,
                300L to 0.27f,
                350L to 0.29f,
                400L to 0.32f,
                450L to 0.38f,
                500L to 0.46f,
                550L to 0.55f,
            ).forEach { (dt, y) ->
                val t = startMs + dt
                frames.add(
                    TrackletEngine.FrameObs(
                        t = t,
                        cands = listOf(TrackletEngine.Obs(t, rimCX, y, 0.9f)),
                        person = null,
                    ),
                )
            }
        }

        addShot(0L, 0.30f)
        addShot(3_000L, 0.70f)

        val result = TrackletEngine.runDetailed(
            frames,
            firstRim,
            720,
            1280,
            rimTrack = rimTrack,
        )

        assertEquals(2, result.events.size)
        assertTrue(result.events.all { it.made })
    }

    @Test
    fun untrustedDynamicRimDoesNotReplaceValidFixedRim() {
        val fixedRim = NRect(0.49f, 0.49f, 0.51f, 0.51f)
        val untrusted = listOf(
            RimTrackPoint(
                tMs = 0L,
                rim = NRect(0.04f, 0.49f, 0.06f, 0.51f),
                confidence = 0.10f,
            ),
        )
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

        val result = TrackletEngine.runDetailed(
            frames,
            fixedRim,
            720,
            1280,
            rimTrack = untrusted,
        )

        assertEquals(1, result.events.size)
        assertTrue("低置信度动态框不得污染固定筐回退", result.events.single().made)
    }

    @Test
    fun movingRimIsCheckedAtTheBallCrossingTime() {
        val roughRim = NRect(0.49f, 0.28f, 0.51f, 0.32f)
        val rimTrack = (0..11).map { index ->
            val t = index * 50L
            val cx = 0.30f + 0.20f * index / 11f
            RimTrackPoint(
                tMs = t,
                rim = NRect(cx - 0.01f, 0.28f, cx + 0.01f, 0.32f),
                confidence = 0.90f,
            )
        }
        val trajectory = listOf(
            0L to 0.60f,
            50L to 0.52f,
            100L to 0.45f,
            150L to 0.39f,
            200L to 0.34f,
            250L to 0.30f,
            300L to 0.27f,
            350L to 0.29f,
            400L to 0.32f,
            450L to 0.38f,
            500L to 0.46f,
            550L to 0.55f,
        )
        val frames = trajectory.map { (t, y) ->
            val progress = t / 550f
            TrackletEngine.FrameObs(
                t = t,
                cands = listOf(
                    TrackletEngine.Obs(t, 0.30f + 0.20f * progress, y, 0.9f),
                ),
                person = null,
            )
        }

        val result = TrackletEngine.runDetailed(
            frames,
            roughRim,
            720,
            1280,
            rimTrack = rimTrack,
        )

        assertEquals(1, result.events.size)
        assertTrue("移动中的筐框必须按穿筐时刻复核", result.events.single().made)
    }

    @Test
    fun shotTypeUsesRimPositionAtReleaseTime() {
        val roughRim = NRect(0.525f, 0.28f, 0.675f, 0.32f)
        val rimTrack = listOf(
            RimTrackPoint(0L, NRect(0.025f, 0.28f, 0.175f, 0.32f), 0.95f),
            RimTrackPoint(100L, NRect(0.191f, 0.28f, 0.341f, 0.32f), 0.95f),
            RimTrackPoint(200L, NRect(0.358f, 0.28f, 0.508f, 0.32f), 0.95f),
            RimTrackPoint(250L, NRect(0.525f, 0.28f, 0.675f, 0.32f), 0.95f),
            RimTrackPoint(500L, NRect(0.525f, 0.28f, 0.675f, 0.32f), 0.95f),
        )
        val trajectory = listOf(
            0L to 0.60f,
            50L to 0.52f,
            100L to 0.45f,
            150L to 0.39f,
            200L to 0.34f,
            250L to 0.30f,
            300L to 0.27f,
            350L to 0.29f,
            400L to 0.32f,
            450L to 0.38f,
            500L to 0.46f,
            550L to 0.55f,
        )
        val frames = trajectory.map { (t, y) ->
            TrackletEngine.FrameObs(
                t = t,
                cands = listOf(TrackletEngine.Obs(t, 0.60f, y, 0.9f)),
                person = null,
            )
        }

        val result = TrackletEngine.runDetailed(
            frames,
            roughRim,
            720,
            1280,
            rimTrack = rimTrack,
        )

        assertEquals(1, result.events.size)
        assertEquals(
            "类型必须比较出手点与出手时刻的筐位置",
            ShotType.THREE,
            result.events.single().type,
        )
    }

    @Test
    fun verticalMovingRimCrossingUsesRelativeIntersection() {
        val roughRim = NRect(0.49f, 0.30f, 0.51f, 0.32f)
        val rimTrack = listOf(
            RimTrackPoint(0L, NRect(0.49f, 0.30f, 0.51f, 0.32f), 0.95f),
            RimTrackPoint(350L, NRect(0.49f, 0.30f, 0.51f, 0.32f), 0.95f),
            RimTrackPoint(400L, NRect(0.49f, 0.27f, 0.51f, 0.29f), 0.95f),
            RimTrackPoint(550L, NRect(0.49f, 0.27f, 0.51f, 0.29f), 0.95f),
        )
        val trajectory = listOf(
            0L to 0.60f,
            50L to 0.52f,
            100L to 0.45f,
            150L to 0.39f,
            200L to 0.34f,
            250L to 0.30f,
            300L to 0.28f,
            350L to 0.29f,
            400L to 0.32f,
            450L to 0.38f,
            500L to 0.46f,
            550L to 0.55f,
        )
        val frames = trajectory.map { (t, y) ->
            TrackletEngine.FrameObs(
                t = t,
                cands = listOf(TrackletEngine.Obs(t, 0.50f, y, 0.9f)),
                person = null,
            )
        }

        val result = TrackletEngine.runDetailed(
            frames,
            roughRim,
            720,
            1280,
            rimTrack = rimTrack,
        )

        assertEquals(1, result.events.size)
        assertTrue("竖直移动的筐面必须做相对交点求解", result.events.single().made)
    }

    @Test
    fun predictedCrossingUsesMovingRimHeight() {
        val roughRim = NRect(0.64f, 0.50f, 0.68f, 0.54f)
        val rimTrack = listOf(
            RimTrackPoint(0L, NRect(0.64f, 0.50f, 0.68f, 0.54f), 0.95f),
            RimTrackPoint(200L, NRect(0.64f, 0.52f, 0.68f, 0.56f), 0.95f),
            RimTrackPoint(1_000L, NRect(0.64f, 0.60f, 0.68f, 0.64f), 0.95f),
        )
        val trajectory = listOf(
            0L to (0.18f to 0.20f),
            100L to (0.26f to 0.21f),
            200L to (0.34f to 0.24f),
            300L to (0.42f to 0.29f),
            400L to (0.50f to 0.36f),
        )
        val frames = trajectory.map { (t, point) ->
            TrackletEngine.FrameObs(
                t = t,
                cands = listOf(TrackletEngine.Obs(t, point.first, point.second, 0.9f)),
                person = null,
            )
        }

        val result = TrackletEngine.runDetailed(
            frames,
            roughRim,
            720,
            1280,
            rimTrack = rimTrack,
        )

        assertEquals(1, result.events.size)
        assertTrue("预测穿筐时间必须与移动中的筐高求交", result.events.single().made)
    }

    @Test
    fun staleTrustedRimDoesNotControlLaterShot() {
        val fixedRim = NRect(0.65f, 0.28f, 0.75f, 0.32f)
        val staleTrack = listOf(
            RimTrackPoint(
                tMs = 0L,
                rim = NRect(0.05f, 0.28f, 0.15f, 0.32f),
                confidence = 0.95f,
            ),
        )
        val frames = listOf(
            3_000L to 0.60f,
            3_050L to 0.52f,
            3_100L to 0.45f,
            3_150L to 0.39f,
            3_200L to 0.34f,
            3_250L to 0.30f,
            3_300L to 0.27f,
            3_350L to 0.29f,
            3_400L to 0.32f,
            3_450L to 0.38f,
            3_500L to 0.46f,
            3_550L to 0.55f,
        ).map { (t, y) ->
            TrackletEngine.FrameObs(
                t = t,
                cands = listOf(TrackletEngine.Obs(t, 0.70f, y, 0.9f)),
                person = null,
            )
        }

        val result = TrackletEngine.runDetailed(
            frames,
            fixedRim,
            720,
            1280,
            rimTrack = staleTrack,
        )

        assertEquals(1, result.events.size)
        assertTrue("过期动态筐框不得覆盖后续固定机位", result.events.single().made)
    }

    @Test
    fun peakFilterUsesRimAtTheApexTime() {
        val roughRim = NRect(0.69f, 0.30f, 0.71f, 0.32f)
        val rimTrack = listOf(
            RimTrackPoint(0L, NRect(0.69f, 0.19f, 0.71f, 0.21f), 0.95f),
            RimTrackPoint(260L, NRect(0.69f, 0.19f, 0.71f, 0.21f), 0.95f),
            RimTrackPoint(300L, NRect(0.69f, 0.30f, 0.71f, 0.32f), 0.95f),
            RimTrackPoint(550L, NRect(0.69f, 0.30f, 0.71f, 0.32f), 0.95f),
        )
        val trajectory = listOf(
            0L to 0.55f,
            50L to 0.46f,
            100L to 0.38f,
            150L to 0.32f,
            200L to 0.28f,
            250L to 0.27f,
            300L to 0.26f,
            350L to 0.28f,
            400L to 0.32f,
            450L to 0.38f,
            500L to 0.46f,
        )
        val frames = trajectory.map { (t, y) ->
            TrackletEngine.FrameObs(
                t = t,
                cands = listOf(TrackletEngine.Obs(t, 0.70f, y, 0.9f)),
                person = null,
            )
        }

        val result = TrackletEngine.runDetailed(
            frames,
            roughRim,
            720,
            1280,
            rimTrack = rimTrack,
        )

        assertEquals(1, result.events.size)
        assertTrue("峰值过滤必须使用最高点时刻的筐框", result.events.single().made)
    }

    @Test
    fun staleTrackDoesNotAffectFallbackPredictionCheck() {
        val fixedRim = NRect(0.69f, 0.49f, 0.71f, 0.51f)
        val staleTrack = listOf(
            RimTrackPoint(
                tMs = 0L,
                rim = NRect(0.04f, 0.49f, 0.06f, 0.51f),
                confidence = 0.95f,
            ),
        )
        val trajectory = listOf(
            3_000L to 0.20f,
            3_100L to 0.21f,
            3_200L to 0.24f,
            3_300L to 0.29f,
            3_400L to 0.36f,
        )
        val frames = trajectory.map { (t, y) ->
            TrackletEngine.FrameObs(
                t = t,
                cands = listOf(TrackletEngine.Obs(t, 0.70f, y, 0.9f)),
                person = null,
            )
        }

        val result = TrackletEngine.runDetailed(
            frames,
            fixedRim,
            720,
            1280,
            rimTrack = staleTrack,
        )

        assertEquals(1, result.events.size)
        assertTrue("过期动态轨迹不得参与固定筐的预测命中复核", result.events.single().made)
    }

    @Test
    fun predictedCrossingRejectsRelativeMotionFromAboveToBelow() {
        val roughRim = NRect(0.49f, 0.18f, 0.51f, 0.20f)
        val rimTrack = listOf(
            RimTrackPoint(0L, NRect(0.49f, 0.18f, 0.51f, 0.20f), 0.95f),
            RimTrackPoint(2_000L, NRect(0.79f, 0.48f, 0.81f, 0.50f), 0.95f),
        )
        val trajectory = listOf(
            0L to 0.200f,
            100L to 0.201f,
            200L to 0.204f,
            300L to 0.209f,
            400L to 0.216f,
        )
        val frames = trajectory.map { (t, y) ->
            TrackletEngine.FrameObs(
                t = t,
                cands = listOf(TrackletEngine.Obs(t, 0.50f, y, 0.9f)),
                person = null,
            )
        }

        val result = TrackletEngine.runDetailed(
            frames,
            roughRim,
            720,
            1280,
            rimTrack = rimTrack,
        )

        assertTrue("只能接受球从筐上方穿向筐下方", result.events.none { it.made })
    }

    @Test
    fun flatBallSegmentCanCrossVerticallyMovingRim() {
        val roughRim = NRect(0.49f, 0.29f, 0.51f, 0.31f)
        val rimTrack = listOf(
            RimTrackPoint(0L, NRect(0.49f, 0.30f, 0.51f, 0.32f), 0.95f),
            RimTrackPoint(400L, NRect(0.49f, 0.31f, 0.51f, 0.33f), 0.95f),
            RimTrackPoint(450L, NRect(0.49f, 0.32f, 0.51f, 0.34f), 0.95f),
            RimTrackPoint(500L, NRect(0.49f, 0.26f, 0.51f, 0.28f), 0.95f),
            RimTrackPoint(550L, NRect(0.49f, 0.26f, 0.51f, 0.28f), 0.95f),
        )
        val trajectory = listOf(
            0L to 0.55f,
            50L to 0.46f,
            100L to 0.38f,
            150L to 0.32f,
            200L to 0.28f,
            250L to 0.27f,
            300L to 0.27f,
            350L to 0.29f,
            400L to 0.31f,
            450L to 0.30f,
            500L to 0.30f,
            550L to 0.38f,
        )
        val frames = trajectory.map { (t, y) ->
            TrackletEngine.FrameObs(
                t = t,
                cands = listOf(TrackletEngine.Obs(t, 0.50f, y, 0.9f)),
                person = null,
            )
        }

        val result = TrackletEngine.runDetailed(
            frames,
            roughRim,
            720,
            1280,
            rimTrack = rimTrack,
        )

        assertEquals(1, result.events.size)
        assertTrue("球图像高度不变时仍应检测相对移动的筐面交点", result.events.single().made)
    }

    @Test
    fun sceneCutsPreventTrackletsFromLinkingAcrossCameraViews() {
        val firstRim = NRect(0.29f, 0.28f, 0.31f, 0.32f)
        val secondRim = NRect(0.69f, 0.28f, 0.71f, 0.32f)
        val frames = listOf(
            TrackletEngine.FrameObs(
                t = 0L,
                cands = listOf(TrackletEngine.Obs(0L, 0.30f, 0.58f, 0.9f, segment = 0)),
                person = null,
                segment = 0,
            ),
            TrackletEngine.FrameObs(
                t = 50L,
                cands = listOf(TrackletEngine.Obs(50L, 0.30f, 0.48f, 0.9f, segment = 0)),
                person = null,
                segment = 0,
            ),
            TrackletEngine.FrameObs(
                t = 100L,
                cands = listOf(TrackletEngine.Obs(100L, 0.70f, 0.36f, 0.9f, segment = 1)),
                person = null,
                segment = 1,
            ),
            TrackletEngine.FrameObs(
                t = 150L,
                cands = listOf(TrackletEngine.Obs(150L, 0.70f, 0.28f, 0.9f, segment = 1)),
                person = null,
                segment = 1,
            ),
            TrackletEngine.FrameObs(
                t = 200L,
                cands = listOf(TrackletEngine.Obs(200L, 0.70f, 0.34f, 0.9f, segment = 1)),
                person = null,
                segment = 1,
            ),
        )
        val rimTrack = listOf(
            RimTrackPoint(0L, firstRim, 0.95f, segment = 0),
            RimTrackPoint(50L, firstRim, 0.95f, segment = 0),
            RimTrackPoint(100L, secondRim, 0.95f, segment = 1),
            RimTrackPoint(200L, secondRim, 0.95f, segment = 1),
        )

        val result = TrackletEngine.runDetailed(
            frames,
            firstRim,
            720,
            1280,
            rimTrack = rimTrack,
        )

        assertEquals("切镜两侧的短轨迹不得拼接成一次出手", 0, result.events.size)
    }

    @Test
    fun sceneCutFallbackRimIgnoresCrossingsFromOtherCameraSegments() {
        val targetRim = NRect(0.69f, 0.28f, 0.71f, 0.32f)
        val frames = ArrayList<TrackletEngine.FrameObs>()
        val path = listOf(
            0.60f,
            0.52f,
            0.45f,
            0.39f,
            0.34f,
            0.30f,
            0.27f,
            0.29f,
            0.32f,
            0.38f,
            0.46f,
            0.55f,
        )

        fun addTrack(segment: Int, startMs: Long, centerX: Float) {
            path.forEachIndexed { index, y ->
                val time = startMs + index * 50L
                frames.add(
                    TrackletEngine.FrameObs(
                        t = time,
                        cands = listOf(
                            TrackletEngine.Obs(time, centerX, y, 0.9f, segment),
                        ),
                        person = null,
                        segment = segment,
                    ),
                )
            }
        }

        repeat(3) { addTrack(segment = 0, startMs = it * 1_000L, centerX = 0.20f) }
        addTrack(segment = 1, startMs = 3_000L, centerX = 0.70f)

        val result = TrackletEngine.runDetailed(frames, targetRim, 720, 1280)
        val summary = ShotAggregator.summarize(result.events)
        val targetEvent = summary.events.singleOrNull { it.releaseMs >= 3_000L }

        assertEquals("不同镜头的连续出手不得被 1.05 秒去重合并", 3, summary.attempts)
        assertTrue("切镜后的目标出手应存在", targetEvent != null)
        assertTrue(
            "固定筐回退不得吸收其他镜头段的穿点",
            targetEvent!!.made,
        )
    }

    @Test
    fun observedRimMissIsNotOverriddenByReturnPathExtrapolation() {
        val rim = NRect(0.915f, 0.420f, 0.965f, 0.440f)
        val rimTrack = (0..12).map { index ->
            RimTrackPoint(index * 50L, rim, 0.95f)
        }
        val trajectory = listOf(
            0L to (0.290f to 0.392f),
            50L to (0.360f to 0.360f),
            100L to (0.430f to 0.330f),
            150L to (0.500f to 0.310f),
            200L to (0.580f to 0.312f),
            250L to (0.660f to 0.325f),
            300L to (0.740f to 0.345f),
            350L to (0.820f to 0.370f),
            400L to (0.890f to 0.390f),
            450L to (0.930f to 0.408f),
            500L to (0.920f to 0.416f),
            550L to (0.880f to 0.430f),
            600L to (0.800f to 0.450f),
            650L to (0.700f to 0.500f),
        )
        val frames = trajectory.map { (time, point) ->
            TrackletEngine.FrameObs(
                t = time,
                cands = listOf(TrackletEngine.Obs(time, point.first, point.second, 0.8f)),
                person = null,
            )
        }

        val result = TrackletEngine.runDetailed(
            frames,
            rim,
            720,
            1280,
            rimTrack = rimTrack,
        )

        assertEquals(1, result.events.size)
        assertTrue("实测未穿过筐口时，局部外推不得改判命中", !result.events.single().made)
    }

    @Test
    fun ballReturningAwayFromTheRimDoesNotCreateAnotherShot() {
        val rim = NRect(0.915f, 0.420f, 0.965f, 0.440f)
        val rimTrack = (0..12).map { index ->
            RimTrackPoint(index * 50L, rim, 0.95f)
        }
        val trajectory = listOf(
            0L to (0.860f to 0.400f),
            70L to (0.820f to 0.350f),
            140L to (0.750f to 0.380f),
            210L to (0.650f to 0.430f),
            280L to (0.550f to 0.480f),
            350L to (0.450f to 0.540f),
        )
        val frames = trajectory.map { (time, point) ->
            TrackletEngine.FrameObs(
                t = time,
                cands = listOf(TrackletEngine.Obs(time, point.first, point.second, 0.7f)),
                person = null,
            )
        }

        val result = TrackletEngine.runDetailed(
            frames,
            rim,
            720,
            1280,
            rimTrack = rimTrack,
        )

        assertEquals("从筐口返回球员的球不得算新出手", 0, result.events.size)
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
