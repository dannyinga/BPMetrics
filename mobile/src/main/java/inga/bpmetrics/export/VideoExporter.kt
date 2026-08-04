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
    private const val TAG = "VideoExporter"

    /**
     * Configuration for video export, including speed and overlay positioning.
     */
    data class VideoExportConfig(
        val imageConfig: ImageExporter.ImageExportConfig,
        val windowSizeMs: Long = 30000L,
        val frameRate: Int = 30,
        val overlayBitRate: Int = 8000000,
        val regularBitRate: Int = 2500000,
        val overlayVideoUri: Uri? = null,
        val graphRect: RectF = RectF(0f, 0f, 1f, 1f),
        val lockAspectRatio: Boolean = true,
        val syncOffsetMs: Long = 0L,
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
        val fps = config.frameRate

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

        if (config.overlayVideoUri != null) {
            mediaUri = config.overlayVideoUri
            inputMimeType = context.contentResolver.getType(mediaUri)
            isInputImage = inputMimeType?.startsWith("image/") == true ||
                    mediaUri.path?.lowercase()?.let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") } == true
        } else {
            val blackBitmap = createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.BLACK)
            }
            val tempImageFile = File(context.cacheDir, "black_bg.png")
            FileOutputStream(tempImageFile).use { blackBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
                        config.syncOffsetMs
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
        effectList.add(FrameDropEffect.createDefaultFrameDropEffect(config.frameRate.toFloat()))
        
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
                exportException = e
            }

            if (exportException != null) throw exportException
            outputFile
        }
    }

    /**
     * Estimates the video start time with high precision.
     */
    fun getVideoStartTime(context: Context, uri: Uri): Long? {
        var isQuickTime = false
        val mimeType = try { context.contentResolver.getType(uri) } catch (e: Exception) { null }
        if (mimeType?.contains("video/quicktime") == true) isQuickTime = true

        if (uri.scheme == "content") {
            val projection = arrayOf(
                android.provider.MediaStore.Video.VideoColumns.DATE_TAKEN,
                android.provider.MediaStore.Video.VideoColumns.DURATION,
                android.provider.MediaStore.Video.VideoColumns.DISPLAY_NAME
            )
            try {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val dateTaken = cursor.getLong(0)
                        val durationMs = cursor.getLong(1)
                        val displayName = cursor.getString(2)
                        if (!isQuickTime && displayName?.lowercase()?.endsWith(".mov") == true) isQuickTime = true
                        if (dateTaken > 0) return if (isQuickTime) dateTaken else dateTaken - durationMs
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query MediaStore for date_taken", e)
            }
        } else if (uri.path?.lowercase()?.endsWith(".mov") == true) {
            isQuickTime = true
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
                    if (format.endsWith("'Z'")) sdf.timeZone = TimeZone.getTimeZone("UTC")
                    creationTime = sdf.parse(dateStr)?.time
                    if (creationTime != null) break
                } catch (e: Exception) {}
            }
            if (isQuickTime) creationTime else creationTime?.let { it - durationMs }
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
        globalSyncOffsetMs: Long
    ): Pair<Long, Long> {
        val sessionStartTs = timelineOriginMs
        val retriever = MediaMetadataRetriever()
        var videoDurationMs = 0L
        var videoStartTs: Long? = null
        try {
            retriever.setDataSource(context, videoUri)
            videoDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            videoStartTs = getVideoStartTime(context, videoUri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve video metadata for alignment", e)
        } finally { retriever.release() }

        val actualVideoStartTs = videoStartTs ?: sessionStartTs
        val alignedStart = (actualVideoStartTs - sessionStartTs) + globalSyncOffsetMs
        val alignedEnd = alignedStart + videoDurationMs
        return Pair(alignedStart, alignedEnd)
    }

    /**
     * Queries the MediaStore for videos that overlap with the heart rate record.
     */
    fun getOverlappingVideos(context: Context, record: BpmRecord): List<Uri> =
        getOverlappingVideos(context, listOf(record))

    /**
     * Queries the MediaStore for videos overlapping any of [records].
     *
     * A multi-record export spans from the earliest session to the latest, so a video worth
     * suggesting may overlap only one of them — searching a single record's window would miss it.
     */
    fun getOverlappingVideos(context: Context, records: List<BpmRecord>): List<Uri> {
        if (records.isEmpty()) return emptyList()

        val uris = mutableListOf<Uri>()
        val recStart = records.minOf { it.metadata.startTime }
        val recEnd = records.maxOf { it.metadata.startTime + it.metadata.durationMs }
        val projection = arrayOf(
            android.provider.MediaStore.Video.Media._ID,
            android.provider.MediaStore.Video.Media.DATE_TAKEN,
            android.provider.MediaStore.Video.Media.DURATION
        )
        val selection = "${android.provider.MediaStore.Video.Media.DATE_TAKEN} <= ? AND ${android.provider.MediaStore.Video.Media.DATE_TAKEN} >= ?"
        val selectionArgs = arrayOf((recEnd + 60000).toString(), (recStart - 60000).toString())
        try {
            context.contentResolver.query(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs,
                "${android.provider.MediaStore.Video.Media.DATE_TAKEN} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID)
                while (cursor.moveToNext()) {
                    uris.add(android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idCol)))
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Error querying overlapping videos", e) }
        return uris
    }

    fun hasVideoPermissions(context: Context): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_VIDEO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun getVideoPermissionString(): String = android.Manifest.permission.READ_MEDIA_VIDEO
}
