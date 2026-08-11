package inga.bpmetrics.ui.export

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.clock
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import inga.bpmetrics.BPMetricsApp
import inga.bpmetrics.export.BpmExportService
import inga.bpmetrics.export.ExportPreset
import inga.bpmetrics.export.RenderQueueManager
import inga.bpmetrics.export.VideoExporter
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The export utility: four steps, each answering one question.
 *
 * Replaces a single dialog that asked everything at once — canvas size beside which recordings
 * beside where the graph sits — and so answered nothing about what it was about to produce. The
 * questions are genuinely sequential: which clips are on offer depends on the source, and the
 * settings are only judgeable against the clip they will be drawn on.
 *
 * Every visited step stays reachable. Walking back to change the source must not discard the look,
 * because the look is the part worth keeping.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportUtilityScreen(
    viewModel: ExportUtilityViewModel,
    onOpenDrawer: () -> Unit,
    onOpenQueue: () -> Unit
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val furthest by viewModel.furthestStep.collectAsStateWithLifecycle()
    val canAdvance by viewModel.canAdvance.collectAsStateWithLifecycle()
    val sourceLabel by viewModel.sourceLabel.collectAsStateWithLifecycle()
    val records by viewModel.records.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    val loadingClips by viewModel.loadingClips.collectAsStateWithLifecycle()
    val hasNoClips by viewModel.hasNoClips.collectAsStateWithLifecycle()
    val pendingJobs by viewModel.pendingJobs.collectAsStateWithLifecycle()
    val oldestFirst by viewModel.clipsOldestFirst.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val selectedPresetId by viewModel.selectedPresetId.collectAsStateWithLifecycle()
    val preset by viewModel.preset.collectAsStateWithLifecycle()
    var presetToExport by remember {
        mutableStateOf<inga.bpmetrics.library.ExportPresetEntity?>(null)
    }

    val context = LocalContext.current
    val repository = remember(context) {
        (context.applicationContext as BPMetricsApp).libraryRepository
    }
    val events by remember { repository.getAllEvents() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val collections by remember { repository.getAllCollections() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val people by remember { repository.getAllPeople() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val peopleById = remember(people) { people.associateBy { it.personId } }
    val allRecords by repository.records.collectAsStateWithLifecycle()
    val manualOverlay by viewModel.manualOverlay.collectAsStateWithLifecycle()
    val previewAt by viewModel.previewAt.collectAsStateWithLifecycle()
    val kind by viewModel.kind.collectAsStateWithLifecycle()
    val imageGrouping by viewModel.imageGrouping.collectAsStateWithLifecycle()
    val imagePlan by viewModel.imagePlan.collectAsStateWithLifecycle()
    val imageCrop by viewModel.imageCrop.collectAsStateWithLifecycle()
    val imageTitle by viewModel.imageTitle.collectAsStateWithLifecycle()
    val imageNaturalSpan by viewModel.imageNaturalSpan.collectAsStateWithLifecycle()

    // Names and event labels have to be resolved here, where the repository is: the renderer draws
    // them and must not hold a copy that goes stale when someone is renamed.
    val personNames = remember(people) { people.associate { it.personId to it.displayName } }
    val eventNames = remember(events) { events.associate { it.eventId to it.displayName } }

    // Reset by the plan changing, so editing the look after saving does not still claim the new
    // version is on disk.
    var imagesSaved by remember(imagePlan, preset) { mutableStateOf(false) }

    // Which clip step 3 is framing. Held here rather than in the ViewModel because it is a
    // position on one screen, not part of what will be exported.
    var previewingUri by remember { mutableStateOf<Uri?>(null) }
    val previewingClip = pendingJobs.firstOrNull { it.clip.uri == previewingUri }
        // Falls back to the first ticked clip, so arriving at step 3 shows something rather than
        // waiting for a selection the strip has not been scrolled to yet.
        ?: pendingJobs.firstOrNull()

    // The framing on screen: the previewed clip's override if it has one, else the preset's own.
    // Saving a preset captures this, so a preset carries the framing it was saved looking at.
    val currentFraming = previewingClip?.graph ?: GraphPlacement.of(preset)

    // Colour comes from the person, never chosen per export — one answer, set in People, so the
    // same person is the same colour on screen and in the video of the same session.
    val recordColours = remember(records, peopleById) {
        records.mapIndexed { index, record ->
            record.metadata.recordId to inga.bpmetrics.library.PersonColors.colorFor(
                record.metadata.personId,
                peopleById,
                index
            )
        }.toMap()
    }

    // Their faces for the pills, decoded once here rather than per frame. A render draws these
    // thirty times a second and the picture never changes; reading and cropping a file on each of
    // those is the difference between a two-minute render and a ten-minute one.
    //
    // Keyed by recording so the renderer needs to know nothing about people. Absent where someone
    // has no photograph, and the pill falls back to their colour and initial — the same fallback
    // the library uses, so a person looks the same in an export as they do on screen.
    val recordPhotos = remember(records, peopleById) {
        records.mapNotNull { record ->
            val photo = record.metadata.personId
                ?.let { peopleById[it] }
                ?.ownPhoto
                ?: return@mapNotNull null
            inga.bpmetrics.library.CoverStore.decodeCropped(context, photo)
                ?.let { record.metadata.recordId to it }
        }.toMap()
    }

    // What the estimate falls back to when there are no clips: the span of the recordings
    // themselves, which is what an unbacked export renders.
    val fallbackDurationMs = remember(records) {
        if (records.isEmpty()) 0L
        else records.maxOf { it.metadata.startTime + it.metadata.durationMs } -
            records.minOf { it.metadata.startTime }
    }

    val pickOverlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // Persisted, or the chosen video stops being readable the next time the process
            // starts — and a queued render outlives the screen that picked it.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.setManualOverlay(it)
        }
    }

    // A preset travels as a small JSON file, shared and opened through the same plumbing a backup
    // uses. Written to wherever the user picks rather than the share sheet, for the same reason:
    // it is a file you want to be able to find again.
    val savePresetLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val chosen = presetToExport
        presetToExport = null
        if (uri == null || chosen == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(chosen.configJson.toByteArray())
            } != null
        }.getOrDefault(false)
        Toast.makeText(
            context,
            if (ok) "Saved ${chosen.name}" else "Could not write the preset",
            Toast.LENGTH_SHORT
        ).show()
    }

    val importPresetLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val json = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (json == null) {
            Toast.makeText(context, "Could not read that file", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        viewModel.importPreset(json) { accepted ->
            Toast.makeText(
                context,
                if (accepted) {
                    "Preset imported"
                } else {
                    // Refused rather than half-applied: a preset from a newer build would produce
                    // an export looking nothing like the one it was shared from.
                    "That preset was not readable, or was made by a newer version of the app"
                },
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(presetToExport) {
        presetToExport?.let {
            savePresetLauncher.launch("${it.name.replace(" ", "_")}.bpmpreset")
        }
    }

    // The default preset, or wherever the user left off. Read once when the utility opens rather
    // than on every visit to step 3, which would undo edits made on the way back from step 4.
    LaunchedEffect(Unit) { viewModel.restorePreset() }

    // Clips are looked up when step 2 is reached, not when the source changes. This hits the
    // MediaStore, and querying on every tap in step 1 would search for sources the user is only
    // browsing past.
    LaunchedEffect(step, records) {
        if (step != ExportStep.CONTENTS || records.isEmpty()) return@LaunchedEffect
        viewModel.setLoadingClips()
        val found = withContext(Dispatchers.IO) {
            VideoExporter.getOverlappingClips(context, records)
        }
        viewModel.loadClips(found)
    }

    // Back walks the steps rather than leaving, which is what a staged flow implies. Leaving from
    // step 1 is the drawer's job.
    BackHandler(enabled = step != ExportStep.SOURCE) { viewModel.back() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("Export", fontWeight = FontWeight.Bold)
                            Text(
                                sourceLabel.ifBlank { step.question },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Default.Menu, contentDescription = "Open navigation menu")
                        }
                    },
                    actions = {
                        // The queue lives here rather than in a tab of its own. It is where an
                        // export *ends up*, so it belongs beside the flow that makes one — and a
                        // top-level destination that is empty most of the time was spending a
                        // quarter of the navigation bar on a screen nobody opens deliberately.
                        val queued by RenderQueueManager.queue.collectAsStateWithLifecycle()
                        val active = queued.count {
                            it.status == inga.bpmetrics.export.RenderStatus.QUEUED ||
                                it.status == inga.bpmetrics.export.RenderStatus.RENDERING
                        }
                        IconButton(onClick = onOpenQueue) {
                            if (active > 0) {
                                androidx.compose.material3.BadgedBox(
                                    badge = {
                                        androidx.compose.material3.Badge { Text("$active") }
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Movie,
                                        contentDescription = "Render queue, $active in progress"
                                    )
                                }
                            } else {
                                Icon(Icons.Default.Movie, contentDescription = "Render queue")
                            }
                        }
                    }
                )
                StepIndicator(
                    current = step,
                    furthest = furthest,
                    onSelect = { viewModel.goTo(it) }
                )
                HorizontalDivider()
            }
        },
        bottomBar = {
            // Step 4 has no "next" — the queue is the end of the flow, and its own actions live
            // on the job cards.
            if (step != ExportStep.MAKE) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (step != ExportStep.SOURCE) {
                        TextButton(onClick = { viewModel.back() }) { Text("Back") }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    Button(onClick = { viewModel.next() }, enabled = canAdvance) {
                        Text("Next")
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (step) {
                ExportStep.SOURCE -> SourceStep(
                    events = events,
                    collections = collections,
                    recordings = allRecords,
                    peopleById = peopleById,
                    selected = source,
                    onSelect = { viewModel.setSource(it) }
                )

                // Video or image sits at the top of this step, because this step is what it
                // decides — a video picks clips to draw on, an image asks which recordings share a
                // timeline. It used to be asked on Source, where the answer changes nothing, and
                // that forced every entry point outside the utility to put a modal in the way
                // asking it first.
                ExportStep.CONTENTS -> Column(Modifier.fillMaxSize()) {
                    ExportKindToggle(kind) { viewModel.setKind(it) }

                    // Weighted so the list below takes the space the toggle leaves, rather than
                    // relying on the Column's remaining-space default.
                    Box(Modifier.weight(1f)) {
                        if (kind == ExportKind.IMAGE) {
                            ImageContentsStep(
                                plan = imagePlan,
                                grouping = imageGrouping,
                                onGroupingChange = { viewModel.setImageGrouping(it) },
                                // Only a group can become more than one image, so only a group is
                                // asked.
                                showGroupingChoice = source is ExportSource.Group,
                                crop = imageCrop,
                                onCropChange = { viewModel.setImageCrop(it) },
                                naturalSpan = imageNaturalSpan,
                                timeZoneId = preset.timeZoneId,
                                title = imageTitle,
                                onTitleChange = { viewModel.setImageTitle(it) }
                            )
                        } else {
                            ContentsStep(
                                clips = clips,
                                records = records,
                                peopleById = peopleById,
                                loading = loadingClips,
                                hasNoClips = hasNoClips,
                                oldestFirst = oldestFirst,
                                onToggleOrder = { viewModel.toggleClipOrder() },
                                onSelectAll = { viewModel.setAllClipsSelected(it) },
                                onToggleClip = { viewModel.toggleClip(it) },
                                onToggleRecord = { uri, id ->
                                    viewModel.toggleRecordOnClip(uri, id)
                                }
                            )
                        }
                    }
                }

                ExportStep.LOOK -> LookStep(
                    records = records,
                    isImage = kind == ExportKind.IMAGE,
                    // Drawn through exactly the call step 4 makes, so the preview and the saved
                    // file cannot be different pictures. The first entry stands for the batch.
                    renderImagePreview = {
                        viewModel.renderImages(
                            imagePlan.take(1), recordColours, personNames, eventNames
                        ).firstOrNull()?.bitmap
                    },
                    imageRevision = listOf(imagePlan, recordColours, imageCrop, imageTitle),
                    jobCount = pendingJobs.size,
                    presets = presets,
                    selectedPresetId = selectedPresetId,
                    preset = preset,
                    onPresetChange = { viewModel.setPreset(it) },
                    // Previewed against the first ticked clip, since that is what most of the
                    // batch will look like. A preview of nothing would be useless with a batch.
                    previewOverlay = pendingJobs.firstOrNull()?.clip?.uri ?: manualOverlay,
                    previewColours = recordColours,
                    previewPhotos = recordPhotos,
                    graphTitle = imageTitle ?: sourceLabel,
                    onGraphTitleChange = { viewModel.setImageTitle(it) },
                    previewTitle = (imageTitle ?: sourceLabel).takeIf { it.isNotBlank() },
                    previewAt = previewAt,
                    onScrub = { viewModel.scrubPreview(it) },
                    framing = currentFraming,
                    ticked = pendingJobs,
                    previewing = previewingClip,
                    onSelectClip = { previewingUri = it },
                    onPlacementChange = { uri, placement -> viewModel.setClipGraph(uri, placement) },
                    onScrubClip = { uri, at -> viewModel.scrubClip(uri, at) },
                    onApplyToAll = { viewModel.applyGraphToAll(it) },
                    overlay = manualOverlay,
                    onPickOverlay = { pickOverlayLauncher.launch(arrayOf("video/*")) },
                    onClearOverlay = { viewModel.setManualOverlay(null) },
                    onApplyPreset = { viewModel.applyPreset(it) },
                    onSaveAs = { viewModel.savePresetAs(it, currentFraming) },
                    onUpdatePreset = { viewModel.updatePreset(it, currentFraming) },
                    onSetDefault = { viewModel.setDefaultPreset(it) },
                    onDeletePreset = { viewModel.deletePreset(it) },
                    onExportPresetFile = { presetToExport = it },
                    onImportPresetFile = { importPresetLauncher.launch(arrayOf("*/*")) }
                )

                ExportStep.MAKE -> if (kind == ExportKind.IMAGE) {
                    // Rendered here rather than queued: an image takes well under a second, and a
                    // queue would be a second place to look for something already finished.
                    val rendered by produceState<List<RenderedImage>?>(
                        initialValue = null,
                        imagePlan, preset, recordColours, imageCrop
                    ) {
                        value = withContext(Dispatchers.Default) {
                            viewModel.renderImages(imagePlan, recordColours, personNames, eventNames)
                        }
                    }

                    ImageMakeStep(
                        images = rendered,
                        saved = imagesSaved,
                        onSaveAll = {
                            val images = rendered.orEmpty()
                            val written = images.count { image ->
                                inga.bpmetrics.export.ExportUtils.saveImageToGallery(
                                    context, image.bitmap, "${sourceLabel}_${image.label}"
                                ) != null
                            }
                            imagesSaved = written == images.size && written > 0
                            viewModel.rememberLastUsed()
                            Toast.makeText(
                                context,
                                if (imagesSaved) {
                                    "Saved to Pictures/BPMetrics"
                                } else {
                                    "Saved $written of ${images.size}"
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onShareAll = {
                            val files = rendered.orEmpty().mapNotNull { image ->
                                inga.bpmetrics.export.ExportUtils.stageImageForShare(
                                    context, image.bitmap, "${sourceLabel}_${image.label}"
                                )
                            }
                            when {
                                files.isEmpty() -> Toast.makeText(
                                    context, "Could not prepare the image", Toast.LENGTH_SHORT
                                ).show()
                                files.size == 1 -> inga.bpmetrics.export.ExportUtils
                                    .shareFile(context, files.first(), "image/png")
                                else -> inga.bpmetrics.export.ExportUtils
                                    .shareMultipleFiles(context, files, "image/png")
                            }
                        }
                    )
                } else {
                    MakeStep(
                        estimate = viewModel.estimate(pendingJobs, fallbackDurationMs),
                        onOpenQueue = onOpenQueue,
                        onQueue = {
                            viewModel.rememberLastUsed()
                            queueBatch(
                                context = context,
                                viewModel = viewModel,
                                jobs = pendingJobs,
                                allRecords = records,
                                colours = recordColours,
                                photos = recordPhotos,
                                manualOverlay = manualOverlay,
                                label = imageTitle?.takeIf { it.isNotBlank() } ?: sourceLabel
                            )
                        }
                    )
                }
            }
        }
    }

}

/**
 * Turns one set of settings into one job per ticked clip.
 *
 * The video is the unit: each job carries its own clip and only the recordings that were running
 * while that clip was filming. The appearance is shared, which is the point of configuring once and
 * exporting six.
 *
 * With no clips at all, a single job still goes out. `VideoExporter` renders against a solid
 * background when no overlay is given, and a session nobody filmed is still worth exporting.
 */
private fun queueBatch(
    context: android.content.Context,
    viewModel: ExportUtilityViewModel,
    jobs: List<ClipSelection>,
    allRecords: List<inga.bpmetrics.library.BpmRecordWithPoints>,
    colours: Map<Long, Int>,
    photos: Map<Long, android.graphics.Bitmap>,
    manualOverlay: Uri?,
    label: String
) {
    if (allRecords.isEmpty()) return
    val name = label.ifBlank { "Export" }

    // Carried onto every job so the queue describes itself. A restored queue has no way to work
    // these out again — the recordings may be gone by then — so they are stored with the job.
    val presetName = viewModel.presetLabel()

    if (jobs.isEmpty()) {
        BpmExportService.startExport(
            context,
            allRecords.first().metadata.recordId,
            name,
            viewModel.buildConfig(allRecords, manualOverlay, colours, photos, name),
            null,
            presetName = presetName,
            sourceLabel = name
        )
        return
    }

    jobs.forEach { job ->
        val forThisClip = allRecords.filter { it.metadata.recordId in job.recordIds }
        if (forThisClip.isEmpty()) return@forEach

        BpmExportService.startExport(
            context,
            forThisClip.first().metadata.recordId,
            // Named by clip time, so a queue of six is readable rather than six identical rows.
            "$name · ${getTimeString(job.clip.startedAtMs, forThisClip.clock)}",
            viewModel.buildConfig(
                forRecords = forThisClip,
                overlay = job.clip.uri,
                colours = colours,
                photos = photos,
                title = name,
                clip = job.clip,
                placement = job.graph
            ),
            null,
            presetName = presetName,
            sourceLabel = name
        )
    }
}

/**
 * Step 4 — what this will cost, and the queue it lands in.
 *
 * The estimate is up front rather than after configuring everything. `VideoExporter` already
 * refuses a render there is no room for, but only once you have got that far; a phone has already
 * been filled by this app once.
 */
@Composable
private fun MakeStep(
    estimate: ExportEstimate,
    onQueue: () -> Unit,
    onOpenQueue: () -> Unit
) {
    var queued by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (queued) {
            // The confirmation, and a way through to watch it — not the queue itself. Ending the
            // flow by listing every render ever made turned "your export has started" into "here
            // is your backlog".
            Text(
                "Started",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${estimate.jobCount} render${if (estimate.jobCount == 1) "" else "s"} " +
                    "queued. They carry on if you leave this screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onOpenQueue) { Text("Watch the render queue") }
        } else {
            Text(
                "${estimate.jobCount} export${if (estimate.jobCount == 1) "" else "s"}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "About ${formatSize(estimate.approxBytes)}, roughly " +
                    "${formatMinutes(estimate.approxRenderMs)} to render, for " +
                    "${formatMinutes(estimate.totalDurationMs)} of video.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = { onQueue(); queued = true }) {
                Text("Start ${estimate.jobCount}")
            }
        }
    }
}

/** Sizes people can act on. Nobody decides anything from a byte count. */
private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
    else -> "${(bytes / 1_000).coerceAtLeast(1)} KB"
}

private fun formatMinutes(ms: Long): String {
    val minutes = ms / 60_000
    val seconds = (ms % 60_000) / 1000
    return when {
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}

/**
 * Step 3 — how the export looks.
 *
 * The preview sits at the top and is scrubable, because the whole point of previewing is judging
 * the settings against the moment that will be hard to read, not against frame zero. Presets next,
 * then the options in four sections.
 */
@Composable
private fun LookStep(
    records: List<inga.bpmetrics.library.BpmRecordWithPoints>,
    isImage: Boolean,
    renderImagePreview: () -> android.graphics.Bitmap?,
    imageRevision: Any,
    jobCount: Int,
    presets: List<inga.bpmetrics.library.ExportPresetEntity>,
    selectedPresetId: Long?,
    preset: ExportPreset,
    onPresetChange: (ExportPreset) -> Unit,
    previewOverlay: Uri?,
    previewColours: Map<Long, Int>,
    previewPhotos: Map<Long, android.graphics.Bitmap>,
    graphTitle: String,
    onGraphTitleChange: (String) -> Unit,
    previewTitle: String?,
    previewAt: Float,
    onScrub: (Float) -> Unit,
    framing: GraphPlacement,
    ticked: List<ClipSelection>,
    previewing: ClipSelection?,
    onSelectClip: (Uri) -> Unit,
    onPlacementChange: (Uri, GraphPlacement) -> Unit,
    onScrubClip: (Uri, Float) -> Unit,
    onApplyToAll: (Uri) -> Unit,
    overlay: Uri?,
    onPickOverlay: () -> Unit,
    onClearOverlay: () -> Unit,
    onApplyPreset: (inga.bpmetrics.library.ExportPresetEntity) -> Unit,
    onSaveAs: (String) -> Unit,
    onUpdatePreset: (inga.bpmetrics.library.ExportPresetEntity) -> Unit,
    onSetDefault: (inga.bpmetrics.library.ExportPresetEntity) -> Unit,
    onDeletePreset: (inga.bpmetrics.library.ExportPresetEntity) -> Unit,
    onExportPresetFile: (inga.bpmetrics.library.ExportPresetEntity) -> Unit,
    onImportPresetFile: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Pinned, and nothing else is. Every control below changes what this shows, so scrolling it
        // away would mean adjusting a setting and having to scroll back to see what it did. The
        // clip strip is deliberately *not* here: it is picked once and then not looked at, and
        // pinning it spent a third of the screen on a row that had already done its job.
        if (isImage) {
            // The image preview is the image, at whatever the settings currently say — there is no
            // clip to scrub through and no playhead to choose, so the scrubbing preview has nothing
            // to offer here.
            ImageLookPreview(
                preset = preset,
                render = renderImagePreview,
                revision = imageRevision,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)
            )
            HorizontalDivider()
        } else if (records.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)
            ) {
                ExportPreview(
                    records = previewing?.let { selection ->
                        // Only the people who were recording during this clip, which is what its
                        // export will draw. Previewing the whole cast would show curves the
                        // finished video will not have.
                        records.filter { it.metadata.recordId in selection.recordIds }
                    } ?: records,
                    preset = preset,
                    clip = previewing?.clip,
                    placement = framing,
                    onPlacementChange = { placement ->
                        // Framing a clip overrides the preset for that clip only. With no clip
                        // being previewed there is nothing to override, so it edits the preset —
                        // which is where framing lives when it is not clip-specific.
                        previewing?.let { onPlacementChange(it.clip.uri, placement) }
                            ?: onPresetChange(placement.into(preset))
                    },
                    overlay = previewing?.clip?.uri ?: previewOverlay,
                    colours = previewColours,
                    photos = previewPhotos,
                    title = previewTitle,
                    at = previewing?.scrubAt ?: previewAt,
                    onScrub = { at ->
                        previewing?.let { onScrubClip(it.clip.uri, at) } ?: onScrub(at)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            HorizontalDivider()
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Which clip is being framed, and the way to copy that framing across — both scroll,
            // because both are decided once rather than watched.
            if (!isImage && records.isNotEmpty() && ticked.size > 1) {
                ClipSelectorStrip(
                    clock = records.clock,
                    clips = ticked,
                    selectedUri = previewing?.clip?.uri,
                    onSelect = onSelectClip
                )
                if (previewing != null) {
                    TextButton(onClick = { onApplyToAll(previewing.clip.uri) }) {
                        Text("Apply this framing to all ${ticked.size} clips")
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            LookSections(
                preset = preset,
                onChange = onPresetChange,
                title = graphTitle,
                onTitleChange = onGraphTitleChange,
                overlay = overlay,
                onPickOverlay = onPickOverlay,
                onClearOverlay = onClearOverlay,
                hasClips = jobCount > 0,
                isImage = isImage,
                syncOffsetMs = preset.syncOffsetMs,
                onSyncOffsetChange = { onPresetChange(preset.copy(syncOffsetMs = it)) },
                framing = framing,
                onFramingChange = { placement ->
                    previewing?.let { onPlacementChange(it.clip.uri, placement) }
                        ?: onPresetChange(placement.into(preset))
                },
                // Folded in as a section of its own rather than standing above them. A preset is
                // one more thing about how the export looks, and a permanently open bar over four
                // collapsed sections claimed a priority it does not have.
                presetBar = {
                    PresetBar(
                        presets = presets,
                        selectedId = selectedPresetId,
                        onApply = onApplyPreset,
                        onSaveAs = onSaveAs,
                        onUpdate = onUpdatePreset,
                        onSetDefault = onSetDefault,
                        onDelete = onDeletePreset,
                        onExportFile = onExportPresetFile,
                        onImportFile = onImportPresetFile
                    )
                }
            )
        }
    }
}

/**
 * Where the user is, and where they may go.
 *
 * Steps already visited are tappable; ones ahead are not. Jumping to "how should it look" before
 * saying what is being exported would be asking about the appearance of nothing.
 */
@Composable
private fun StepIndicator(
    current: ExportStep,
    furthest: ExportStep,
    onSelect: (ExportStep) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ExportStep.entries.forEach { entry ->
            val reached = entry.ordinal <= furthest.ordinal
            val done = entry.ordinal < furthest.ordinal
            val active = entry == current

            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(if (reached) Modifier.clickable { onSelect(entry) } else Modifier)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                active -> MaterialTheme.colorScheme.primary
                                done -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (done) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(
                            "${entry.number}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (active) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    entry.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.Bold else null,
                    color = if (reached) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * A step whose contents land in a later sprint.
 *
 * Says what will be here rather than showing nothing — the scaffold ships first so the shape is
 * reviewable before the four steps are filled, and an empty pane reads as a bug.
 */
@Composable
private fun StepPlaceholder(step: ExportStep, description: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Step ${step.number} · ${step.title}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


/**
 * Video or image, at the top of the step it decides.
 *
 * A segmented control rather than two chips, for the same reason the Compare tab uses one: it is a
 * single setting with two positions, and two chips side by side look like two independent things to
 * tap. The description under it is the honest part — "rendered in the background" and "saved
 * straight away" are the difference anyone actually cares about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportKindToggle(kind: ExportKind, onSelect: (ExportKind) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ExportKind.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = kind == entry,
                    onClick = { onSelect(entry) },
                    shape = SegmentedButtonDefaults.itemShape(index, ExportKind.entries.size),
                    icon = {},
                    label = { Text(entry.label) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            kind.description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
