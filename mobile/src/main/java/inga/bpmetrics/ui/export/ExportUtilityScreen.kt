package inga.bpmetrics.ui.export

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import inga.bpmetrics.BPMetricsApp
import inga.bpmetrics.export.BpmExportService
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
    onOpenDrawer: () -> Unit
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
    var showSettings by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val repository = remember(context) {
        (context.applicationContext as BPMetricsApp).libraryRepository
    }
    val events by remember { repository.getAllEvents() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val groups by remember { repository.getAllEventGroups() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val people by remember { repository.getAllPeople() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val peopleById = remember(people) { people.associateBy { it.personId } }
    val allRecords by repository.records.collectAsStateWithLifecycle()

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
                        .navigationBarsPadding()
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
                    groups = groups,
                    recordings = allRecords,
                    peopleById = peopleById,
                    selected = source,
                    onSelect = { viewModel.setSource(it) }
                )

                ExportStep.CONTENTS -> ContentsStep(
                    clips = clips,
                    records = records,
                    peopleById = peopleById,
                    loading = loadingClips,
                    hasNoClips = hasNoClips,
                    oldestFirst = oldestFirst,
                    onToggleOrder = { viewModel.toggleClipOrder() },
                    onToggleClip = { viewModel.toggleClip(it) },
                    onToggleRecord = { uri, id -> viewModel.toggleRecordOnClip(uri, id) }
                )

                ExportStep.LOOK -> LookStep(
                    records = records,
                    jobCount = pendingJobs.size,
                    onConfigure = { showSettings = true }
                )

                // Already built, already good. Folded in here rather than kept as a separate
                // drawer entry, because the queue is where an export ends up and nowhere else.
                ExportStep.MAKE -> RenderQueueContent()
            }
        }
    }

    // The settings still come from the existing dialog rather than living in step 3 — see the
    // EXP-1.4 note. It stays the single place a VideoExportConfig is built, so batching cannot
    // drift from what a one-off export produces.
    if (showSettings && records.isNotEmpty()) {
        VideoExportDialog(
            record = records.first(),
            records = records,
            graphTitle = sourceLabel.takeIf { it.isNotBlank() },
            onDismiss = { showSettings = false },
            onExport = { config, _ ->
                queueOneJobPerClip(context, config, pendingJobs, records, sourceLabel)
                showSettings = false
                viewModel.next()
            }
        )
    }
}

/**
 * Turns one set of settings into one job per ticked clip.
 *
 * The video is the unit: each job carries its own clip and only the recordings that were running
 * while that clip was filming. The appearance is shared, which is the whole point of configuring
 * once and exporting six — a preset in everything but name, until Sprint 3 gives it one.
 *
 * With no clips at all, a single job still goes out. `VideoExporter` renders against a solid
 * background when no overlay is given, and a session nobody filmed is still worth exporting.
 */
private fun queueOneJobPerClip(
    context: android.content.Context,
    config: VideoExporter.VideoExportConfig,
    jobs: List<ClipSelection>,
    allRecords: List<inga.bpmetrics.library.BpmRecord>,
    label: String
) {
    val name = label.ifBlank { "Export" }

    if (jobs.isEmpty()) {
        // The config is passed through untouched, overlay and all. Entering at step 3 from an
        // existing entry point skips clip selection entirely, and the dialog's own video picker is
        // then the only thing that chose an overlay — overriding it here would silently throw away
        // the video the user just picked.
        BpmExportService.startExport(
            context,
            allRecords.first().metadata.recordId,
            name,
            config.copy(records = allRecords),
            null
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
            "$name · ${getTimeString(job.clip.startedAtMs)}",
            config.copy(
                overlayVideoUri = job.clip.uri,
                records = forThisClip
            ),
            null
        )
    }
}

/**
 * Step 3 for now: a summary and a way into the existing settings dialog.
 *
 * The sections, the preview and the presets land in Sprints 3 and 4. What matters here is that the
 * flow reaches a real export — the batching is done, and it is the settings that are still to move.
 */
@Composable
private fun LookStep(
    records: List<inga.bpmetrics.library.BpmRecord>,
    jobCount: Int,
    onConfigure: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            when {
                records.isEmpty() -> "Nothing to export"
                jobCount == 0 -> "1 export, on a plain background"
                jobCount == 1 -> "1 export"
                else -> "$jobCount exports, one per clip"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Canvas, graph placement, background and overlay. Settings apply to every export in " +
                "this batch.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onConfigure, enabled = records.isNotEmpty()) {
            Text(if (jobCount > 1) "Configure and queue $jobCount" else "Configure and queue")
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
