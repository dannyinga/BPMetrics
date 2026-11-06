package inga.bpmetrics.ui.export

import inga.bpmetrics.export.BpmExportService
import inga.bpmetrics.export.RenderQueueManager
import inga.bpmetrics.export.RenderJob
import inga.bpmetrics.export.RenderStatus
import inga.bpmetrics.export.VideoExporter
import inga.bpmetrics.ui.feedback.Feedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import inga.bpmetrics.ui.theme.BpmAccent
import inga.bpmetrics.ui.theme.BpmSuccess
import inga.bpmetrics.ui.theme.BpmHigh
import inga.bpmetrics.ui.theme.BpmLow


/**
 * What is rendering, and what has rendered.
 *
 * Its own section of the app. It was folded into the export flow's last step for a while, on the
 * reasoning that an export ends up in the queue â but that made the last thing the flow did be
 * handing back a list of everything ever queued, and it meant checking on a render that started
 * yesterday required walking into a new export to find it. Starting a render and watching one are
 * different errands.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RenderQueueScreen(onOpenDrawer: () -> Unit) {
    val queue by RenderQueueManager.queue.collectAsState()
    val active = queue.count {
        it.status == RenderStatus.QUEUED || it.status == RenderStatus.RENDERING
    }
    val finished = queue.count { it.isFinished }

    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    Text(if (active > 0) "Render queue ($active)" else "Render queue")
                },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onOpenDrawer) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Default.Menu,
                            contentDescription = "Open navigation drawer"
                        )
                    }
                },
                actions = {
                    // Saved and cancelled renders, which had to be dismissed one at a time. Failed
                    // ones stay, with their reason and Retry.
                    if (finished > 0) {
                        androidx.compose.material3.TextButton(onClick = { RenderQueueManager.clearFinished() }) {
                            Text("Clear finished")
                        }
                    }
                }
            )
        }
    ) { padding ->
        RenderQueueContent(Modifier.padding(padding))
    }
}

/**
 * The queue itself, without a screen around it.
 *
 * Split from [RenderQueueScreen] so anything else that wants to show the queue inline can, without
 * a second implementation of it drifting away from this one.
 */
@Composable
fun RenderQueueContent(modifier: Modifier = Modifier) {
    val queue by RenderQueueManager.queue.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var confirmCancel by remember { mutableStateOf<RenderJob?>(null) }

    val totalJobs = queue.size
    val activeJobs = queue.count { it.status == RenderStatus.RENDERING }
    val queuedJobs = queue.count { it.status == RenderStatus.QUEUED }
    val completedJobs = queue.count { it.status == RenderStatus.COMPLETED }
    val failedJobs = queue.count { it.status == RenderStatus.FAILED }
    val cancelledJobs = queue.count { it.status == RenderStatus.CANCELLED }

    Column(modifier = modifier.fillMaxSize()) {
        // Stats Panel
        if (totalJobs > 0) {
            RenderStatsPanel(
                total = totalJobs,
                active = activeJobs,
                queued = queuedJobs,
                completed = completedJobs,
                failed = failedJobs,
                cancelled = cancelledJobs
            )
        }

        if (queue.isEmpty()) {
            EmptyQueueState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(queue, key = { it.id }) { job ->
                    RenderJobCard(
                        job = job,
                        // A render can be minutes in, and Cancel sits where a thumb lands.
                        onCancel = { confirmCancel = job },
                        onRemove = { RenderQueueManager.removeJob(job.id) },
                        onRetry = {
                            // The checks the render makes first, made before queueing again: a job
                            // that failed for want of space went straight back to fail the same way.
                            scope.launch {
                                val problem = withContext(Dispatchers.IO) {
                                    VideoExporter.preflightProblem(context, job.request)
                                }
                                if (problem != null) {
                                    RenderQueueManager.updateJobStatus(job.id, RenderStatus.FAILED, error = problem)
                                    Feedback.failed(problem)
                                } else {
                                    RenderQueueManager.retryJob(job.id)
                                    BpmExportService.resumeQueue(context)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    confirmCancel?.let { job ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmCancel = null },
            title = { Text("Cancel this render?") },
            text = {
                Text(
                    "${job.recordTitle} stops where it is, and nothing is saved. You can retry it " +
                        "later, from the start."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    RenderQueueManager.cancelJob(job.id)
                    confirmCancel = null
                }) { Text("Cancel render", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmCancel = null }) {
                    Text("Keep rendering")
                }
            }
        )
    }
}

@Composable
fun RenderStatsPanel(
    total: Int,
    active: Int,
    queued: Int,
    completed: Int,
    failed: Int,
    cancelled: Int = 0
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Rendering Summary",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem(label = "Total", value = total.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                StatItem(label = "Active", value = active.toString(), color = BpmAccent)
                StatItem(label = "Queued", value = queued.toString(), color = BpmLow)
                StatItem(label = "Completed", value = completed.toString(), color = BpmSuccess)
                // Counted as what they are. Failed and cancelled were added up as "Inactive",
                // which hid that something had gone wrong.
                if (failed > 0) {
                    StatItem(label = "Failed", value = failed.toString(), color = BpmHigh)
                }
                if (cancelled > 0) {
                    StatItem(
                        label = "Cancelled",
                        value = cancelled.toString(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun RenderJobCard(
    job: RenderJob,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Where it was saved, read from the media store, so a finished job says where to look.
    val location by androidx.compose.runtime.produceState<String?>(null, job.status, job.targetUri) {
        val uri = job.targetUri
        value = if (job.status == RenderStatus.COMPLETED && uri != null) {
            withContext(Dispatchers.IO) { inga.bpmetrics.export.ExportUtils.describeLocation(context, uri) }
        } else null
    }

    // Pulsing alpha for active rendering jobs
    val infiniteTransition = rememberInfiniteTransition(label = "pulsing")
    val pulsingAlpha by if (job.status == RenderStatus.RENDERING) {
        infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulsingAlpha"
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    val cardBorderColor by animateColorAsState(
        targetValue = when (job.status) {
            RenderStatus.RENDERING -> BpmAccent.copy(alpha = 0.8f)
            RenderStatus.QUEUED -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            RenderStatus.COMPLETED -> BpmSuccess.copy(alpha = 0.4f)
            RenderStatus.FAILED -> BpmHigh.copy(alpha = 0.4f)
            RenderStatus.CANCELLED -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
        },
        label = "borderColor"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, cardBorderColor, MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = when (job.status) {
                RenderStatus.RENDERING -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status icon/badge
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            when (job.status) {
                                RenderStatus.RENDERING -> BpmAccent.copy(alpha = 0.15f)
                                RenderStatus.QUEUED -> BpmLow.copy(alpha = 0.1f)
                                RenderStatus.COMPLETED -> BpmSuccess.copy(alpha = 0.15f)
                                RenderStatus.FAILED -> BpmHigh.copy(alpha = 0.15f)
                                RenderStatus.CANCELLED -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (job.status) {
                            RenderStatus.RENDERING -> Icons.Default.PlayArrow
                            RenderStatus.QUEUED -> Icons.Default.HourglassEmpty
                            RenderStatus.COMPLETED -> Icons.Default.CheckCircle
                            RenderStatus.FAILED -> Icons.Default.Error
                            RenderStatus.CANCELLED -> Icons.Default.Close
                        },
                        contentDescription = null,
                        tint = when (job.status) {
                            RenderStatus.RENDERING -> BpmAccent
                            RenderStatus.QUEUED -> BpmLow
                            RenderStatus.COMPLETED -> BpmSuccess
                            RenderStatus.FAILED -> BpmHigh
                            RenderStatus.CANCELLED -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        },
                        modifier = Modifier.size(20.dp).alpha(if (job.status == RenderStatus.RENDERING) pulsingAlpha else 1f)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Titles
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.recordTitle,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    // What the job is *of*, before what is happening to it. Six rows from one
                    // batch differ in their source and their look, not in their status, so the
                    // part that distinguishes them goes first.
                    job.summary.takeIf { it.isNotBlank() }?.let { summary ->
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = when (job.status) {
                            RenderStatus.RENDERING -> "Rendering…"
                            RenderStatus.QUEUED -> "Waiting its turn"
                            RenderStatus.COMPLETED -> "Saved to ${location ?: "Movies/BPMetrics"}"
                            RenderStatus.FAILED -> "Failed"
                            RenderStatus.CANCELLED -> "Cancelled"
                        },
                        style = if (job.status == RenderStatus.COMPLETED) MaterialTheme.typography.bodySmall
                            else MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }

                // Control action
                if (job.status == RenderStatus.RENDERING) {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel Render",
                            tint = BpmHigh
                        )
                    }
                } else if (job.status == RenderStatus.QUEUED) {
                    IconButton(onClick = onRemove) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove from Queue",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (job.isRetryable) {
                    // The job is kept whole, so this needs nothing rebuilt: a render that ran out
                    // of space at 90%, or one the phone killed, goes again exactly as configured
                    // rather than being reassembled from the source, the clip and the framing.
                    Row {
                        IconButton(onClick = onRetry) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Retry render",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = onRemove) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Remove from Queue",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (job.status == RenderStatus.COMPLETED) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    Row {
                        job.targetUri?.let { uri ->
                            IconButton(onClick = {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "video/mp4")
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play Video",
                                    tint = BpmSuccess
                                )
                            }
                            IconButton(onClick = {
                                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Video").apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share Video",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        IconButton(onClick = onRemove) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                } else {
                    IconButton(onClick = onRemove) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // Progress bar and details for Rendering status
            if (job.status == RenderStatus.RENDERING) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LinearProgressIndicator(
                        progress = { job.progress },
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(MaterialTheme.shapes.extraSmall),
                        color = BpmAccent,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "${(job.progress * 100).toInt()}%",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = BpmAccent
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                val w = job.request.preset.width
                val h = job.request.preset.height
                val fps = job.request.preset.frameRate
                Text(
                    text = "Resolution: ${w}x${h} | Frame Rate: ${fps}fps",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            // Why it failed, in plain words and in full. It was behind "Tap to view error details"
            // and set in monospace, as if it were for a developer.
            if (job.status == RenderStatus.FAILED) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(BpmHigh.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = BpmHigh,
                        modifier = Modifier.size(16.dp).padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = job.error?.takeIf { it.isNotBlank() } ?: "It stopped without saying why.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}


/**
 * Nothing rendering.
 *
 * Says where renders come from rather than only that there are none â an empty screen that just
 * apologises leaves someone guessing what they were supposed to have done.
 */
@Composable
fun EmptyQueueState() {
    inga.bpmetrics.ui.components.BpmEmptyState(
        icon = Icons.Default.HourglassEmpty,
        title = "Nothing rendering",
        body = "Videos you queue from the export utility appear here, and carry on rendering " +
            "while you use the rest of the app."
    )
}
