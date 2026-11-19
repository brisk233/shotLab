package app.bball.court.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

/** BlazePose 33 landmarks; GPU first, CPU fallback, never throws. */
class PoseEstimator(context: Context, modelAsset: String = "pose_landmarker_lite.task") : AutoCloseable {

    private var landmarker: PoseLandmarker? = null
    val initError: String?

    init {
        var err: String? = null
        for (delegate in listOf(Delegate.GPU, Delegate.CPU)) {
            try {
                val base = BaseOptions.builder().setModelAssetPath(modelAsset).setDelegate(delegate).build()
                val opts = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(0.5f)
                    .setMinPosePresenceConfidence(0.5f)
                    .build()
                landmarker = PoseLandmarker.createFromOptions(context, opts)
                Log.i("ShotLab", "pose ready on " + delegate)
                break
            } catch (t: Throwable) {
                err = delegate.name + ": " + (t.message ?: t.javaClass.simpleName)
                Log.w("ShotLab", "pose failed on " + delegate, t)
                landmarker = null
            }
        }
        initError = if (landmarker == null) err ?: "unknown" else null
    }

    data class Pose(
        val shoulderL: NPt, val shoulderR: NPt,
        val elbowL: NPt, val elbowR: NPt,
        val wristL: NPt, val wristR: NPt,
        val hipL: NPt, val hipR: NPt,
        val kneeL: NPt, val kneeR: NPt,
        val ankleL: NPt, val ankleR: NPt,
    ) {
        fun nearerWrist(p: NPt): NPt {
            val dl = kotlin.math.hypot(wristL.x - p.x, wristL.y - p.y)
            val dr = kotlin.math.hypot(wristR.x - p.x, wristR.y - p.y)
            return if (dl <= dr) wristL else wristR
        }
        fun sameChain(p: NPt): Triple<NPt, NPt, NPt> {
            val dl = kotlin.math.hypot(wristL.x - p.x, wristL.y - p.y)
            val dr = kotlin.math.hypot(wristR.x - p.x, wristR.y - p.y)
            return if (dl <= dr) Triple(shoulderL, elbowL, wristL) else Triple(shoulderR, elbowR, wristR)
        }
        fun kneeAngle(): Float? {
            val l = Geometry.angleDeg(hipL, kneeL, ankleL)
            val r = Geometry.angleDeg(hipR, kneeR, ankleR)
            return listOf(l, r).filter { it > 1f }.minOrNull()
        }
    }

    fun detect(bitmap: Bitmap): Pose? {
        val l = landmarker ?: return null
        return try {
            val res = l.detect(BitmapImageBuilder(bitmap).build())
            val lm = res.landmarks().firstOrNull() ?: return null
            if (lm.size < 33) return null
            fun p(i: Int) = NPt(lm[i].x(), lm[i].y())
            Pose(
                shoulderL = p(11), shoulderR = p(12),
                elbowL = p(13), elbowR = p(14),
                wristL = p(15), wristR = p(16),
                hipL = p(23), hipR = p(24),
                kneeL = p(25), kneeR = p(26),
                ankleL = p(27), ankleR = p(28),
            )
        } catch (t: Throwable) {
            Log.w("ShotLab", "pose detect failed", t)
            null
        }
    }

    override fun close() {
        try { landmarker?.close() } catch (_: Throwable) {}
    }
}
