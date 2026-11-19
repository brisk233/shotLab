package app.bball.court.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * 人体检测（yolov8n COCO，取 person 类）。
 *
 * 为什么必须有它：出手判定要"球离手 + 球在肩线以上"，这依赖一个稳定的全身人框。
 * 之前的替代方案（MediaPipe EfficientDet / 姿态关键点拼框）在远景小人上经常漏检，
 * 导致状态机无法进入 RISING —— 实测出手数只有 11 次，而 PC 端（YOLO person）能到 38 次。
 */
@Suppress("UNCHECKED_CAST")
class PersonDetector(
    context: Context,
    assetName: String = "person_yolo.onnx",
    private val confThreshold: Float = 0.30f,
    private val iouThreshold: Float = 0.45f,
) : AutoCloseable {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName = "images"
    private var inputW = 320
    private var inputH = 320
    private var numAnchors = 0
    private var numClasses = 80
    private var transposed = true

    var initError: String? = null
        private set

    init {
        try {
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                // 硬件加速：NNAPI（高通/联发科都能用），失败则保留 CPU 路径
                try { addNnapi() } catch (_: Throwable) {}
            }
            val bytes = context.assets.open(assetName).use { it.readBytes() }
            val s = e.createSession(bytes, opts)
            env = e; session = s
            inputName = s.inputNames.first()
            val info = s.inputInfo[inputName]!!.info as ai.onnxruntime.TensorInfo
            inputH = info.shape[2].toInt(); inputW = info.shape[3].toInt()
            val outInfo = s.outputInfo[s.outputNames.first()]!!.info as ai.onnxruntime.TensorInfo
            val sh = outInfo.shape
            if (sh[1] < sh[2]) { numClasses = sh[1].toInt() - 4; numAnchors = sh[2].toInt(); transposed = true }
            else { numAnchors = sh[1].toInt(); numClasses = sh[2].toInt() - 4; transposed = false }
            Log.i("ShotLab", "person ready anchors=" + numAnchors + " cls=" + numClasses)
        } catch (t: Throwable) {
            initError = t.message ?: t.javaClass.simpleName
            Log.w("ShotLab", "person model unavailable: " + initError)
        }
    }

    /** 画面里最大的那个人（归一化框），没有则 null。 */
    fun detect(bitmap: Bitmap): NRect? {
        val s = session ?: return null
        val e = env ?: return null
        val transform = Letterbox.fit(bitmap.width, bitmap.height, inputW, inputH)
        val modelBitmap = transform.toModelBitmap(bitmap)
        return try {
            val px = IntArray(inputW * inputH)
            modelBitmap.getPixels(px, 0, inputW, 0, 0, inputW, inputH)
            val data = FloatArray(3 * inputW * inputH)
            val plane = inputW * inputH
            for (i in px.indices) {
                val c = px[i]
                data[i] = ((c shr 16 and 0xFF) / 255f)
                data[plane + i] = ((c shr 8 and 0xFF) / 255f)
                data[2 * plane + i] = ((c and 0xFF) / 255f)
            }
            val tensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(data), longArrayOf(1, 3, inputH.toLong(), inputW.toLong()))
            val out = s.run(mapOf(inputName to tensor))
            val v = out[0].value as Array<Array<FloatArray>>
            out.close(); tensor.close()

            var best: FloatArray? = null
            var bestArea = 0f
            if (transposed) {
                for (a in 0 until numAnchors) {
                    if (v[0][4][a] < confThreshold) continue      // class0 = person
                    val w = v[0][2][a]; val h = v[0][3][a]
                    val area = w * h
                    if (area > bestArea) { bestArea = area; best = floatArrayOf(v[0][0][a], v[0][1][a], w, h) }
                }
            } else {
                for (a in 0 until numAnchors) {
                    if (v[0][a][4] < confThreshold) continue
                    val w = v[0][a][2]; val h = v[0][a][3]
                    val area = w * h
                    if (area > bestArea) { bestArea = area; best = floatArrayOf(v[0][a][0], v[0][a][1], w, h) }
                }
            }
            val b = best ?: return null
            val cx = transform.toSourceX(b[0])
            val cy = transform.toSourceY(b[1])
            val hw = ((transform.toSourceX(b[0] + b[2] / 2f) - transform.toSourceX(b[0] - b[2] / 2f)) / 2f)
                .coerceAtLeast(0.01f)
            val hh = ((transform.toSourceY(b[1] + b[3] / 2f) - transform.toSourceY(b[1] - b[3] / 2f)) / 2f)
                .coerceAtLeast(0.01f)
            NRect((cx - hw).coerceIn(0f, 1f), (cy - hh).coerceIn(0f, 1f), (cx + hw).coerceIn(0f, 1f), (cy + hh).coerceIn(0f, 1f))
        } catch (t: Throwable) {
            Log.w("ShotLab", "person detect failed", t); null
        } finally {
            if (!modelBitmap.isRecycled) modelBitmap.recycle()
        }
    }

    override fun close() {
        try { session?.close() } catch (_: Throwable) {}
        try { env?.close() } catch (_: Throwable) {}
    }
    /** 画面里的所有人（按面积从大到小，最多 maxN 个），用于多人场景。 */
    fun detectAll(bitmap: Bitmap, maxN: Int = 4): List<NRect> {
        val s = session ?: return emptyList()
        val e = env ?: return emptyList()
        val transform = Letterbox.fit(bitmap.width, bitmap.height, inputW, inputH)
        val modelBitmap = transform.toModelBitmap(bitmap)
        return try {
            val px = IntArray(inputW * inputH)
            modelBitmap.getPixels(px, 0, inputW, 0, 0, inputW, inputH)
            val data = FloatArray(3 * inputW * inputH)
            val plane = inputW * inputH
            for (i in px.indices) {
                val c = px[i]
                data[i] = ((c shr 16 and 0xFF) / 255f)
                data[plane + i] = ((c shr 8 and 0xFF) / 255f)
                data[2 * plane + i] = ((c and 0xFF) / 255f)
            }
            val tensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(data), longArrayOf(1, 3, inputH.toLong(), inputW.toLong()))
            val out = s.run(mapOf(inputName to tensor))
            val v = out[0].value as Array<Array<FloatArray>>
            out.close(); tensor.close()

            val found = ArrayList<Pair<Float, NRect>>()
            if (transposed) {
                for (a in 0 until numAnchors) {
                    if (v[0][4][a] < confThreshold) continue
                    val cx = v[0][0][a]; val cy = v[0][1][a]
                    val w = v[0][2][a]; val h = v[0][3][a]
                    val r = NRect(
                        transform.toSourceX(cx - w / 2),
                        transform.toSourceY(cy - h / 2),
                        transform.toSourceX(cx + w / 2),
                        transform.toSourceY(cy + h / 2),
                    )
                    val area = r.w * r.h
                    if (area < 0.004f) continue
                    if (found.none { iou(it.second, r) > iouThreshold }) found.add(area to r)
                }
            }
            found.sortByDescending { it.first }
            found.take(maxN).map { it.second }
        } catch (t: Throwable) {
            emptyList()
        } finally {
            if (!modelBitmap.isRecycled) modelBitmap.recycle()
        }
    }

    private fun iou(a: NRect, b: NRect): Float {
        val x1 = max(a.left, b.left); val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right); val y2 = min(a.bottom, b.bottom)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val ua = a.w * a.h + b.w * b.h - inter
        return if (ua <= 1e-6f) 0f else inter / ua
    }
}
