package app.bball.court.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/** Android 侧历史仓库：JSON 记录和出手缩略图全部保存在应用私有目录。 */
object ShotHistoryStore {

    private const val HISTORY_FILE = "analysis_history.json"
    private const val THUMB_DIR = "analysis_history_thumbs"

    fun rememberReadPermission(ctx: Context, uri: Uri) {
        if (uri.scheme != "content") return
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Throwable) {
            // 部分文件提供方不授予持久权限，本次会话仍可正常读取。
        }
    }

    fun sourceName(ctx: Context, uri: Uri): String {
        if (uri.scheme == "file") {
            return uri.path?.let { File(it).name }.orEmpty().ifBlank { "本地视频" }
        }
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        return cursor.getString(index).orEmpty().ifBlank { "视频" }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return uri.lastPathSegment.orEmpty().ifBlank { "视频" }
    }

    @Synchronized
    fun save(ctx: Context, uri: Uri, report: ShotReport): ShotHistoryRecord? {
        if (report.error != null && report.framesAnalysed == 0) return null
        val id = System.currentTimeMillis().toString() + "_" + UUID.randomUUID().toString()
        val thumbDir = File(File(ctx.filesDir, THUMB_DIR), id)
        if (!thumbDir.exists() && !thumbDir.mkdirs()) return null

        val savedShots = ArrayList<ShotHistoryShot>(report.shots.size)
        report.shots.forEachIndexed { index, shot ->
            val fileName = "shot_%03d.jpg".format(index)
            val thumb = shot.thumb
            if (thumb != null && !thumb.isRecycled) {
                try {
                    File(thumbDir, fileName).outputStream().use {
                        thumb.compress(Bitmap.CompressFormat.JPEG, 82, it)
                    }
                } catch (_: Throwable) {
                }
            }
            savedShots.add(
                ShotHistoryShot(
                    tMs = shot.tMs,
                    type = shot.type,
                    made = shot.made,
                    releaseX = shot.releaseX,
                    releaseY = shot.releaseY,
                    distanceM = shot.distanceM,
                    releaseAngle = shot.releaseAngle,
                    courtX = shot.courtX,
                    courtY = shot.courtY,
                    makeMs = shot.makeMs,
                    resolveMs = shot.resolveMs,
                    thumbnailFile = fileName.takeIf { File(thumbDir, it).isFile },
                ),
            )
        }

        val record = ShotHistoryRecord(
            id = id,
            createdAtMs = System.currentTimeMillis(),
            sourceUri = uri.toString(),
            sourceName = sourceName(ctx, uri),
            attempts = report.attempts,
            made = report.made,
            layupMade = report.layupMade,
            layupAtt = report.layupAtt,
            twoMade = report.twoMade,
            twoAtt = report.twoAtt,
            threeMade = report.threeMade,
            threeAtt = report.threeAtt,
            ftMade = report.ftMade,
            ftAtt = report.ftAtt,
            avgReleaseAngle = report.avgReleaseAngle,
            avgElbowAngle = report.avgElbowAngle,
            avgKneeAngle = report.avgKneeAngle,
            avgBallInHandMs = report.avgBallInHandMs,
            framesAnalysed = report.framesAnalysed,
            ballHitRate = report.ballHitRate,
            rim = NRect(0f, 0f, 0f, 0f),
            calibrated = report.calibrated,
            autoRim = report.autoRim,
            rimBallRatio = report.rimBallRatio,
            rimPx = report.rimPx,
            bestStreak = report.bestStreak,
            bestMissStreak = report.bestMissStreak,
            avgIntervalMs = report.avgIntervalMs,
            shots = savedShots,
        )
        val records = repository(ctx).add(record)
        pruneThumbnails(ctx, records)
        return record
    }

    fun list(ctx: Context): List<ShotHistoryRecord> = repository(ctx).load()

    fun rename(ctx: Context, id: String, name: String): ShotHistoryRecord? {
        val cleanName = name.trim().take(60)
        if (cleanName.isEmpty()) return null
        return repository(ctx).rename(id, cleanName).firstOrNull { it.id == id }
    }

    fun loadReport(ctx: Context, record: ShotHistoryRecord): ShotReport {
        val thumbDir = File(File(ctx.filesDir, THUMB_DIR), record.id)
        val shots = record.shots.map { saved ->
            val thumb = saved.thumbnailFile?.let { BitmapFactory.decodeFile(File(thumbDir, it).path) }
            ShotEvent(
                startFrame = 0,
                tMs = saved.tMs,
                type = saved.type,
                made = saved.made,
                releaseX = saved.releaseX,
                releaseY = saved.releaseY,
                distanceM = saved.distanceM,
                releaseAngle = saved.releaseAngle,
                thumb = thumb,
                courtX = saved.courtX,
                courtY = saved.courtY,
                makeMs = saved.makeMs,
                resolveMs = saved.resolveMs,
            )
        }
        return ShotReport(
            attempts = record.attempts,
            made = record.made,
            layupMade = record.layupMade,
            layupAtt = record.layupAtt,
            twoMade = record.twoMade,
            twoAtt = record.twoAtt,
            threeMade = record.threeMade,
            threeAtt = record.threeAtt,
            ftMade = record.ftMade,
            ftAtt = record.ftAtt,
            avgReleaseAngle = record.avgReleaseAngle,
            avgElbowAngle = record.avgElbowAngle,
            avgKneeAngle = record.avgKneeAngle,
            avgBallInHandMs = record.avgBallInHandMs,
            framesAnalysed = record.framesAnalysed,
            ballHitRate = record.ballHitRate,
            shots = shots,
            calibrated = record.calibrated,
            autoRim = record.autoRim,
            rimBallRatio = record.rimBallRatio,
            rimPx = record.rimPx,
            bestStreak = record.bestStreak,
            bestMissStreak = record.bestMissStreak,
            avgIntervalMs = record.avgIntervalMs,
        )
    }

    fun delete(ctx: Context, id: String) {
        repository(ctx).delete(id)
        File(File(ctx.filesDir, THUMB_DIR), id).deleteRecursively()
    }

    fun clear(ctx: Context) {
        repository(ctx).clear()
        File(ctx.filesDir, THUMB_DIR).deleteRecursively()
    }

    private fun repository(ctx: Context) = ShotHistoryRepository(File(ctx.filesDir, HISTORY_FILE))

    private fun pruneThumbnails(ctx: Context, records: List<ShotHistoryRecord>) {
        val root = File(ctx.filesDir, THUMB_DIR)
        if (!root.exists()) return
        val keep = records.mapTo(HashSet()) { it.id }
        root.listFiles()?.forEach { dir ->
            if (dir.name !in keep) dir.deleteRecursively()
        }
    }
}
