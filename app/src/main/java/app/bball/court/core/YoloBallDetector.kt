package app.bball.court.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * 自训 ball 检测器（YOLO11n → ONNX → ONNX Runtime Android）。
 *
 * 为什么不用 TFLite：ultralytics 在 Windows 上禁用了 TFLite/LiteRT 导出
 * （"only supported on Linux x86 and macOS"），ONNX 是 Windows 上唯一能导出的通用格式，
 * 而 ONNX Runtime Android 一个 AAR 就能跑，正好对应。
 *
 * 默认模型：assets/ball_yolo.onnx（416x416，单类 ball，输出 [1,5,N]）。
 * 该版本从旧 512 模型增量训练，保留 360p 高弧球召回，同时减少约三分之一网络计算。
 * 同一实现也用于 assets/ball_precision.onnx（旧 512 精准回补模型）。
 */
@Suppress("UNCHECKED_CAST")
class YoloBallDetector(
    context: Context,
    assetName: String = "ball_yolo.onnx",
    /**
     * 416 增量模型的置信度分布比旧 512 模型更保守；0.08 在实测集上把召回
     * 从旧模型 0.25 阈值时的约 61% 提升到 70%，误报率仍从约 64% 降到 60%，
     * 最终轨迹还会过滤持球、静止色块和伪弧线。
     */
    private val confThreshold: Float = 0.08f,
    private val iouThreshold: Float = 0.45f,
) : AutoCloseable {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String = "images"

    var initError: String? = null
        private set
    var inputW = 320
        private set
    var inputH = 320
        private set
    private var numAnchors = 0
    private var numClasses = 1
    private var transposed = true
    private var pixels: IntArray? = null
    private var inputData: FloatArray? = null
    private var modelBitmap: Bitmap? = null
    private var modelCanvas: Canvas? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        try {
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
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
            pixels = IntArray(inputW * inputH)
            inputData = FloatArray(3 * inputW * inputH)
            modelBitmap = Bitmap.createBitmap(inputW, inputH, Bitmap.Config.ARGB_8888)
            modelCanvas = Canvas(modelBitmap!!)
            Log.i("ShotLab", "onnx ready in=${inputW}x${inputH} anchors=${numAnchors} cls=${numClasses} transposed=${transposed}")
        } catch (t: Throwable) {
            initError = t.message ?: t.javaClass.simpleName
            Log.w("ShotLab", "onnx model unavailable: " + initError)
        }
    }

    fun detect(bitmap: Bitmap): BallSample? {
        val s = session ?: return null
        val e = env ?: return null
        val px = pixels ?: return null
        val data = inputData ?: return null
        val target = modelBitmap ?: return null
        val canvas = modelCanvas ?: return null
        val transform = Letterbox.fit(bitmap.width, bitmap.height, inputW, inputH)
        val resized = try {
            Bitmap.createScaledBitmap(bitmap, transform.resizedWidth, transform.resizedHeight, true)
        } catch (t: Throwable) {
            Log.w("ShotLab", "onnx letterbox resize failed", t)
            return null
        }
        return try {
            target.eraseColor(Color.rgb(114, 114, 114))
            canvas.drawBitmap(resized, transform.padX, transform.padY, paint)
            target.getPixels(px, 0, inputW, 0, 0, inputW, inputH)
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

            val cands = ArrayList<FloatArray>()
            if (transposed) {
                for (a in 0 until numAnchors) {
                    var best = 0f
                    for (c in 0 until numClasses) best = max(best, v[0][4 + c][a])
                    if (best < confThreshold) continue
                    cands.add(floatArrayOf(v[0][0][a], v[0][1][a], v[0][2][a], v[0][3][a], best))
                }
            } else {
                for (a in 0 until numAnchors) {
                    var best = 0f
                    for (c in 0 until numClasses) best = max(best, v[0][a][4 + c])
                    if (best < confThreshold) continue
                    cands.add(floatArrayOf(v[0][a][0], v[0][a][1], v[0][a][2], v[0][a][3], best))
                }
            }
            if (cands.isEmpty()) return null
            val best = nms(cands).maxByOrNull { it[4] } ?: return null
            val cx = transform.toSourceX(best[0])
            val cy = transform.toSourceY(best[1])
            val r = transform.toSourceRadius(best[2], best[3])
            BallSample(0L, NPt(cx, cy), best[4], r)
        } catch (t: Throwable) {
            Log.w("ShotLab", "onnx detect failed", t); null
        } finally {
            if (resized !== bitmap && !resized.isRecycled) resized.recycle()
        }
    }

    private fun nms(boxes: MutableList<FloatArray>): List<FloatArray> {
        boxes.sortByDescending { it[4] }
        val out = ArrayList<FloatArray>()
        for (b in boxes) {
            if (out.none { iou(b, it) > iouThreshold }) out.add(b)
        }
        return out
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val ax1 = a[0] - a[2] / 2; val ay1 = a[1] - a[3] / 2
        val ax2 = a[0] + a[2] / 2; val ay2 = a[1] + a[3] / 2
        val bx1 = b[0] - b[2] / 2; val by1 = b[1] - b[3] / 2
        val bx2 = b[0] + b[2] / 2; val by2 = b[1] + b[3] / 2
        val ix = max(0f, min(ax2, bx2) - max(ax1, bx1))
        val iy = max(0f, min(ay2, by2) - max(ay1, by1))
        val inter = ix * iy
        val ua = a[2] * a[3] + b[2] * b[3] - inter
        return if (ua <= 0f) 0f else inter / ua
    }

    override fun close() {
        try { modelBitmap?.recycle() } catch (_: Throwable) {}
        modelBitmap = null
        modelCanvas = null
        pixels = null
        inputData = null
        try { session?.close() } catch (_: Throwable) {}
        try { env?.close() } catch (_: Throwable) {}
    }
}
