package inga.bpmetrics.ui.library

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import inga.bpmetrics.ui.components.DeleteConfirmDialog
import inga.bpmetrics.ui.components.rememberCoverPicker

/**
 * Collections: the app's third place, where Analysis used to be.
 *
 * A collection is a different *relation* from the library, not a different ordering of it: "every
 * festival", "everything with Kyle" — things that belong together but did not happen together. No
 * sorting of a chronology has a place for a set spanning two weekends four months apart, which is
 * why it is not a view mode.
 *
 * **It replaced the Analysis tab because they were the same list.** A saved analysis is a
 * collection with its numbers frozen, so Analysis was showing a filtered subset of what this shows
 * all of. And analysis is not a place: §8.5 of the product doc has a detail screen as a scope, its
 * numbers and a split, so you analyse by *opening* something — a recording, an event, or one of
 * these. A tab named after the verb implied a fourth kind of thing to go and find.
 *
 * Everything here still moves nothing. A set names events and recordings that stay exactly where
 * they are on the timeline, and deleting one removes a grouping and no data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    viewModel: LibraryViewModel,
    onOpenDrawer: () -> Unit,
    onOpen: (Long) -> Unit,
    /** The whole library as a scope — the one that always exists, however empty this list is. */
    onAnalyseEverything: () -> Unit = {},
    /** Picking recordings by hand, which no filter expresses. */
    onPickForSameTime: () -> Unit = {}
) {
    val context = LocalContext.current
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val covers by viewModel.coversByCollection.collectAsStateWithLifecycle()

    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<CollectionSummary?>(null) }
    var deleting by remember { mutableStateOf<CollectionSummary?>(null) }

    // Registered once for the screen rather than once per card: a launcher per collection would be
    // dozens of them on a library with dozens of sets. Which one it is for is held alongside it,
    // because the launcher outlives the card that started it.
    var covering by remember { mutableStateOf<CollectionSummary?>(null) }
    val pickCover = rememberCoverPicker { uri ->
        covering?.let { target ->
            viewModel.setCollectionCover(context, target.collection.collectionId, uri) { ok ->
                if (!ok) {
                    Toast.makeText(context, "That image could not be read", Toast.LENGTH_LONG).show()
                }
            }
        }
        covering = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Collections",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Open navigation menu")
                    }
                },
                actions = {
                    // Two scopes that are not collections: the whole library, and a set picked by
                    // hand. Offered here because this is where someone comes looking to compare
                    // something — and a hand-picked set opens the same page a collection does,
                    // which is why it no longer needs a screen or a rule of its own.
                    Box {
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Analyse everything") },
                                onClick = { menu = false; onAnalyseEverything() }
                            )
                            DropdownMenuItem(
                                text = { Text("Compare chosen recordings…") },
                                onClick = { menu = false; onPickForSameTime() }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            CollectionsList(
                collections = collections,
                covers = covers,
                onCreate = { creating = true },
                onOpen = onOpen,
                onRename = { renaming = it },
                onSetCover = { covering = it; pickCover() },
                onRemoveCover = {
                    viewModel.clearCollectionCover(context, it.collection.collectionId)
                },
                onDelete = { deleting = it },
                onTogglePin = {
                    viewModel.setCollectionPinned(
                        it.collection.collectionId,
                        !it.collection.isPinned
                    )
                },
                onClearRule = { viewModel.clearCollectionRule(it.collection.collectionId) }
            )
        }
    }

    if (creating) {
        NameDialog(
            title = "New collection",
            label = "Collection name",
            confirmLabel = "Create",
            supporting = "A collection gathers things that belong together but did not happen " +
                "together — every festival, or everything with Kyle. Whatever you add stays " +
                "exactly where it is on the timeline.",
            onDismiss = { creating = false },
            onConfirm = { name ->
                viewModel.createCollection(name)
                creating = false
            }
        )
    }

    renaming?.let { summary ->
        NameDialog(
            title = "Rename collection",
            label = "Collection name",
            initial = summary.collection.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                viewModel.renameCollection(summary.collection.collectionId, name)
                renaming = null
            }
        )
    }

    deleting?.let { summary ->
        DeleteConfirmDialog(
            title = "Delete ${summary.collection.displayName}?",
            // Worth stating plainly: this is the one delete in the app that removes no data, and
            // people reasonably assume otherwise from every other one.
            message = "This removes the grouping only. Its " +
                "${summary.recordCount} recording${if (summary.recordCount == 1) "" else "s"} " +
                "stay exactly where they are on the timeline.",
            onDismiss = { deleting = null },
            onConfirm = {
                viewModel.deleteCollection(summary.collection.collectionId)
                deleting = null
            }
        )
    }
}
