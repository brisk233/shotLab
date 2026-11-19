package app.bball.court.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * Basketball = COCO class "sports ball" (EfficientDet-Lite0).
 *
 * The GPU delegate is *tried*, never required: on a device where it fails to
 * initialise MediaPipe throws from the factory, and that used to take the whole
 * screen down. CPU is ~2x slower here and always works.
 */
class BallDetector(context: Context, modelAsset: String = "efficientdet_lite0.tflite") : AutoCloseable {

    private var detector: ObjectDetector? = null
    val initError: String?

    init {
        var err: String? = null
        for (delegate in listOf(Delegate.GPU, Delegate.CPU)) {
            try {
                val base = BaseOptions.builder()
                    .setModelAssetPath(modelAsset)
                    .setDelegate(delegate)
                    .build()
                val opts = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.IMAGE)
                    .setScoreThreshold(0.20f)
                    .setMaxResults(8)
                    .build()
                detector = ObjectDetector.createFromOptions(context, opts)
                Log.i("ShotLab", "ball detector ready on " + delegate)
                break
            } catch (t: Throwable) {
                err = delegate.name + ": " + (t.message ?: t.javaClass.simpleName)
                Log.w("ShotLab", "ball detector failed on " + delegate, t)
                detector = null
            }
        }
        initError = if (detector == null) err ?: "unknown" else null
    }

    /** Best "sports ball" box in normalised coordinates, or null. Never throws. */
    fun detect(bitmap: Bitmap, minScore: Float = 0.20f): BallSample? {
        val d = detector ?: return null
        return try {
            val result = d.detect(BitmapImageBuilder(bitmap).build())
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            var best: BallSample? = null
            for (det in result.detections()) {
                val cat = det.categories().firstOrNull() ?: continue
                val name: String = cat.categoryName() ?: ""
                if (!name.contains("ball", ignoreCase = true)) continue
                val score = cat.score()
                if (score < minScore) continue
                val box = det.boundingBox()
                val cx = ((box.left + box.right) / 2f) / w
                val cy = ((box.top + box.bottom) / 2f) / h
                val rad = (Math.max(box.width(), box.height()) / 2f) / w
                if (best == null || score > best.score) best = BallSample(0L, NPt(cx, cy), score, rad)
            }
            best
        } catch (t: Throwable) {
            Log.w("ShotLab", "ball detect failed", t)
            null
        }
    }

    /** 人体框（COCO person）。用于出手判定的"离手/肩线"，比姿态关键点稳得多。 */
    fun detectPerson(bitmap: Bitmap, minScore: Float = 0.35f): NRect? {
        val d = detector ?: return null
        return try {
            val result = d.detect(BitmapImageBuilder(bitmap).build())
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            var best: NRect? = null
            var bestArea = 0f
            for (det in result.detections()) {
                val cat = det.categories().firstOrNull() ?: continue
                val name: String = cat.categoryName() ?: ""
                if (!name.contains("person", ignoreCase = true)) continue
                if (cat.score() < minScore) continue
                val box = det.boundingBox()
                val area = box.width().toFloat() * box.height().toFloat()
                if (area > bestArea) {
                    bestArea = area
                    best = NRect(
                        (box.left / w).coerceIn(0f, 1f), (box.top / h).coerceIn(0f, 1f),
                        (box.right / w).coerceIn(0f, 1f), (box.bottom / h).coerceIn(0f, 1f),
                    )
                }
            }
            best
        } catch (t: Throwable) {
            Log.w("ShotLab", "person detect failed", t); null
        }
    }

    override fun close() {
        try { detector?.close() } catch (_: Throwable) {}
    }
}
