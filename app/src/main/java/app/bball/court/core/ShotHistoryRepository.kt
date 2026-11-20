package app.bball.court.core

import com.google.gson.Gson
import java.io.File

/** 一条完整的视频解析历史。 */
data class ShotHistoryRecord(
    val id: String,
    val createdAtMs: Long,
    val sourceUri: String,
    val sourceName: String,
    val attempts: Int,
    val made: Int,
    val layupMade: Int,
    val layupAtt: Int,
    val twoMade: Int,
    val twoAtt: Int,
    val threeMade: Int,
    val threeAtt: Int,
    val ftMade: Int,
    val ftAtt: Int,
    val avgReleaseAngle: Float?,
    val avgElbowAngle: Float?,
    val avgKneeAngle: Float?,
    val avgBallInHandMs: Long?,
    val framesAnalysed: Int,
    val ballHitRate: Float,
    val rim: NRect,
    val calibrated: Boolean,
    val autoRim: Boolean,
    val rimBallRatio: Float,
    val rimPx: Int,
    val bestStreak: Int,
    val bestMissStreak: Int,
    val avgIntervalMs: Long,
    val shots: List<ShotHistoryShot>,
)

/** 历史中保存的单次出手，不包含无法持久化的 Bitmap。 */
data class ShotHistoryShot(
    val tMs: Long,
    val type: ShotType,
    val made: Boolean,
    val releaseX: Float,
    val releaseY: Float,
    val distanceM: Float,
    val releaseAngle: Float?,
    val courtX: Float = 0f,
    val courtY: Float = 0f,
    val makeMs: Long = 0L,
    val resolveMs: Long = 0L,
    val thumbnailFile: String? = null,
)

private data class ShotHistoryFile(
    val version: Int = 1,
    val records: List<ShotHistoryRecord> = emptyList(),
)

object ShotHistoryCodec {
    private val gson = Gson()

    fun encode(records: List<ShotHistoryRecord>): String =
        gson.toJson(ShotHistoryFile(version = 1, records = records))

    fun decode(text: String): List<ShotHistoryRecord> {
        if (text.isBlank()) return emptyList()
        return gson.fromJson(text, ShotHistoryFile::class.java)?.records ?: emptyList()
    }
}

/**
 * 本地历史文件存储。每次写入先落临时文件，再替换正式文件，避免进程中断留下半截 JSON。
 */
class ShotHistoryRepository(
    private val file: File,
    private val maxRecords: Int = 100,
) {
    @Synchronized
    fun load(): List<ShotHistoryRecord> {
        val current = read(file)
        if (current != null) return current
        return read(backupFile()) ?: emptyList()
    }

    @Synchronized
    fun add(record: ShotHistoryRecord): List<ShotHistoryRecord> {
        val updated = (listOf(record) + load())
            .distinctBy { it.id }
            .sortedByDescending { it.createdAtMs }
            .take(maxRecords.coerceAtLeast(1))
        write(updated)
        return updated
    }

    @Synchronized
    fun delete(id: String): List<ShotHistoryRecord> {
        val updated = load().filterNot { it.id == id }
        write(updated)
        return updated
    }

    @Synchronized
    fun rename(id: String, name: String): List<ShotHistoryRecord> {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return load()
        val updated = load().map { record ->
            if (record.id == id) record.copy(sourceName = cleanName) else record
        }
        write(updated)
        return updated
    }

    @Synchronized
    fun clear() {
        file.delete()
        backupFile().delete()
        temporaryFile().delete()
    }

    private fun read(source: File): List<ShotHistoryRecord>? {
        if (!source.isFile || source.length() == 0L) return null
        return try {
            ShotHistoryCodec.decode(source.readText())
        } catch (_: Throwable) {
            null
        }
    }

    private fun write(records: List<ShotHistoryRecord>) {
        file.parentFile?.mkdirs()
        val temp = temporaryFile()
        temp.writeText(ShotHistoryCodec.encode(records))
        val backup = backupFile()
        backup.delete()
        if (file.exists() && !file.renameTo(backup)) {
            throw IllegalStateException("无法备份历史文件")
        }
        if (!temp.renameTo(file)) {
            if (backup.exists()) backup.renameTo(file)
            throw IllegalStateException("无法替换历史文件")
        }
        backup.delete()
    }

    private fun temporaryFile() = File(file.path + ".tmp")
    private fun backupFile() = File(file.path + ".bak")
}
