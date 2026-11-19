package app.bball.court.core

import android.graphics.Bitmap
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log

/**
 * 顺序解码取帧器。
 *
 * 为什么需要它：MediaMetadataRetriever 每次取帧都要"从上一个关键帧重解"，
 * 实测 651ms/帧（优化到批量取帧后仍有 332ms/帧），占总耗时 88%。
 * 顺序解码只解一遍：整段 120s@30fps 的视频约 3600 帧，硬件解码 ~8ms/帧 → 30-40s，
 * 再加上只对采样帧做 YUV→Bitmap 转换，整体能压到 1 分钟量级。
 *
 * 用法：时间单调递增地调用 [frameAt]（分析循环本身就是递增的）。
 */
class SeqFrameReader(uri: Uri, private val outWidth: Int = 720) : AutoCloseable {

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var reader: ImageReader? = null
    private var trackIndex = -1
    private var inputDone = false
    private var outputDone = false
    private var lastWantedMs = -1L
    private val info = MediaCodec.BufferInfo()
    var initError: String? = null
        private set

    init {
        try {
            val ex = MediaExtractor()
            ex.setDataSource(uri.path ?: uri.toString())
            var vi = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) { vi = i; fmt = f; break }
            }
            if (vi < 0 || fmt == null) throw IllegalStateException("没有视频轨")
            ex.selectTrack(vi)
            trackIndex = vi
            extractor = ex

            val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
            val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            val ir = ImageReader.newInstance(w, h, android.graphics.ImageFormat.YUV_420_888, 4)
            reader = ir
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            c.configure(fmt, ir.surface, null, 0)
            c.start()
            codec = c
        } catch (t: Throwable) {
            initError = t.message ?: t.javaClass.simpleName
            Log.w("ShotLab", "seq reader init failed: " + initError)
        }
    }

    /** 取 tMs 时刻（或其后最近）的帧。时间必须单调不减。 */
    fun frameAt(tMs: Long): Bitmap? {
        val c = codec ?: return null
        val ex = extractor ?: return null
        val ir = reader ?: return null
        if (outputDone) return null
        if (tMs < lastWantedMs) return null
        lastWantedMs = tMs
        val wantUs = tMs * 1000

        try {
            while (true) {
                // 1) 灌数据
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                // 2) 取输出
                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val isImage = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0
                    c.releaseOutputBuffer(outIdx, isImage)
                    if (isImage) {
                        val img = ir.acquireNextImage()
                        if (img != null) {
                            val ptsUs = img.timestamp
                            if (ptsUs >= wantUs) {
                                val bmp = toBitmap(img, outWidth)
                                img.close()
                                return bmp
                            }
                            img.close()
                        }
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                        return null
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    continue
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    outputDone = true
                    return null
                }
            }
        } catch (t: Throwable) {
            Log.w("ShotLab", "seq frame failed at " + tMs, t)
            outputDone = true
            return null
        }
    }

    /** YUV_420_888 → ARGB Bitmap（BT.601），并按 outWidth 等比缩放。 */
    private fun toBitmap(img: Image, outW: Int): Bitmap? {
        val w = img.width
        val h = img.height
        val yPlane = img.planes[0]
        val uPlane = img.planes[1]
        val vPlane = img.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixStride = uPlane.pixelStride

        val argb = IntArray(w * h)
        for (j in 0 until h) {
            val yBase = j * yRowStride
            val uvBase = (j / 2) * uvRowStride
            for (i in 0 until w) {
                val y = yBuf.get(yBase + i).toInt() and 0xFF
                val uvIdx = uvBase + (i / 2) * uvPixStride
                val u = (uBuf.get(uvIdx).toInt() and 0xFF) - 128
                val v = (vBuf.get(uvIdx).toInt() and 0xFF) - 128
                val r = (y + 1.402 * v).toInt().coerceIn(0, 255)
                val g = (y - 0.344136 * u - 0.714136 * v).toInt().coerceIn(0, 255)
                val b = (y + 1.772 * u).toInt().coerceIn(0, 255)
                argb[j * w + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        full.setPixels(argb, 0, w, 0, 0, w, h)
        if (w <= outW) return full
        val scaled = Bitmap.createScaledBitmap(full, outW, (h * outW / w).coerceAtLeast(1), true)
        full.recycle()
        return scaled
    }

    override fun close() {
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { extractor?.release() } catch (_: Throwable) {}
    }
}
