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
 * 所以几秒就能出片，画质无损。单球预览和集锦只复用视频轨。
 */
object ClipMaker {

    /**
     * 出手预览只需要画面；音频轨在 seek 后还可能与视频轨使用不同样本基准，
     * 统一只复用视频轨，时间轴更稳定。
     */
    internal fun shouldMuxTrack(mime: String): Boolean = mime.startsWith("video/")

    internal fun shiftedPresentationUs(sampleUs: Long, anchorUs: Long, timelineUs: Long): Long =
        timelineUs + (sampleUs - anchorUs)

    internal fun firstSyncSampleIndex(syncFlags: List<Boolean>): Int =
        syncFlags.indexOfFirst { it }

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
                if (shouldMuxTrack(mime)) {
                    extractor.selectTrack(i); trackMap[i] = muxer.addTrack(fmt)
                    if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxInput = maxOf(maxInput, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }
            if (trackMap.isEmpty()) return false
            muxer.start()
            val buf = ByteBuffer.allocateDirect(maxInput)
            var timelineUs = 0L
            var wroteAny = false
            for (seg in segments) {
                val startUs = seg.first * 1000
                val endUs = seg.last * 1000
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val nextTimeline = muxSegment(
                    extractor = extractor,
                    muxer = muxer,
                    buffer = buf,
                    trackMap = trackMap,
                    endUs = endUs,
                    timelineUs = timelineUs,
                    label = "gallery:$seg",
                )
                if (nextTimeline != null) {
                    timelineUs = nextTimeline
                    wroteAny = true
                }
            }
            wroteAny
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
                if (shouldMuxTrack(mime)) {
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
            var timelineUs = 0L
            var wroteAny = false
            for (seg in segments) {
                val startUs = seg.first * 1000
                val endUs = seg.last * 1000
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val nextTimeline = muxSegment(
                    extractor = extractor,
                    muxer = muxer,
                    buffer = buf,
                    trackMap = trackMap,
                    endUs = endUs,
                    timelineUs = timelineUs,
                    label = "preview:$seg",
                )
                if (nextTimeline != null) {
                    timelineUs = nextTimeline
                    wroteAny = true
                }
            }
            if (wroteAny) {
                Log.i("ShotLab", "clip done segs=" + segments.size + " size=" + out.length() / 1024 + "KB")
            }
            wroteAny
        } catch (t: Throwable) {
            Log.w("ShotLab", "clip failed", t)
            false
        } finally {
            try { muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            try { extractor?.release() } catch (_: Throwable) {}
        }
    }

    private data class MuxSample(
        val trackIndex: Int,
        val timeUs: Long,
        val flags: Int,
        val data: ByteArray,
    )

    private fun readSegmentSamples(
        extractor: MediaExtractor,
        buffer: ByteBuffer,
        endUs: Long,
    ): List<MuxSample> {
        val samples = ArrayList<MuxSample>()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val timeUs = extractor.sampleTime
            if (timeUs > endUs) break
            if (size > 0) {
                buffer.position(0)
                val data = ByteArray(size)
                buffer.get(data, 0, size)
                samples.add(
                    MuxSample(
                        trackIndex = extractor.sampleTrackIndex,
                        timeUs = timeUs,
                        flags = extractor.sampleFlags,
                        data = data,
                    ),
                )
            }
            extractor.advance()
        }
        return samples
    }

    /**
     * HEVC 的 sampleTime 按解码顺序返回，B 帧 PTS 本身会轻微倒退。
     * 不能逐帧压成单调值，否则 5 帧会被压到 0.1ms、随后出现 133ms 空洞。
     * 以首个可解码关键帧的 PTS 为锚点统一平移，保留原始 composition time。
     */
    private fun muxSegment(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        buffer: ByteBuffer,
        trackMap: Map<Int, Int>,
        endUs: Long,
        timelineUs: Long,
        label: String,
    ): Long? {
        val allSamples = readSegmentSamples(extractor, buffer, endUs)
        if (allSamples.isEmpty()) return null
        val syncIndex = firstSyncSampleIndex(
            allSamples.map { it.flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0 },
        )
        if (syncIndex < 0) return null
        val anchorUs = allSamples[syncIndex].timeUs
        val samples = allSamples
            .subList(syncIndex, allSamples.size)
            .filter { it.timeUs >= anchorUs }
        if (samples.isEmpty()) return null
        val info = MediaCodec.BufferInfo()
        var maxPresentationUs = timelineUs - 1L
        for (sample in samples) {
            val dst = trackMap[sample.trackIndex] ?: continue
            buffer.clear()
            buffer.put(sample.data)
            buffer.position(0)
            buffer.limit(sample.data.size)
            info.offset = 0
            info.size = sample.data.size
            info.presentationTimeUs = shiftedPresentationUs(
                sample.timeUs,
                anchorUs,
                timelineUs,
            )
            info.flags = if (sample.flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            try {
                muxer.writeSampleData(dst, buffer, info)
            } catch (t: Throwable) {
                Log.w(
                    "ShotLab",
                        "clip sample failed label=$label srcTrack=${sample.trackIndex} " +
                        "dstTrack=$dst srcUs=${sample.timeUs} anchorUs=$anchorUs " +
                        "ptsUs=${info.presentationTimeUs} size=${sample.data.size} " +
                        "buffer=${buffer.capacity()} flags=${info.flags}",
                    t,
                )
                throw t
            }
            maxPresentationUs = maxOf(maxPresentationUs, info.presentationTimeUs)
        }
        return if (maxPresentationUs >= timelineUs) maxPresentationUs + 33_000L else null
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
        val key = Integer.toHexString(
            (uri.toString() + ":" + shot.tMs + ":" + shot.resolveMs + ":" + PREVIEW_CACHE_VERSION).hashCode(),
        )
        val out = File(dir, "shot_$key.mp4")
        if (out.isFile && out.length() > 0L) return out
        val segment = segmentForShot(shot)
        if (!cut(ctx, uri, listOf(segment), out)) {
            if (out.exists()) out.delete()
            return null
        }
        return out
    }

    private const val PREVIEW_CACHE_VERSION = 3
}
