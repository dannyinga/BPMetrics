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
import inga.bpmetrics.ui.library.NameDialog

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
    onExport: (inga.bpmetrics.ui.export.ExportKind) -> Unit
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
    var menuOpen by remember { mutableStateOf(false) }

    val pickCover = rememberCoverPicker { uri ->
        libraryViewModel.setCollectionCover(context, collectionId, uri) { ok ->
            if (!ok) Toast.makeText(context, "That image could not be read", Toast.LENGTH_LONG).show()
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
                    },
                    onOpenAncestor = { navController.navigate("${Routes.EVENT_DETAIL}/$it") }
                )
            }
        },
        subjectActions = {
            IconButton(onClick = { renaming = true }) {
                Icon(Icons.Default.Edit, contentDescription = "Rename collection")
            }
        },
        subjectOverflow = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(if (summary?.collection?.ownCover != null) "Change photo" else "Add photo")
                        },
                        onClick = { menuOpen = false; pickCover() }
                    )
                    if (summary?.collection?.ownCover != null) {
                        DropdownMenuItem(
                            text = { Text("Remove photo") },
                            onClick = {
                                menuOpen = false
                                libraryViewModel.clearCollectionCover(context, collectionId)
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (summary?.collection?.isPinned == true) "Unpin from library"
                                else "Pin to library"
                            )
                        },
                        onClick = {
                            menuOpen = false
                            libraryViewModel.setCollectionPinned(
                                collectionId,
                                summary?.collection?.isPinned != true
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete collection") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; deleting = true }
                    )
                }
            }
        }
    )

    if (renaming && summary != null) {
        NameDialog(
            title = "Rename collection",
            label = "Collection name",
            initial = summary.collection.name,
            onDismiss = { renaming = false },
            onConfirm = { name ->
                libraryViewModel.renameCollection(collectionId, name)
                renaming = false
            }
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
