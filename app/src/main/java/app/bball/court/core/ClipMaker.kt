package app.bball.court.core

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.nio.ByteBuffer

/**
 * 命中集锦：把同一个视频里的若干片段（毫秒区间）按顺序拼成一个 mp4。
 *
 * 不做重编码 —— 直接用 MediaExtractor 抽样本、MediaMuxer 顺序复用到新容器，
 * 所以几秒就能出片，画质无损。前提是所有片段来自同一个源视频（正是本场景）。
 */
object ClipMaker {

    /**
     * 写到系统相册（Movies/ShotLab），用户能在相册/文件管理器里直接看到并分享。
     * Android 10+ 用 MediaStore 插入，MediaMuxer 直接写 FileDescriptor。
     * @return 成功返回展示用路径
     */
    fun cutToGallery(ctx: android.content.Context, uri: Uri, segments: List<LongRange>, name: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return cutToGalleryApi29(ctx, uri, segments, name)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun cutToGalleryApi29(
        ctx: android.content.Context,
        uri: Uri,
        segments: List<LongRange>,
        name: String,
    ): String? {
        if (segments.isEmpty()) return null
        val resolver = ctx.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/ShotLab")
            put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
        }
        val out = resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        var ok = false
        try {
            resolver.openFileDescriptor(out, "rw")?.use { pfd ->
                ok = cutToFd(ctx, uri, segments, pfd.fileDescriptor)
            }
            values.clear()
            values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(out, values, null, null)
        } catch (t: Throwable) {
            Log.w("ShotLab", "clip to gallery failed", t)
        }
        return if (ok) "Movies/ShotLab/" + name else null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun cutToFd(
        ctx: android.content.Context,
        srcUri: Uri,
        segments: List<LongRange>,
        fd: java.io.FileDescriptor,
    ): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            // MediaStore/OpenDocument 返回 content://，必须交给 ContentResolver 打开。
            extractor = MediaExtractor().apply { setDataSource(ctx, srcUri, null) }
            muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = HashMap<Int, Int>()
            var maxInput = 1 shl 20
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i); trackMap[i] = muxer.addTrack(fmt)
                    if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxInput = maxOf(maxInput, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }
            if (trackMap.isEmpty()) return false
            muxer.start()
            val buf = ByteBuffer.allocateDirect(maxInput)
            val info = MediaCodec.BufferInfo()
            var timelineUs = 0L
            for (seg in segments) {
                val startUs = seg.first * 1000
                val endUs = seg.last * 1000
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var baseUs = -1L
                var lastUs = timelineUs
                while (true) {
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) break
                    val t = extractor.sampleTime
                    if (baseUs < 0) baseUs = t          // 关键帧不一定是 seg 起点，用首帧做基准
                    if (t > endUs) break
                    val dst = trackMap[extractor.sampleTrackIndex]
                    if (dst != null) {
                        info.offset = 0; info.size = size
                        info.presentationTimeUs = timelineUs + (t - baseUs)
                        lastUs = info.presentationTimeUs
                        info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        muxer.writeSampleData(dst, buf, info)
                    }
                    extractor.advance()
                }
                timelineUs = lastUs + 33000L        // 段间接 33ms，避免时间戳重叠
            }
            true
        } catch (t: Throwable) {
            Log.w("ShotLab", "cutToFd failed", t); false
        } finally {
            try { muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            try { extractor?.release() } catch (_: Throwable) {}
        }
    }

    /** @param segments 毫秒区间列表，按播放顺序 */
    fun cut(ctx: android.content.Context, uri: Uri, segments: List<LongRange>, out: File): Boolean {
        if (segments.isEmpty()) return false
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            extractor = MediaExtractor().apply { setDataSource(ctx, uri, null) }
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackMap = HashMap<Int, Int>()   // srcTrack -> muxTrack
            var maxInputSize = 1 shl 20
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    trackMap[i] = muxer.addTrack(fmt)
                    if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxInputSize = maxOf(maxInputSize, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                }
            }
            if (trackMap.isEmpty()) return false
            muxer.start()

            val buf = ByteBuffer.allocateDirect(maxInputSize)
            val info = MediaCodec.BufferInfo()
            var timelineUs = 0L
            for (seg in segments) {
                val startUs = seg.first * 1000
                val endUs = seg.last * 1000
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var baseUs = -1L
                var lastUs = timelineUs
                while (true) {
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) break
                    val t = extractor.sampleTime
                    if (baseUs < 0) baseUs = t
                    if (t > endUs) break
                    val dst = trackMap[extractor.sampleTrackIndex]
                    if (dst != null) {
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = timelineUs + (t - baseUs)
                        lastUs = info.presentationTimeUs
                        info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                            MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        muxer.writeSampleData(dst, buf, info)
                    }
                    extractor.advance()
                }
                timelineUs = lastUs + 33000L
            }
            Log.i("ShotLab", "clip done segs=" + segments.size + " size=" + out.length() / 1024 + "KB")
            true
        } catch (t: Throwable) {
            Log.w("ShotLab", "clip failed", t)
            false
        } finally {
            try { muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            try { extractor?.release() } catch (_: Throwable) {}
        }
    }

    /**
     * 出手 → 集锦区间。
     *
     * “命中集锦”只剪命中球，窗口为出手前 2.0s 到判中后 1.2s。
     * 需要全出手视频时必须显式传 onlyMade=false；重叠/相邻片段仍会合并。
     *
     * @param onlyMade 只剪命中的球
     * @param mergeGapMs 两段间隔小于这个值就合并（避免关键帧对齐造成的重复画面）
     */
    fun segmentsForShots(
        shots: List<ShotEvent>,
        preMs: Long = 2000,
        postMs: Long = 1200,
        onlyMade: Boolean = true,
        mergeGapMs: Long = 600,
    ): List<LongRange> {
        val picked = if (onlyMade) shots.filter { it.made } else shots
        if (picked.isEmpty()) return emptyList()
        val raw = picked.map { shot -> segmentForShot(shot, preMs, postMs) }.sortedBy { it.first }

        val merged = ArrayList<LongRange>()
        for (r in raw) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + mergeGapMs) {
                merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
            } else merged.add(r)
        }
        return merged
    }

    /** 单次出手预览：出手前 2.0s 到轨迹判定后 1.2s，不中球也包含完整飞行段。 */
    fun segmentForShot(
        shot: ShotEvent,
        preMs: Long = 2000,
        postMs: Long = 1200,
    ): LongRange {
        val start = (shot.tMs - preMs).coerceAtLeast(0L)
        val resolved = when {
            shot.makeMs > 0L -> shot.makeMs
            shot.resolveMs > 0L -> shot.resolveMs
            else -> shot.tMs
        }
        return start..(resolved + postMs).coerceAtLeast(start + 1)
    }

    /** 生成或复用单次出手的缓存视频，供报告页内播放。 */
    fun cutShotPreview(
        ctx: android.content.Context,
        uri: Uri,
        shot: ShotEvent,
    ): File? {
        val dir = File(ctx.cacheDir, "shot_preview")
        if (!dir.exists() && !dir.mkdirs()) return null
        val key = Integer.toHexString((uri.toString() + ":" + shot.tMs + ":" + shot.resolveMs).hashCode())
        val out = File(dir, "shot_$key.mp4")
        if (out.isFile && out.length() > 0L) return out
        val segment = segmentForShot(shot)
        if (!cut(ctx, uri, listOf(segment), out)) {
            if (out.exists()) out.delete()
            return null
        }
        return out
    }
}
