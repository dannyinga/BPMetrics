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
import inga.bpmetrics.library.BpmRecordWithPoints
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
     * The item Media3 renders, with what each kind of input needs.
     *
     * An image (the plain backdrop used when there is no footage) has no frame rate of its own, and
     * Media3's image loader refuses one without it: `checkState(frameRate != RATE_UNSET_INT)`. The
     * render-queue rewrite (135a468) dropped the call, and every video without footage failed
     * with "There was an error encoding" (Audit #35). Here as a function so a test can hold it.
     */
    internal fun editedItem(
        mediaItem: MediaItem,
        effects: List<Effect>,
        isInputImage: Boolean,
        frameRate: Int
    ): EditedMediaItem = EditedMediaItem.Builder(mediaItem)
        .setEffects(Effects(emptyList(), effects))
        .setRemoveAudio(isInputImage)
        .apply { if (isInputImage) setFrameRate(frameRate) }
        .build()

    /**
     * Roughly how much room a render needs, as a multiple of its expected size.
     *
     * Generous, because the staged copy in the cache and the finished copy at its destination both
     * exist at once, and an encoder that runs out of room part way leaves a mess rather than an
     * error.
     */
    /** Room a render needs beyond the finished file: Media3 writes intermediates alongside it. */
    const val SPACE_SAFETY_FACTOR = 2.5

    /** What one render will weigh, and the free space it needs while it runs. */
    data class Sizing(val bitrate: Int, val bytes: Long) {
        val requiredFreeBytes: Long get() = (bytes * SPACE_SAFETY_FACTOR).toLong()
    }

    /**
     * The bitrate a render uses and what the file will weigh.
     *
     * One sum for the render and for step 4. Step 4 used the preset's two flat bitrates, which the
     * render never read, and showed the file's size where the render checked for 2.5 times it:
     * "about 1.9 GB" promised, then 4,670 MB required, and the job failed in seconds (Audit #38).
     *
     * Footage gets about 0.13 bits per pixel per frame (about 8 Mbps at 1080p30), clamped to 2 to
     * 50 Mbps; a plain background about 0.04 (about 2.5 Mbps), clamped to 1 to 15 Mbps.
     */
    fun sizing(width: Int, height: Int, fps: Int, hasOverlay: Boolean, durationMs: Long): Sizing {
        val bitsPerPixel = if (hasOverlay) 0.13 else 0.04
        val calculated = (width.toLong() * height.toLong() * fps.toLong() * bitsPerPixel).toLong()
        val bitrate = if (hasOverlay) calculated.coerceIn(2_000_000L, 50_000_000L).toInt()
            else calculated.coerceIn(1_000_000L, 15_000_000L).toInt()
        val bytes = (bitrate.toLong() / 8) * (durationMs / 1000).coerceAtLeast(1)
        return Sizing(bitrate, bytes)
    }

    /** The frame rate a render will use: the footage's when asked for and readable, else the set one. */
    private fun renderFrameRate(context: Context, request: ExportRequest.Video): Int =
        if (request.preset.matchSourceFrameRate && request.clip != null) {
            getVideoFrameRate(context, request.clip)?.coerceIn(1, 120) ?: request.preset.frameRate
        } else {
            request.preset.frameRate
        }

    /**
     * Why this render can't start, in words for the queue and the notification, or null if it can.
     *
     * The render runs it first, and so does Retry before queueing a job again, so a job that
     * failed for want of space isn't put back only to fail the same way (Audit §3 Export).
     */
    fun preflightProblem(context: Context, request: ExportRequest.Video): String? {
        request.clip?.let { clip ->
            val readable = runCatching {
                context.contentResolver.openFileDescriptor(clip, "r")?.use { true } ?: false
            }.getOrDefault(false)
            if (!readable) {
                return "The clip it was made from can't be opened. It may have been deleted, or " +
                    "BPMetrics can no longer see it."
            }
        }
        val durationMs = (request.content.endMs - request.content.startMs).coerceAtLeast(1000L)
        val sized = sizing(
            request.preset.width, request.preset.height, renderFrameRate(context, request),
            hasOverlay = request.clip != null, durationMs = durationMs
        )
        val freeBytes = context.cacheDir.usableSpace
        if (freeBytes < sized.requiredFreeBytes) {
            return "Not enough space: it needs about ${sized.requiredFreeBytes / 1_000_000} MB free " +
                "while it renders, and the phone has ${freeBytes / 1_000_000} MB. Free some up and retry."
        }
        return null
    }

    private const val TAG = "VideoExporter"

    /**
     * Renders a video with Media3 Transformer: the curves over the clip, or over a plain
     * background without one.
     *
     * The first recording in the request is the one the single-curve path draws and the staging
     * file is named after. It comes from the request, so the render service no longer fetches it
     * again by id.
     */
    suspend fun exportVideo(
        context: Context,
        request: ExportRequest.Video,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        // The clock the footage was filmed on, for the time printed on the frame. Read here as
        // well as when the clip was found, because a job restored from the queue doesn't carry it.
        val request = request.clip
            ?.takeIf { request.content.clipZoneId == null }
            ?.let { clip -> request.copy(content = request.content.copy(clipZoneId = ClipZone.of(context, clip))) }
            ?: request
        val record = request.content.records.firstOrNull()
            ?: throw IOException("There's nothing to draw: no recordings came with this export.")

        // 1. Setup File
        val sanitizedTitle = FileNames.of(record.metadata.title)
        val fileName = "${sanitizedTitle}.mp4"
        val outputFile = File(context.cacheDir, fileName)
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        val startTimeMs = request.content.startMs
        val endTimeMs = request.content.endMs
        val totalDataDurationMs = (endTimeMs - startTimeMs).coerceAtLeast(1000L)

        // Place every record on one timeline up front. Doing it here rather than per frame keeps
        // the overlay cheap, and — more importantly — means the video is clipped against exactly
        // the same origin the graph is drawn against, so all records stay in sync with it.
        //
        // One recording too, through the same path. It had its own, with its own BPM scale and the
        // recording's name for a title in place of the caption, while the preview always took this
        // one, so a one-person video didn't look like its preview (Audit #27).
        val exportRecords = request.content.records.ifEmpty { listOf(record) }
        val alignedRecords = VideoFrameRenderer.alignRecords(exportRecords)
        val timelineOriginMs = alignedRecords.timeline.originWallClockMs

        var isInputImage = false
        val mediaUri: Uri
        val inputMimeType: String?

        // 2. Handle Background and Dynamic Bitrate based on output dimensions and fps
        val width = request.preset.width
        val height = request.preset.height
        // Matched to the footage when asked for, falling back to the stated rate when the file will
        // not say — a clip whose metadata is unreadable is not a reason to refuse the export.
        val fps = renderFrameRate(context, request)

        // The bitrate and the size, by the same sum step 4 shows before anything is queued.
        val sized = sizing(width, height, fps, hasOverlay = request.clip != null, durationMs = totalDataDurationMs)
        val targetBitrate = sized.bitrate

        // Refuse a render there is plainly no room for, or whose clip has gone, rather than
        // discovering it part way and leaving the phone worse off than before it started.
        preflightProblem(context, request)?.let { throw IOException(it) }

        if (request.clip != null) {
            mediaUri = request.clip
            inputMimeType = context.contentResolver.getType(mediaUri)
            isInputImage = inputMimeType?.startsWith("image/") == true ||
                    mediaUri.path?.lowercase()?.let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") } == true
        } else {
            // What a video with no footage behind it is drawn on. The app's surface rather than
            // black, so an export with nothing filmed looks like it came from this app instead of
            // from a default. The video's own shape at a tenth of its size: a square one was fitted
            // into a wide video with black bars either side of it.
            val backdrop = createBitmap(
                (width / 10).coerceAtLeast(16), (height / 10).coerceAtLeast(16), Bitmap.Config.ARGB_8888
            ).apply {
                eraseColor(inga.bpmetrics.core.BpmPalette.SURFACE)
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
                        request.preset.syncOffsetMs,
                        request.clipStartedAtMs
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

        // Where the graph sits: the preset's frame for this many curves. The request's preset has
        // this clip's own framing baked in already, so this is the frame the preview showed.
        val frame = request.preset.frameFor(exportRecords.size)
        val graphRect = frame.toRectF()

        // 4. Effects
        val canvasOverlay = object : BitmapOverlay() {
            private val overlaySize = androidx.media3.common.util.Size(request.preset.width, request.preset.height)
            private val reusableBitmap = createBitmap(request.preset.width, request.preset.height, Bitmap.Config.ARGB_8888)
            private val reusableCanvas = Canvas(reusableBitmap)

            override fun getTextureSize(presentationTimeUs: Long) = overlaySize

            override fun getBitmap(presentationTimeUs: Long): Bitmap {
                reusableCanvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                val currentRecordAbsoluteTimeMs = startTimeMs + ((presentationTimeUs / 1000.0))
                VideoFrameRenderer.renderAlignedRecordsOnCanvas(
                    canvas = reusableCanvas,
                    aligned = alignedRecords,
                    content = request.content,
                    look = request.preset,
                    currentTimeMs = currentRecordAbsoluteTimeMs,
                    graphRect = graphRect,
                    readouts = frame.readouts
                )
                return reusableBitmap
            }
        }

        val effectList = mutableListOf<Effect>()
        
        // Add FrameDropEffect to ensure the output matches the requested frame rate
        // especially when the source video has a higher frame rate (e.g. 60 -> 30).
        effectList.add(FrameDropEffect.createDefaultFrameDropEffect(fps.toFloat()))
        
        effectList.add(Presentation.createForWidthAndHeight(
            request.preset.width,
            request.preset.height,
            // Filling crops footage of another shape to the canvas; fitting keeps all of it, with
            // bars. A plain-background export's backdrop is the canvas's shape either way.
            if (request.preset.fillCanvas) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            else Presentation.LAYOUT_SCALE_TO_FIT
        ))
        effectList.add(OverlayEffect(listOf(canvasOverlay)))

        val editedMediaItem = editedItem(mediaItem, effectList, isInputImage, fps)

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
                android.provider.MediaStore.Video.VideoColumns.DATE_MODIFIED,
                android.provider.MediaStore.Video.VideoColumns.DISPLAY_NAME
            )
            try {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val stamp = VideoTiming.resolve(
                            dateTakenMs = cursor.getLong(0),
                            durationMs = cursor.getLong(1),
                            // Seconds in MediaStore, milliseconds everywhere else here.
                            dateModifiedMs = cursor.getLong(2) * 1000L,
                            fileName = cursor.getString(3)
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
     * A video handed over by a picker, as a clip.
     *
     * So that a video chosen by hand goes through exactly what a found one does — the Contents step,
     * who was recording during it, the preview, per-clip framing, and a render of the right stretch.
     * It used to be a bare uri laid over the whole recording from the start, which is why a picked
     * video could not be lined up with anything.
     *
     * The length is the file's and is required: a clip is a start and a length, and without the
     * length there is no telling which stretch of a recording belongs over it. The start is the
     * file's best guess and may well be wrong — a screen recording is stamped when it was captured,
     * not when the footage inside it was filmed — which is what [ClipRetiming] exists to correct.
     *
     * @return null for something that is not a playable video.
     */
    fun clipFromUri(context: Context, uri: Uri): VideoClip? {
        val durationMs = MediaMetadataRetriever().let { retriever ->
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the length of $uri", e)
                null
            } finally {
                retriever.release()
            }
        }?.takeIf { it > 0L } ?: return null

        val name = runCatching {
            context.contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()

        // No stamp at all is not a reason to refuse the file. It still has a length, and its start
        // is about to be set by hand anyway — so it starts where it ends, now.
        val stamp = resolveStamp(context, uri)
        val (width, height) = shapeOf(context, uri) ?: (0 to 0)
        return VideoClip(
            widthPx = width,
            heightPx = height,
            uri = uri,
            startedAtMs = stamp?.startedAtMs ?: (System.currentTimeMillis() - durationMs),
            durationMs = durationMs,
            displayName = name,
            basis = stamp?.basis ?: VideoTiming.Basis.ASSUMED,
            zoneId = ClipZone.of(context, uri)
        )
    }

    /**
     * A video's picture size as it plays: width and height with its rotation applied, so a phone
     * clip filmed upright is taller than it is wide even though it's stored sideways.
     *
     * @return null for something that has no picture, or can't be opened.
     */
    fun shapeOf(context: Context, uri: Uri): Pair<Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w == null || h == null || w <= 0 || h <= 0) null
            else if (rotation % 180 != 0) h to w else w to h
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the size of $uri", e)
            null
        } finally {
            retriever.release()
        }
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
        record: BpmRecordWithPoints,
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
     * For a multi-record export the origin is [VideoFrameRenderer.Timeline.originWallClockMs] rather
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
        val basis: VideoTiming.Basis = VideoTiming.Basis.ASSUMED,
        /** The UTC offset the file says it was filmed at, if it says. See [ClipZone]. */
        val zoneId: String? = null,
        /**
         * The picture's size as it plays, rotation applied, or 0 when unknown. For a canvas that
         * matches the clip (see [ExportPreset.forClip]).
         */
        val widthPx: Int = 0,
        val heightPx: Int = 0
    ) {
        val endedAtMs: Long get() = startedAtMs + durationMs

        /** [preset] at the size it renders over this clip. */
        fun canvasFor(preset: ExportPreset): ExportPreset = preset.forClip(widthPx, heightPx)

        /** Whether a recording was running at any point while this was filming. */
        fun overlaps(record: BpmRecordWithPoints): Boolean {
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
         * A clip that began before the timeline (filming started a second before the watch did)
         * has its window start at the timeline's origin, and the footage before then is skipped,
         * not slid along: [ClipWindow.intoClipMs] says how much. The render has always skipped it.
         * The preview didn't, so it drew each frame against the curve from that much later.
         *
         * @param syncOffsetMs nudges the clip along the timeline, so a positive value runs the
         *   curves ahead of the footage.
         */
        fun windowOn(timeline: VideoFrameRenderer.Timeline, syncOffsetMs: Long): ClipWindow {
            val placed = startedAtMs - timeline.originWallClockMs + syncOffsetMs
            val start = placed.coerceAtLeast(0L)
            return ClipWindow(
                startMs = start,
                endMs = (placed + durationMs).coerceAtMost(timeline.durationMs).coerceAtLeast(start),
                intoClipMs = start - placed
            )
        }
    }

    /**
     * A span of a recording timeline, in milliseconds from its origin.
     *
     * @property intoClipMs how far into the clip's file the window begins: the footage filmed
     *   before the timeline started, which has no curve to go with it.
     */
    data class ClipWindow(val startMs: Long, val endMs: Long, val intoClipMs: Long = 0L) {
        /** Never zero: an export of no duration is a file nobody can play. */
        val spanMs: Long get() = (endMs - startMs).coerceAtLeast(1L)
    }

    /**
     * Queries the MediaStore for videos overlapping any of [records].
     *
     * A multi-record export spans from the earliest session to the latest, so a video worth
     * suggesting may overlap only one of them — searching a single record's window would miss it.
     *
     * Keeps each video's `DATE_TAKEN` and `DURATION` rather than bare uris. Per-clip work needs
     * them: which people to offer on a clip depends on who
     * was recording during *that clip's* few minutes, not during the whole event, and a clip filmed
     * after someone's watch stopped must not offer their curve.
     */
    fun getOverlappingClips(context: Context, records: List<BpmRecordWithPoints>): List<VideoClip> {
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
                        dateModifiedMs = cursor.getLong(modifiedCol) * 1000L,
                        fileName = cursor.getString(nameCol)
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
            // Only for the clips that matched, since it opens each file.
            .map { clip ->
                val (width, height) = shapeOf(context, clip.uri) ?: (0 to 0)
                clip.copy(zoneId = ClipZone.of(context, clip.uri), widthPx = width, heightPx = height)
            }
    }

    /**
     * The permission that lets [getOverlappingClips] see anything at all.
     *
     * **Version-dependent, because the permission itself is.** `READ_MEDIA_VIDEO` only exists from
     * Android 13; below that the media store is behind `READ_EXTERNAL_STORAGE`. `minSdk` is 31, so
     * naming only the newer one meant asking Android 12 for a permission it has never heard of —
     * which is refused, permanently and without a dialog, leaving video overlay impossible on
     * those devices however many times anyone tried.
     */
    fun getVideoPermissionString(): String =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_VIDEO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /**
     * Whether the gallery can be read.
     *
     * Worth asking *before* querying rather than inferring afterwards: a query made without this
     * comes back empty rather than failing, so "not allowed to look" and "nothing to find" are the
     * same answer unless someone checks. That is exactly how a fresh install — which has no runtime
     * grants, whatever the manifest says — came to report a library full of footage as having none.
     */
    fun hasVideoPermissions(context: Context): Boolean = mediaAccess(context) != MediaAccess.NONE

    /** How much of the gallery this app may read. */
    enum class MediaAccess { FULL, PARTIAL, NONE }

    /**
     * Full, partial or none. Partial is Android 14's "Allow limited access": only the videos
     * someone picked are visible, so finding nothing proves nothing about what was filmed.
     */
    fun mediaAccess(context: Context): MediaAccess {
        fun granted(permission: String) = androidx.core.content.ContextCompat.checkSelfPermission(
            context, permission
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return when {
            granted(getVideoPermissionString()) -> MediaAccess.FULL
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                granted(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.PARTIAL
            else -> MediaAccess.NONE
        }
    }

    /**
     * What to ask for. On Android 14 and later both, which is what makes the system offer "Select
     * photos and videos" and, asked again with partial access, "Choose more".
     */
    fun videoPermissionsToRequest(): Array<String> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
        } else {
            arrayOf(getVideoPermissionString())
        }
}
