package app.bball.court.core

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * 篮筐训练样本采集：每当用户确认一次筐框，就保存一帧裁图 + YOLO 标签。
 *
 * 为什么这样做：筐的外观差异极大（橙圈 / 暗棕金属 / 红漆），颜色阈值和零样本检测都不可靠。
 * 唯一可靠的标签来源就是"人工确认过位置"的那一帧。用 A 的确认动作给 B 攒数据，
 * 攒够几百条就能训出真正通用的篮筐检测器——不需要额外找人标注。
 */
object RimSampleStore {

    private const val DIR = "hoop_samples"

    /** 保存一条样本；同一机位（指纹相同）只存一次，避免重复。 */
    fun save(ctx: Context, frame: Bitmap, rim: NRect, tag: String = ""): Boolean {
        return try {
            val dir = File(ctx.getExternalFilesDir(null), DIR)
            if (!dir.exists() && !dir.mkdirs()) return false
            val sig = SceneCut.signature(frame).joinToString(",") { it.toInt().toString() }
            val key = Integer.toHexString(sig.hashCode())
            val imgFile = File(dir, key + ".jpg")
            if (imgFile.exists()) return false          // 同机位已采过

            // 裁一块比筐大 6 倍的区域（保留篮板/支架上下文，利于训练）
            val w = frame.width; val h = frame.height
            val padX = (rim.w * w * 3f).toInt()
            val padY = (rim.w * w * 2f).toInt()
            val l = ((rim.left * w) - padX).toInt().coerceIn(0, w - 2)
            val t = ((rim.top * h) - padY).toInt().coerceIn(0, h - 2)
            val r = ((rim.right * w) + padX).toInt().coerceIn(l + 2, w)
            val b = ((rim.bottom * h) + padY).toInt().coerceIn(t + 2, h)
            val crop = Bitmap.createBitmap(frame, l, t, r - l, b - t)
            FileOutputStream(imgFile).use { crop.compress(Bitmap.CompressFormat.JPEG, 92, it) }

            // 标签（相对裁图归一化）
            val cw = (r - l).toFloat(); val ch = (b - t).toFloat()
            val cx = ((rim.cx * w) - l) / cw
            val cy = ((rim.cy * h) - t) / ch
            val bw = (rim.w * w) / cw
            val bh = (rim.h * h) / ch
            File(dir, key + ".txt").writeText("0 %.6f %.6f %.6f %.6f\n".format(cx, cy, bw, bh))
            File(dir, key + ".meta").writeText("tag=" + tag + "\nrim=" + rim.left + "," + rim.top + "," + rim.right + "," + rim.bottom + "\n")
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun count(ctx: Context): Int {
        val dir = File(ctx.getExternalFilesDir(null), DIR)
        return dir.listFiles { f -> f.name.endsWith(".txt") }?.size ?: 0
    }
}
