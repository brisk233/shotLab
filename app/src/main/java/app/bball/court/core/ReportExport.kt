package app.bball.court.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 报告导出：CSV（写入 Download/ShotLab）+ 文本分享。
 * CSV 用 Excel 能直接打开，方便自己二次统计或给教练看。
 */
object ReportExport {

    private fun fmt(ms: Long): String {
        val s = ms / 1000.0
        return String.format(Locale.US, "%.2f", s)
    }

    fun buildCsv(r: ShotReport): String {
        val sb = StringBuilder()
        sb.append("序号,时间(s),类型,结果,距离(m),出手角(度),球场X(m),球场Y(m)\n")
        r.shots.forEachIndexed { i, s ->
            sb.append(i + 1).append(',')
                .append(fmt(s.tMs)).append(',')
                .append(s.type.name).append(',')
                .append(if (s.made) "命中" else "不中").append(',')
                .append(String.format(Locale.US, "%.2f", s.distanceM)).append(',')
                .append(s.releaseAngle?.let { String.format(Locale.US, "%.1f", it) } ?: "").append(',')
                .append(String.format(Locale.US, "%.2f", s.courtX)).append(',')
                .append(String.format(Locale.US, "%.2f", s.courtY)).append('\n')
        }
        sb.append("\n汇总\n")
        sb.append("总出手,").append(r.attempts).append('\n')
        sb.append("命中,").append(r.made).append('\n')
        sb.append("命中率,").append(if (r.attempts > 0) String.format(Locale.US, "%.1f%%", r.made * 100.0 / r.attempts) else "-").append('\n')
        sb.append("最长连中,").append(r.bestStreak).append('\n')
        sb.append("最长连铁,").append(r.bestMissStreak).append('\n')
        sb.append("平均出手间隔(s),").append(if (r.avgIntervalMs > 0) fmt(r.avgIntervalMs) else "-").append('\n')
        sb.append("上篮/两分/三分,").append(r.layupMade).append('/').append(r.twoMade).append('/').append(r.threeMade).append('\n')
        return sb.toString()
    }

    /** 写入 Download/ShotLab/report_yyyyMMdd_HHmmss.csv，返回 uri 描述或 null。 */
    fun saveCsv(ctx: Context, r: ShotReport): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return saveCsvApi29(ctx, r)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveCsvApi29(ctx: Context, r: ShotReport): String? {
        return try {
            val name = "report_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ShotLab")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(buildCsv(r).toByteArray()) }
            name
        } catch (t: Throwable) {
            null
        }
    }

    fun shareText(ctx: Context, r: ShotReport) {
        val txt = "ShotLab 分析结果\n出手 " + r.attempts + " 次，命中 " + r.made + " 次" +
                (if (r.attempts > 0) String.format(Locale.US, "，命中率 %.1f%%", r.made * 100.0 / r.attempts) else "") +
                "\n最长连中 " + r.bestStreak + "，最长连铁 " + r.bestMissStreak
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, txt) }
        ctx.startActivity(Intent.createChooser(i, "分享分析结果"))
    }
}
