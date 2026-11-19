package app.bball.court.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 竖屏/横屏视频常见上下黑边（letterbox）。黑边占了画面高度的一半时，
 * 推理时球被缩得极小 —— 实测同一段视频：全帧检出 20%，裁掉黑边后 84%。
 *
 * 这里用"整行是否接近纯黑"判断内容区，得到一个像素矩形。
 */
object Letterbox {

    data class Transform(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val modelWidth: Int,
        val modelHeight: Int,
        val resizedWidth: Int,
        val resizedHeight: Int,
        val padX: Float,
        val padY: Float,
        val scale: Float,
    ) {
        fun toSourceX(modelX: Float): Float =
            (((modelX - padX) / scale) / sourceWidth).coerceIn(0f, 1f)

        fun toSourceY(modelY: Float): Float =
            (((modelY - padY) / scale) / sourceHeight).coerceIn(0f, 1f)

        fun toSourceRadius(modelWidthValue: Float, modelHeightValue: Float): Float {
            val width = (modelWidthValue / scale) / sourceWidth
            val height = (modelHeightValue / scale) / sourceHeight
            return ((width + height) / 4f).coerceAtLeast(0f)
        }

        fun toModelBitmap(source: Bitmap): Bitmap {
            val output = Bitmap.createBitmap(modelWidth, modelHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawColor(Color.rgb(114, 114, 114))
            val resized = Bitmap.createScaledBitmap(source, resizedWidth, resizedHeight, true)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(resized, padX, padY, paint)
            if (resized !== source) resized.recycle()
            return output
        }
    }

    /**
     * 与 YOLO 训练时的 letterbox 一致：等比缩放，居中补 114 灰边。
     * 直接拉伸到正方形会改变球的横纵比，使 Android 端准确率低于离线指标。
     */
    fun fit(sourceWidth: Int, sourceHeight: Int, modelWidth: Int, modelHeight: Int): Transform {
        require(sourceWidth > 0 && sourceHeight > 0 && modelWidth > 0 && modelHeight > 0)
        val scale = min(modelWidth.toFloat() / sourceWidth, modelHeight.toFloat() / sourceHeight)
        val resizedWidth = (sourceWidth * scale).roundToInt().coerceIn(1, modelWidth)
        val resizedHeight = (sourceHeight * scale).roundToInt().coerceIn(1, modelHeight)
        return Transform(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            modelWidth = modelWidth,
            modelHeight = modelHeight,
            resizedWidth = resizedWidth,
            resizedHeight = resizedHeight,
            padX = (modelWidth - resizedWidth) / 2f,
            padY = (modelHeight - resizedHeight) / 2f,
            scale = scale,
        )
    }

    data class Crop(val top: Int, val bottom: Int, val left: Int, val right: Int) {
        val height get() = bottom - top + 1
        val width get() = right - left + 1
    }

    fun contentCrop(bmp: Bitmap, darkThreshold: Int = 24, minContentRatio: Float = 0.15f): Crop {
        val w = bmp.width
        val h = bmp.height
        if (w < 16 || h < 16) return Crop(0, h - 1, 0, w - 1)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        fun rowIsDark(y: Int): Boolean {
            var sum = 0
            val row = y * w
            var x = 0
            while (x < w) {
                val c = px[row + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                sum += (r + g + b) / 3
                x += 8                       // 抽样，够快
            }
            val n = (w + 7) / 8
            return sum / n < darkThreshold
        }

        var top = 0
        while (top < h && rowIsDark(top)) top++
        var bottom = h - 1
        while (bottom > top && rowIsDark(bottom)) bottom--

        // 兜底：内容区太小说明判断失败，别裁
        if ((bottom - top + 1).toFloat() / h < minContentRatio || top >= bottom) {
            return Crop(0, h - 1, 0, w - 1)
        }
        return Crop(top, bottom, 0, w - 1)
    }
}
