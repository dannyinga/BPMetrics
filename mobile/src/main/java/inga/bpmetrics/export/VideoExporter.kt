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
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
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
        val syncOffsetMs: Long = 0L,
        val lockAspectRatio: Boolean = true
    )

    /**
     * Exports the BPM record as a video using Media3 Transformer.
     *
     * This method uses the [Composition] API to combine a background (either a provided video/image
     * or a default black background) with a dynamic graph overlay rendered in real-time.
     *
     * @param context Android context for file operations and Media3.
     * @param record The BPM record data to visualize.
     * @param config Configuration for the video export.
     * @param onProgress Callback invoked with the export progress (0.0 to 1.0).
     * @return The [File] pointing to the exported MP4 video.
     * @throws ExportException if the transformer fails to process the composition.
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

        var isInputImage = false
        val mediaUri: Uri
        val inputMimeType: String?

        // 2. Handle Background and Dynamic Bitrate
        // If we have an overlay video, we use a high bitrate to preserve background quality.
        // If we are exporting a "Template" (just graph on black), we use a lower bitrate for speed.
        val targetBitrate = if (config.overlayVideoUri != null) config.overlayBitRate else config.regularBitRate

        if (config.overlayVideoUri != null) {
            mediaUri = config.overlayVideoUri
            inputMimeType = context.contentResolver.getType(mediaUri)
            isInputImage = inputMimeType?.startsWith("image/") == true ||
                    mediaUri.path?.lowercase()?.let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") } == true
        } else {
            // IMPORTANT: If you want to use this in a video editor later,
            // render on pure BLACK. In your editor, set the Blend Mode to "SCREEN".
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
                }
            }
            .build()

        // 4. Effects
        val canvasOverlay = object : BitmapOverlay() {
            private val overlaySize = androidx.media3.common.util.Size(config.imageConfig.width, config.imageConfig.height)
            // Explicitly use ARGB_8888 for alpha support
            private val reusableBitmap = createBitmap(config.imageConfig.width, config.imageConfig.height, Bitmap.Config.ARGB_8888)
            private val reusableCanvas = Canvas(reusableBitmap)

            override fun getTextureSize(presentationTimeUs: Long) = overlaySize

            override fun getBitmap(presentationTimeUs: Long): Bitmap {
                // Clear the canvas to be fully transparent so the graph sits ON TOP of the background
                reusableCanvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

                val currentRecordAbsoluteTimeMs = startTimeMs + ((presentationTimeUs / 1000.0))

                ImageExporter.renderOnCanvas(
                    canvas = reusableCanvas,
                    record = record,
                    config = config.imageConfig,
                    currentTimeMs = currentRecordAbsoluteTimeMs,
                    windowSizeMs = config.windowSizeMs,
                    graphRect = config.graphRect
                )
                return reusableBitmap
            }
        }

        val effectList = mutableListOf<Effect>()

        effectList.add(Presentation.createForWidthAndHeight(
            config.imageConfig.width,
            config.imageConfig.height,
            Presentation.LAYOUT_SCALE_TO_FIT
        ))
        effectList.add(OverlayEffect(listOf(canvasOverlay)))

        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), effectList))
            .setRemoveAudio(isInputImage)
            .apply {
                if (isInputImage) {
                    setFrameRate(config.frameRate)
                }
            }
            .build()

        // 6. Start Export on Main Thread
        return@withContext withContext(Dispatchers.Main) {
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(
                    androidx.media3.transformer.DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            androidx.media3.transformer.VideoEncoderSettings.Builder()
                                .setBitrate(targetBitrate)
                                .build()
                        )
                        .build()
                )
                .build()

            val composition = Composition.Builder(
                listOf(EditedMediaItemSequence(listOf(editedMediaItem)))
            ).build()

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

                    when (state) {
                        Transformer.PROGRESS_STATE_AVAILABLE -> {
                            // Map 0-100 to 0.0-0.99 to leave room for Muxing/Finalizing
                            val progress = (progressHolder.progress / 100f) * 0.99f
                            onProgress(progress)
                        }
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
     * Tries MediaStore DATE_TAKEN first (ms precision), then falls back to metadata (sec precision).
     * Always subtracts duration as these timestamps usually mark the end of the file.
     *
     * @param context Android context.
     * @param uri The URI of the video file.
     * @return The estimated start time in milliseconds, or null if it cannot be determined.
     */
    fun getVideoStartTime(context: Context, uri: Uri): Long? {
        if (uri.scheme == "content") {
            val projection = arrayOf(
                android.provider.MediaStore.Video.VideoColumns.DATE_TAKEN,
                android.provider.MediaStore.Video.VideoColumns.DURATION
            )
            try {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val dateTaken = cursor.getLong(0)
                        val durationMs = cursor.getLong(1)
                        if (dateTaken > 0) return dateTaken - durationMs
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
                    if (format.endsWith("'Z'")) sdf.timeZone = TimeZone.getTimeZone("UTC")
                    creationTime = sdf.parse(dateStr)?.time
                    if (creationTime != null) break
                } catch (e: Exception) {}
            }

            creationTime?.let { it - durationMs }
        } catch (e: Exception) { null } finally { retriever.release() }
    }

    /**
     * Calculates the start and end offsets for a BPM record to align with a video file
     * based on absolute wall-clock start times and user calibration.
     *
     * @return A Pair where first is the startOffsetMs and second is the endOffsetMs.
     */
    /**
     * Calculates the start and end offsets for a BPM record to align with a video file
     * based on absolute wall-clock start times and user calibration.
     *
     * @return A Pair where first is the startOffsetMs and second is the endOffsetMs.
     */
    fun calculateVideoAlignment(
        context: Context,
        record: BpmRecord,
        videoUri: Uri,
        globalSyncOffsetMs: Long
    ): Pair<Long, Long> {
        // 1. Get the wall-clock time the "Start" button was pressed for the HR session
        val sessionStartTs = record.metadata.startTime

        // 2. Get the wall-clock time and duration of the video
        val retriever = MediaMetadataRetriever()
        var videoDurationMs = 0L
        var videoStartTs: Long? = null

        try {
            retriever.setDataSource(context, videoUri)
            videoDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // We use our existing helper to get the high-precision start time
            videoStartTs = getVideoStartTime(context, videoUri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve video metadata for alignment", e)
        } finally {
            retriever.release()
        }

        // Default to session start if video metadata is missing (results in 0 offset)
        val actualVideoStartTs = videoStartTs ?: sessionStartTs

        // 3. Calculate the gap: (HR_Start - Video_Start)
        // If HR started at 12:00:05 and Video started at 12:00:00, offset is +5s.
        // This means at 0:00 of the video, we show the HR data from 5s into the record.
        val alignedStart = (actualVideoStartTs - sessionStartTs) + globalSyncOffsetMs

        // 4. The end value is simply the start offset + the video duration
        // This ensures the graph timeline covers the exact span of the video
        val alignedEnd = alignedStart + videoDurationMs

        return Pair(alignedStart, alignedEnd)
    }

    /**
     * Queries the MediaStore for videos that overlap with the heart rate record.
     *
     * Overlap occurs if:
     * 1. Recording starts during Video (VideoStart <= RecStart <= VideoEnd)
     * 2. Recording ends during Video (VideoStart <= RecEnd <= VideoEnd)
     * 3. Video starts during Recording (RecStart <= VideoStart <= RecEnd)
     * 4. Video ends during Recording (RecStart <= VideoEnd <= RecEnd)
     *
     * Simplified: (vidStart <= recEnd) AND (vidEnd >= recStart)
     */
    fun getOverlappingVideos(context: Context, record: BpmRecord): List<Uri> {
        val uris = mutableListOf<Uri>()
        val recStart = record.metadata.startTime
        val recEnd = recStart + record.metadata.durationMs

        val projection = arrayOf(
            android.provider.MediaStore.Video.Media._ID,
            android.provider.MediaStore.Video.Media.DATE_TAKEN,
            android.provider.MediaStore.Video.Media.DURATION
        )

        // 1. SQL FILTER
        // We look for videos taken within 1 minute of the recording.
        val selection = "${android.provider.MediaStore.Video.Media.DATE_TAKEN} <= ? AND " +
                "${android.provider.MediaStore.Video.Media.DATE_TAKEN} >= ?"

        val selectionArgs = arrayOf(
            (recEnd + 60000).toString(), // +1 minute
            (recStart - 60000).toString() // -1 minute
        )

        try {
            context.contentResolver.query(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${android.provider.MediaStore.Video.Media.DATE_TAKEN} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID)

                // If your debugger wasn't entering here, it's because 'selection' found nothing.
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    uris.add(
                        android.content.ContentUris.withAppendedId(
                            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                        )
                    )

                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying overlapping videos", e)
        }
        return uris
    }

    /**
     * Checks if the app has the necessary permissions to query the MediaStore for videos.
     */
    fun hasVideoPermissions(context: Context): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_MEDIA_VIDEO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Returns the required permission string based on the Android version.
     */
    fun getVideoPermissionString(): String {
        return android.Manifest.permission.READ_MEDIA_VIDEO
    }
}
