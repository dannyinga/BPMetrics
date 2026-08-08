package inga.bpmetrics.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.graphics.createBitmap
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.FrameDropEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import inga.bpmetrics.library.BpmRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import android.graphics.RectF
import kotlin.math.roundToInt

/**
 * Handles video export for BPM records using Media3 Transformer.
 */
@OptIn(UnstableApi::class)
object VideoExporter {

    /**
     * Roughly how much room a render needs, as a multiple of its expected size.
     *
     * Generous, because the staged copy in the cache and the finished copy at its destination both
     * exist at once, and an encoder that runs out of room part way leaves a mess rather than an
     * error.
     */
    private const val SPACE_SAFETY_FACTOR = 2.5
    private const val TAG = "VideoExporter"

    /**
     * Configuration for video export, including speed and overlay positioning.
     */
    data class VideoExportConfig(
        val imageConfig: ImageExporter.ImageExportConfig,
        val windowSizeMs: Long = 30000L,
        val frameRate: Int = 30,
        /**
         * Take the frame rate from the footage instead of [frameRate].
         *
         * Resolved per clip at render time rather than once for the batch, because a set of clips
         * off one phone can still mix 30 and 60 — a slow-motion shot among ordinary ones is the
         * usual way. Re-encoding 60fps footage to 30 throws away half the frames it was filmed for.
         */
        val matchSourceFrameRate: Boolean = false,
        val overlayBitRate: Int = 8000000,
        val regularBitRate: Int = 2500000,
        val overlayVideoUri: Uri? = null,
        val graphRect: RectF = RectF(0f, 0f, 1f, 1f),
        val lockAspectRatio: Boolean = true,
        val syncOffsetMs: Long = 0L,
        /**
         * When [overlayVideoUri] started filming, if the caller already knows.
         *
         * The clip picker resolves this once, shows it, and cuts the preview against it. Passing it
         * down means the render cannot reach a different answer than the preview did — the two
         * working it out separately is precisely how they came to disagree.
         */
        val overlayStartedAtMs: Long? = null,
        val records: List<BpmRecord> = emptyList()
    )

    /**
     * Exports the BPM record as a video using Media3 Transformer.
     */
    suspend fun exportVideo(
        context: Context,
        record: BpmRecord,
        config: VideoExportConfig,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        // 1. Setup File
        val sanitizedTitle = record.metadata.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").replace(" ", "_")
        val fileName = "${sanitizedTitle}.mp4"
        val outputFile = File(context.cacheDir, fileName)
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        val startTimeMs = config.imageConfig.startTimeMs
        val endTimeMs = config.imageConfig.endTimeMs
        val totalDataDurationMs = (endTimeMs - startTimeMs).coerceAtLeast(1000L)

        // Place every record on one timeline up front. Doing it here rather than per frame keeps
        // the overlay cheap, and — more importantly — means the video is clipped against exactly
        // the same origin the graph is drawn against, so all records stay in sync with it.
        val exportRecords = config.records.ifEmpty { listOf(record) }
        val alignedRecords = if (exportRecords.size > 1) {
            ImageExporter.alignRecords(exportRecords, config.imageConfig.alignByElapsedTime)
        } else {
            null
        }
        val timelineOriginMs = alignedRecords?.timeline?.originWallClockMs ?: record.metadata.startTime

        var isInputImage = false
        val mediaUri: Uri
        val inputMimeType: String?

        // 2. Handle Background and Dynamic Bitrate based on output dimensions and fps
        val width = config.imageConfig.width
        val height = config.imageConfig.height
        // Matched to the footage when asked for, falling back to the stated rate when the file will
        // not say — a clip whose metadata is unreadable is not a reason to refuse the export.
        val fps = if (config.matchSourceFrameRate && config.overlayVideoUri != null) {
            getVideoFrameRate(context, config.overlayVideoUri)?.coerceIn(1, 120)
                ?: config.frameRate
        } else {
            config.frameRate
        }

        // Scale target bitrate dynamically based on total pixels and frame rate:
        // Overlay video gets ~0.13 bits per pixel per frame (e.g. ~8 Mbps for 1080p @ 30fps)
        // Solid black background gets ~0.04 bits per pixel per frame (e.g. ~2.5 Mbps for 1080p @ 30fps)
        val bitsPerPixel = if (config.overlayVideoUri != null) 0.13 else 0.04
        val calculatedBitrate = (width.toLong() * height.toLong() * fps.toLong() * bitsPerPixel).toLong()

        // Clamp to sensible ranges (Overlay: 2 Mbps to 50 Mbps; Solid: 1 Mbps to 15 Mbps)
        val targetBitrate = if (config.overlayVideoUri != null) {
            calculatedBitrate.coerceIn(2_000_000L, 50_000_000L).toInt()
        } else {
            calculatedBitrate.coerceIn(1_000_000L, 15_000_000L).toInt()
        }

        // Refuse a render there is plainly no room for, rather than discovering it part way and
        // leaving the phone worse off than before it started. The bitrate and duration say what
        // the finished video will weigh, which is all this needs to know.
        val estimatedBytes = (targetBitrate.toLong() / 8) * (totalDataDurationMs / 1000).coerceAtLeast(1)
        val requiredBytes = (estimatedBytes * SPACE_SAFETY_FACTOR).toLong()
        val freeBytes = context.cacheDir.usableSpace
        if (freeBytes < requiredBytes) {
            throw IOException(
                "Not enough space to export: about ${requiredBytes / 1_000_000}MB needed, " +
                    "${freeBytes / 1_000_000}MB free. Free up some space and try again."
            )
        }

        if (config.overlayVideoUri != null) {
            mediaUri = config.overlayVideoUri
            inputMimeType = context.contentResolver.getType(mediaUri)
            isInputImage = inputMimeType?.startsWith("image/") == true ||
                    mediaUri.path?.lowercase()?.let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") } == true
        } else {
            // What a video with no footage behind it is drawn on. The app's surface rather than
            // black, so an export with nothing filmed looks like it came from this app instead of
            // from a default.
            val backdrop = createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
                eraseColor(inga.bpmetrics.ui.theme.BpmPalette.SURFACE)
            }
            val tempImageFile = File(context.cacheDir, "export_backdrop.png")
            FileOutputStream(tempImageFile).use { backdrop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            mediaUri = Uri.fromFile(tempImageFile)
            inputMimeType = MimeTypes.IMAGE_PNG
            isInputImage = true
        }

        val outputDurationMs = totalDataDurationMs

        // 3. Build MediaItem
        val mediaItem = MediaItem.Builder()
            .setUri(mediaUri)
            .setMimeType(inputMimeType)
            .apply {
                if (isInputImage) {
                    setImageDurationMs(outputDurationMs)
                } else {
                    val alignment = calculateVideoAlignment(
                        context,
                        mediaUri,
                        timelineOriginMs,
                        config.syncOffsetMs,
                        config.overlayStartedAtMs
                    )
                    val videoStartRelativeMs = alignment.first
                    val videoEndRelativeMs = alignment.second
                    val videoDurationMs = videoEndRelativeMs - videoStartRelativeMs

                    val clipStartMs = (startTimeMs - videoStartRelativeMs).coerceIn(0L, videoDurationMs)
                    val clipEndMs = (endTimeMs - videoStartRelativeMs).coerceIn(0L, videoDurationMs)
                    val finalClipEndMs = maxOf(clipStartMs + 1000L, clipEndMs).coerceAtMost(videoDurationMs)

                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clipStartMs)
                            .setEndPositionMs(finalClipEndMs)
                            .build()
                    )
                }
            }
            .build()

        // 4. Effects
        val canvasOverlay = object : BitmapOverlay() {
            private val overlaySize = androidx.media3.common.util.Size(config.imageConfig.width, config.imageConfig.height)
            private val reusableBitmap = createBitmap(config.imageConfig.width, config.imageConfig.height, Bitmap.Config.ARGB_8888)
            private val reusableCanvas = Canvas(reusableBitmap)

            override fun getTextureSize(presentationTimeUs: Long) = overlaySize

            override fun getBitmap(presentationTimeUs: Long): Bitmap {
                reusableCanvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                val currentRecordAbsoluteTimeMs = startTimeMs + ((presentationTimeUs / 1000.0))
                if (alignedRecords != null) {
                    ImageExporter.renderAlignedRecordsOnCanvas(
                        canvas = reusableCanvas,
                        aligned = alignedRecords,
                        config = config.imageConfig,
                        currentTimeMs = currentRecordAbsoluteTimeMs,
                        windowSizeMs = config.windowSizeMs,
                        graphRect = config.graphRect
                    )
                } else {
                    ImageExporter.renderOnCanvas(
                        canvas = reusableCanvas,
                        record = record,
                        config = config.imageConfig,
                        currentTimeMs = currentRecordAbsoluteTimeMs,
                        windowSizeMs = config.windowSizeMs,
                        graphRect = config.graphRect
                    )
                }
                return reusableBitmap
            }
        }

        val effectList = mutableListOf<Effect>()
        
        // Add FrameDropEffect to ensure the output matches the requested frame rate
        // especially when the source video has a higher frame rate (e.g. 60 -> 30).
        effectList.add(FrameDropEffect.createDefaultFrameDropEffect(fps.toFloat()))
        
        effectList.add(Presentation.createForWidthAndHeight(
            config.imageConfig.width,
            config.imageConfig.height,
            Presentation.LAYOUT_SCALE_TO_FIT
        ))
        effectList.add(OverlayEffect(listOf(canvasOverlay)))

        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), effectList))
            .setRemoveAudio(isInputImage)
            .build()

        // 6. Start Export on Main Thread
        return@withContext withContext(Dispatchers.Main) {
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder()
                                .setBitrate(targetBitrate)
                                .setBitrateMode(android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                                .build()
                        )
                        .build()
                )
                .build()

            val composition = Composition.Builder(
                listOf(EditedMediaItemSequence(listOf(editedMediaItem)))
            )
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build()

            val deferred = CompletableDeferred<Unit>()
            var exportException: Exception? = null

            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    deferred.complete(Unit)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, e: ExportException) {
                    exportException = e
                    deferred.complete(Unit)
                }
            })

            try {
                transformer.start(composition, outputFile.absolutePath)

                while (deferred.isActive) {
                    val progressHolder = ProgressHolder()
                    val state = transformer.getProgress(progressHolder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        val progress = (progressHolder.progress / 100f) * 0.99f
                        onProgress(progress)
                    }
                    delay(200)
                }
                if (exportException == null) onProgress(1.0f)
            } catch (e: Exception) {
                // Cancellation arrives here too. Stop the encoder rather than leaving it writing
                // to a file nobody is waiting for any more.
                runCatching { transformer.cancel() }
                exportException = e
            }

            if (exportException != null) {
                // Take the part-written video with us. A render abandoned half way is still a
                // video-sized file, and the usual reason for abandoning one is that the phone is
                // running out of room — so keeping it is precisely the wrong thing to do.
                val abandoned = outputFile.length()
                if (outputFile.delete()) {
                    Log.d(TAG, "Discarded ${abandoned / 1024}KB of unfinished export")
                }
                throw exportException
            }
            outputFile
        }
    }

    /**
     * When a video started filming, as best it can be established.
     *
     * Delegates the judgement to [VideoTiming], which corroborates the file's stamp against its
     * modification time rather than guessing from the container. The old rule here — treat
     * `DATE_TAKEN` as the last frame for MP4 and the first frame for QuickTime — disagreed with
     * [getOverlappingClips], which has always read the same column as the first frame. Every MP4
     * export was therefore placed one clip-duration away from where its own preview said it was.
     */
    fun getVideoStartTime(context: Context, uri: Uri): Long? = resolveStamp(context, uri)?.startedAtMs

    /**
     * The full reading of a video's timing, including how much it is worth believing.
     *
     * Prefers MediaStore, which the scanner has already normalised, and falls back to the file's
     * own metadata for a video handed over by a picker that MediaStore has no row for.
     */
    fun resolveStamp(context: Context, uri: Uri): VideoTiming.Stamp? {
        if (uri.scheme == "content") {
            val projection = arrayOf(
                android.provider.MediaStore.Video.VideoColumns.DATE_TAKEN,
                android.provider.MediaStore.Video.VideoColumns.DURATION,
                android.provider.MediaStore.Video.VideoColumns.DATE_MODIFIED
            )
            try {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val stamp = VideoTiming.resolve(
                            dateTakenMs = cursor.getLong(0),
                            durationMs = cursor.getLong(1),
                            // Seconds in MediaStore, milliseconds everywhere else here.
                            dateModifiedMs = cursor.getLong(2) * 1000L
                        )
                        if (stamp != null) return stamp
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query MediaStore for date_taken", e)
            }
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val dateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) ?: return null
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val formats = arrayOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss", "EEE MMM dd HH:mm:ss zzz yyyy")
            var creationTime: Long? = null
            for (format in formats) {
                try {
                    val sdf = SimpleDateFormat(format, Locale.US)
                    // METADATA_KEY_DATE is defined as UTC whether or not the file spells the zone
                    // out. Parsing the bare form in the device's own zone shifted every such video
                    // by the local offset — the exact failure the zone correction exists to undo,
                    // introduced here rather than by the phone that filmed it.
                    if (!format.contains("zzz")) sdf.timeZone = TimeZone.getTimeZone("UTC")
                    creationTime = sdf.parse(dateStr)?.time
                    if (creationTime != null) break
                } catch (e: Exception) {}
            }
            creationTime?.let {
                // No MediaStore row means no mtime to corroborate against, so this is the stamp at
                // face value — which VideoTiming reports as assumed rather than measured.
                VideoTiming.resolve(dateTakenMs = it, durationMs = durationMs, dateModifiedMs = 0L)
            }
        } catch (e: Exception) { null } finally { retriever.release() }
    }

    /**
     * Extracts the raw capture time from the video metadata.
     */
    fun getVideoCaptureTime(context: Context, uri: Uri): Long? {
        if (uri.scheme == "content") {
            val projection = arrayOf(android.provider.MediaStore.Video.VideoColumns.DATE_TAKEN)
            try {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val dateTaken = cursor.getLong(0)
                        if (dateTaken > 0) return dateTaken
                    }
                }
            } catch (e: Exception) {}
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val dateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) ?: return null
            ExportUtils.parseDateToMillis(dateStr)
        } catch (e: Exception) { null } finally { retriever.release() }
    }

    /**
     * Extracts the frame rate of a video with rounding.
     */
    fun getVideoFrameRate(context: Context, uri: Uri): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val captureFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            if (captureFps != null && captureFps > 0) return captureFps.roundToInt()

            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            if (durationMs > 0 && frameCount != null && frameCount > 0) {
                ((frameCount * 1000.0) / durationMs).roundToInt()
            } else null
        } catch (e: Exception) { null } finally { retriever.release() }
    }

    /**
     * Calculates where a video sits on a single record's timeline.
     */
    fun calculateVideoAlignment(
        context: Context,
        record: BpmRecord,
        videoUri: Uri,
        globalSyncOffsetMs: Long
    ): Pair<Long, Long> = calculateVideoAlignment(
        context = context,
        videoUri = videoUri,
        timelineOriginMs = record.metadata.startTime,
        globalSyncOffsetMs = globalSyncOffsetMs
    )

    /**
     * Calculates where a video sits on a timeline whose position 0 is [timelineOriginMs].
     *
     * For a multi-record export the origin is [ImageExporter.Timeline.originWallClockMs] rather
     * than any one record's start, so a single video lines up with every record on the graph.
     *
     * @return the video's start and end, as offsets from the timeline origin.
     */
    fun calculateVideoAlignment(
        context: Context,
        videoUri: Uri,
        timelineOriginMs: Long,
        globalSyncOffsetMs: Long,
        /** The resolved start, when a caller has already established one. */
        knownStartMs: Long? = null
    ): Pair<Long, Long> {
        val sessionStartTs = timelineOriginMs
        val retriever = MediaMetadataRetriever()
        var videoDurationMs = 0L
        try {
            retriever.setDataSource(context, videoUri)
            videoDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve video metadata for alignment", e)
        } finally { retriever.release() }

        val videoStartTs = knownStartMs ?: getVideoStartTime(context, videoUri)
        if (videoStartTs == null) {
            // Nothing on the file says when it was filmed. Laying it against the start of the
            // recording is the only thing left, and it is a guess: it is right only if filming
            // began when the watch did. Logged loudly, because a silently guessed alignment looks
            // exactly like a measured one that is wrong.
            Log.w(TAG, "No capture time for $videoUri; assuming it began with the recording")
        }
        val actualVideoStartTs = videoStartTs ?: sessionStartTs
        val alignedStart = (actualVideoStartTs - sessionStartTs) + globalSyncOffsetMs
        val alignedEnd = alignedStart + videoDurationMs
        return Pair(alignedStart, alignedEnd)
    }

    /**
     * Queries the MediaStore for videos that overlap with the heart rate record.
     */
    /**
     * A video on the phone that was filmed while something was being recorded.
     *
     * An event is a concert; during it you might have filmed six clips. Each is its own export
     * with its own overlay, so a clip has to carry when it was filmed and for how long — "one
     * export per event" cannot express that, and a bare uri cannot either.
     */
    data class VideoClip(
        val uri: Uri,
        val startedAtMs: Long,
        val durationMs: Long,
        val displayName: String,
        /**
         * How [startedAtMs] was arrived at.
         *
         * Carried so the export uses the number the picker showed rather than working it out
         * again — the two drifting apart is what put every MP4 export a clip-length away from its
         * own preview.
         */
        val basis: VideoTiming.Basis = VideoTiming.Basis.ASSUMED
    ) {
        val endedAtMs: Long get() = startedAtMs + durationMs

        /** Whether a recording was running at any point while this was filming. */
        fun overlaps(record: BpmRecord): Boolean {
            val recordStart = record.metadata.startTime
            val recordEnd = recordStart + record.metadata.durationMs
            return recordStart <= endedAtMs && recordEnd >= startedAtMs
        }

        /**
         * Where this clip sits on a shared recording timeline.
         *
         * The single answer for both the preview and the render. They worked it out separately
         * before, which is why the sync offset moved the exported video and left the preview
         * showing the old alignment — the preview's copy of the arithmetic simply did not have the
         * offset in it. Two places computing the same number is how they come to disagree.
         *
         * @param syncOffsetMs nudges the clip along the timeline, so a positive value runs the
         *   curves ahead of the footage.
         */
        fun windowOn(timeline: ImageExporter.Timeline, syncOffsetMs: Long): ClipWindow {
            val start = (startedAtMs - timeline.originWallClockMs + syncOffsetMs)
                .coerceAtLeast(0L)
            return ClipWindow(start, (start + durationMs).coerceAtMost(timeline.durationMs))
        }
    }

    /** A span of a recording timeline, in milliseconds from its origin. */
    data class ClipWindow(val startMs: Long, val endMs: Long) {
        /** Never zero: an export of no duration is a file nobody can play. */
        val spanMs: Long get() = (endMs - startMs).coerceAtLeast(1L)
    }

    fun getOverlappingVideos(context: Context, record: BpmRecord): List<Uri> =
        getOverlappingVideos(context, listOf(record))

    /**
     * Queries the MediaStore for videos overlapping any of [records].
     *
     * A multi-record export spans from the earliest session to the latest, so a video worth
     * suggesting may overlap only one of them — searching a single record's window would miss it.
     */
    fun getOverlappingVideos(context: Context, records: List<BpmRecord>): List<Uri> =
        getOverlappingClips(context, records).map { it.uri }

    /**
     * The same query, keeping what it already reads.
     *
     * The projection has always asked for `DATE_TAKEN` and `DURATION` and then thrown both away,
     * returning bare uris. Per-clip work needs them: which people to offer on a clip depends on who
     * was recording during *that clip's* few minutes, not during the whole event, and a clip filmed
     * after someone's watch stopped must not offer their curve.
     */
    fun getOverlappingClips(context: Context, records: List<BpmRecord>): List<VideoClip> {
        if (records.isEmpty()) return emptyList()

        val clips = mutableListOf<VideoClip>()
        val recStart = records.minOf { it.metadata.startTime }
        val recEnd = records.maxOf { it.metadata.startTime + it.metadata.durationMs }
        val projection = arrayOf(
            android.provider.MediaStore.Video.Media._ID,
            android.provider.MediaStore.Video.Media.DATE_TAKEN,
            android.provider.MediaStore.Video.Media.DURATION,
            android.provider.MediaStore.Video.Media.DISPLAY_NAME,
            android.provider.MediaStore.Video.Media.DATE_MODIFIED
        )
        // A coarse prefilter, deliberately far wider than the recordings themselves. The stamp on
        // a row is not yet known to mean the start of filming — it may mark the end, or be a whole
        // timezone out — so narrowing to the recording's own window here would drop exactly the
        // clips that need correcting, before there is any chance to correct them. The precise
        // overlap test happens below, once each stamp has been resolved.
        val margin = 15 * 60 * 60_000L
        val selection = "${android.provider.MediaStore.Video.Media.DATE_TAKEN} <= ? AND ${android.provider.MediaStore.Video.Media.DATE_TAKEN} >= ?"
        val selectionArgs = arrayOf((recEnd + margin).toString(), (recStart - margin).toString())
        try {
            context.contentResolver.query(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs,
                "${android.provider.MediaStore.Video.Media.DATE_TAKEN} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID)
                val takenCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DATE_TAKEN)
                val durationCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DURATION)
                val nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DISPLAY_NAME)
                val modifiedCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val durationMs = cursor.getLong(durationCol)
                    val stamp = VideoTiming.resolve(
                        dateTakenMs = cursor.getLong(takenCol),
                        durationMs = durationMs,
                        // MediaStore keeps this column in seconds; everything else here is millis.
                        dateModifiedMs = cursor.getLong(modifiedCol) * 1000L
                    ) ?: continue
                    clips.add(
                        VideoClip(
                            uri = android.content.ContentUris.withAppendedId(
                                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                cursor.getLong(idCol)
                            ),
                            startedAtMs = stamp.startedAtMs,
                            durationMs = durationMs,
                            displayName = cursor.getString(nameCol).orEmpty(),
                            basis = stamp.basis
                        )
                    )
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Error querying overlapping videos", e) }

        // The real overlap test, now that every stamp means the same thing. Ascending, because a
        // set of clips from one evening reads as the evening it was.
        return clips
            .filter { clip -> records.any { clip.overlaps(it) } }
            .sortedBy { it.startedAtMs }
    }

    fun hasVideoPermissions(context: Context): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_VIDEO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun getVideoPermissionString(): String = android.Manifest.permission.READ_MEDIA_VIDEO
}
