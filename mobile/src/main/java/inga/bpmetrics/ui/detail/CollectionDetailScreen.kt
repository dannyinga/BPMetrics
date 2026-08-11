package inga.bpmetrics.ui.detail

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.ScopeRef
import inga.bpmetrics.ui.Routes
import inga.bpmetrics.ui.analysis.AnalysisScreen
import inga.bpmetrics.ui.analysis.AnalysisViewModel
import inga.bpmetrics.ui.analysis.shortDuration
import inga.bpmetrics.ui.components.DeleteConfirmDialog
import inga.bpmetrics.ui.components.rememberCoverPicker
import inga.bpmetrics.ui.library.CollectionSummary
import inga.bpmetrics.ui.library.LibraryViewModel

/**
 * A collection, and its analysis.
 *
 * The third subject, and the one that was left behind: events and recordings got their cover as a
 * header while this still showed the counts card every scope falls back to. It is the same kind of
 * thing as the other two — §8.5 — and looking different said it was not.
 *
 * What a collection's header says is genuinely shorter, because a set has less to be: no window, no
 * venue, no clock of its own. What it does have is the events it names, which is also the way out
 * of the page — §2.4, no page is a dead end.
 */
@Composable
fun CollectionDetailScreen(
    navController: NavController,
    repository: LibraryRepository,
    libraryViewModel: LibraryViewModel,
    collectionId: Long,
    onBack: () -> Unit,
    onSave: (name: String, records: List<inga.bpmetrics.ui.analysis.AnalysisRecord>) -> Unit,
    onExport: () -> Unit
) {
    val context = LocalContext.current

    val analysis: AnalysisViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "collection-$collectionId",
        factory = AnalysisViewModel.forScope(repository, ScopeRef.Collection(collectionId))
    )

    val collections by libraryViewModel.collections.collectAsStateWithLifecycle()
    val summary: CollectionSummary? = remember(collections, collectionId) {
        collections.firstOrNull { it.collection.collectionId == collectionId }
    }

    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    var framingCover by remember { mutableStateOf(false) }
    val pickCover = rememberCoverPicker { uri ->
        libraryViewModel.setCollectionCover(context, collectionId, uri) { ok ->
            // Straight into framing, while the choice is fresh.
            if (ok) framingCover = true
            else Toast.makeText(context, "That image could not be read", Toast.LENGTH_LONG).show()
        }
    }

    AnalysisScreen(
        navController = navController,
        viewModel = analysis,
        onOpenDrawer = {},
        onBack = onBack,
        title = null,
        onSave = onSave,
        onExport = onExport,
        subjectHeader = {
            if (summary != null) {
                val uiState by analysis.uiState.collectAsStateWithLifecycle()
                val set = summary.collection
                SubjectHeader(
                    title = set.displayName,
                    subtitle = buildString {
                        when {
                            set.isFrozen -> append("Frozen — these numbers stay as they were")
                            set.isSmart -> append("Smart — whatever matches, whenever you look")
                            else -> append("A set you assembled")
                        }
                        if (uiState.totalActiveDurationMs > 0) {
                            append(" · ${shortDuration(uiState.totalActiveDurationMs)}")
                        }
                    },
                    cover = set.ownCover,
                    // The events it names, which is also the way into them. A set has nothing
                    // *above* it — it does not nest — so its trail points downward instead.
                    trail = summary.events.map { it.eventId to it.displayName },
                    lowBpm = uiState.minTrio.takeIf { !uiState.isEmpty },
                    avgBpm = uiState.avgTrio.takeIf { !uiState.isEmpty },
                    highBpm = uiState.maxTrio.takeIf { !uiState.isEmpty },
                    counts = buildString {
                        if (summary.eventCount > 0) {
                            append("${summary.eventCount} event")
                            if (summary.eventCount != 1) append("s")
                            append(" · ")
                        }
                        append("${summary.recordCount} recording")
                        if (summary.recordCount != 1) append("s")
                        // The figure that used to head the zone breakdown. A total like the counts
                        // beside it, not a heading for a chart.
                        if (!uiState.isEmpty) {
                            append(
                                " · " +
                                    inga.bpmetrics.ui.analysis.shortDuration(
                                        uiState.totalActiveDurationMs
                                    )
                            )
                        }
                    },
                    onOpenAncestor = { navController.navigate("${Routes.EVENT_DETAIL}/$it") },
                    onEditCover = { framingCover = true }
                )
            }
        },
        subjectActions = {
            IconButton(onClick = { renaming = true }) {
                Icon(Icons.Default.Edit, contentDescription = "Rename collection")
            }
        },
        // No overflow. Everything that was in it — the photo, pinning, deleting — is in the
        // editor, which is where the rest of what a collection *is* already lives.
    )

    if (framingCover && summary != null) {
        inga.bpmetrics.ui.components.CoverCropDialog(
            cover = summary.collection.ownCover,
            onPick = pickCover,
            title = "Frame ${summary.collection.displayName}",
            previewContent = {
                Text(
                    summary.collection.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.White
                )
            },
            onDismiss = { framingCover = false },
            onConfirm = {
                libraryViewModel.setCollectionCoverCrop(collectionId, it)
                framingCover = false
            },
            // Stays open: removing is usually the first half of replacing.
            onRemove = { libraryViewModel.clearCollectionCover(context, collectionId) }
        )
    }

    if (renaming && summary != null) {
        val filterOptions by libraryViewModel.filterOptions.collectAsStateWithLifecycle()
        inga.bpmetrics.ui.library.CollectionEditorDialog(
            collection = summary.collection,
            recordCount = summary.recordCount,
            filterOptions = filterOptions,
            chipsOf = { libraryViewModel.chipsFor(it) },
            onRename = { libraryViewModel.renameCollection(collectionId, it) },
            onSetRule = { libraryViewModel.setCollectionRule(collectionId, it) },
            onMakeStatic = {
                libraryViewModel.materialiseCollection(collectionId) { message ->
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            },
            onThaw = if (!summary.collection.isFrozen) null else {
                {
                    libraryViewModel.thawCollection(collectionId) { message ->
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            },
            onTogglePin = { libraryViewModel.setCollectionPinned(collectionId, it) },
            onDelete = { renaming = false; deleting = true },
            onDismiss = { renaming = false }
        )
    }

    if (deleting && summary != null) {
        DeleteConfirmDialog(
            title = "Delete ${summary.collection.displayName}?",
            // The one delete in the app that removes no data, and people reasonably assume
            // otherwise from every other one.
            message = "This removes the grouping only. Its ${summary.recordCount} recording" +
                "${if (summary.recordCount == 1) "" else "s"} stay exactly where they are on the " +
                "timeline.",
            onDismiss = { deleting = false },
            onConfirm = {
                deleting = false
                libraryViewModel.deleteCollection(collectionId)
                onBack()
            }
        )
    }
}
