package app.bball.court

import app.bball.court.core.NRect
import app.bball.court.core.ShotHistoryCodec
import app.bball.court.core.ShotHistoryRecord
import app.bball.court.core.ShotHistoryRepository
import app.bball.court.core.ShotHistoryShot
import app.bball.court.core.ShotType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ShotHistoryRepositoryTest {

    @Test
    fun codecRoundTripPreservesSummaryAndShots() {
        val record = record("record-1", 10_000)

        val decoded = ShotHistoryCodec.decode(ShotHistoryCodec.encode(listOf(record)))

        assertEquals(1, decoded.size)
        val restored = decoded.single()
        assertEquals(record.id, restored.id)
        assertEquals(record.sourceName, restored.sourceName)
        assertEquals(record.made, restored.made)
        assertEquals(record.attempts, restored.attempts)
        assertEquals(record.rim, restored.rim)
        assertEquals(record.shots, restored.shots)
    }

    @Test
    fun repositoryKeepsNewestRecordsAndHonorsLimit() {
        val dir = Files.createTempDirectory("shot-history").toFile()
        try {
            val repository = ShotHistoryRepository(File(dir, "history.json"), maxRecords = 3)
            repository.add(record("one", 1_000))
            repository.add(record("two", 2_000))
            repository.add(record("three", 3_000))
            repository.add(record("four", 4_000))

            assertEquals(listOf("four", "three", "two"), repository.load().map { it.id })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun repositoryDeletesOneRecordOrClearsAll() {
        val dir = Files.createTempDirectory("shot-history").toFile()
        try {
            val repository = ShotHistoryRepository(File(dir, "history.json"))
            repository.add(record("one", 1_000))
            repository.add(record("two", 2_000))

            repository.delete("one")
            assertEquals(listOf("two"), repository.load().map { it.id })

            repository.clear()
            assertTrue(repository.load().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun repositoryRenamesOnlyTheSelectedRecord() {
        val dir = Files.createTempDirectory("shot-history").toFile()
        try {
            val repository = ShotHistoryRepository(File(dir, "history.json"))
            repository.add(record("one", 1_000))
            repository.add(record("two", 2_000))

            repository.rename("one", "主场训练")

            assertEquals(
                listOf("two.mp4", "主场训练"),
                repository.load().map { it.sourceName },
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptedHistoryFallsBackToEmptyList() {
        val dir = Files.createTempDirectory("shot-history").toFile()
        try {
            val file = File(dir, "history.json")
            file.writeText("{not-json")

            val repository = ShotHistoryRepository(file)

            assertTrue(repository.load().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun record(id: String, created: Long) = ShotHistoryRecord(
        id = id,
        createdAtMs = created,
        sourceUri = "content://video/$id",
        sourceName = "$id.mp4",
        attempts = 2,
        made = 1,
        layupMade = 0,
        layupAtt = 0,
        twoMade = 1,
        twoAtt = 2,
        threeMade = 0,
        threeAtt = 0,
        ftMade = 0,
        ftAtt = 0,
        avgReleaseAngle = 51.5f,
        avgElbowAngle = 92f,
        avgKneeAngle = 151f,
        avgBallInHandMs = 800L,
        framesAnalysed = 72,
        ballHitRate = 0.91f,
        rim = NRect(0.625f, 0.322f, 0.663f, 0.352f),
        calibrated = true,
        autoRim = false,
        rimBallRatio = 1.9f,
        rimPx = 36,
        bestStreak = 1,
        bestMissStreak = 1,
        avgIntervalMs = 3_700L,
        shots = listOf(
            ShotHistoryShot(
                tMs = 1_100,
                type = ShotType.TWO,
                made = false,
                releaseX = 0.33f,
                releaseY = 0.19f,
                distanceM = 3.9f,
                releaseAngle = 48f,
                courtX = 2.1f,
                courtY = 3.3f,
                resolveMs = 1_850,
                thumbnailFile = "shot_0.jpg",
            ),
            ShotHistoryShot(
                tMs = 4_800,
                type = ShotType.TWO,
                made = true,
                releaseX = 0.35f,
                releaseY = 0.21f,
                distanceM = 3.64f,
                releaseAngle = 52f,
                courtX = 1.9f,
                courtY = 3.1f,
                makeMs = 5_550,
                resolveMs = 5_550,
                thumbnailFile = "shot_1.jpg",
            ),
        ),
    )
}
