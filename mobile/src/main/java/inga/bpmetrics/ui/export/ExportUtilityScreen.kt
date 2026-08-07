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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler

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
                ExportStep.SOURCE -> StepPlaceholder(
                    step,
                    "Pick recordings, an event, a group, or a saved analysis. This decides which " +
                        "video clips are on offer."
                )

                ExportStep.CONTENTS -> StepPlaceholder(
                    step,
                    "Every clip overlapping the source, with whoever was recording while it was " +
                        "filming. Each ticked clip becomes its own export."
                )

                ExportStep.LOOK -> StepPlaceholder(
                    step,
                    "Canvas, graph placement, background and overlay — with a preview you can " +
                        "scrub to any frame."
                )

                // Already built, already good. Folded in here rather than kept as a separate
                // drawer entry, because the queue is where an export ends up and nowhere else.
                ExportStep.MAKE -> RenderQueueContent()
            }
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
