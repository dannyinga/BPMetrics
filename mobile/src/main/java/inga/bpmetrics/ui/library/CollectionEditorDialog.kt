package inga.bpmetrics.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.CollectionEntity
import inga.bpmetrics.library.FilterState
import inga.bpmetrics.library.displayName
import inga.bpmetrics.library.rule

/**
 * Everything a collection is, in one place — including what makes it *living*.
 *
 * The model has carried both kinds since §8: a rule that is re-asked every time the set is read,
 * hand-picked members that stay as you left them, and any mix of the two. Only half of that was
 * reachable. A rule could be created exactly one way — narrow the library, press "Save view" — and
 * changed exactly one way, by doing that again under the same name. From the collection itself
 * there was no way to see what it asked, let alone change it, and no way to give a hand-made set a
 * rule at all.
 *
 * So the two states are named and both directions are offered:
 *
 * - **Living.** A rule, editable here. Recordings made tomorrow that match are in it tomorrow.
 * - **Static.** A list. What is in it is what somebody put in it.
 *
 * Going living → static **keeps what the rule found** — see
 * `LibraryRepository.materialiseCollection`. Simply dropping the rule, which is what the old
 * "Stop using a rule" did, throws away everything the rule found and nobody named by hand; that is
 * right when the rule was a mistake and wrong when the rule was the point and you now want the
 * answer held still.
 *
 * **Frozen** is a third thing and deliberately not on this axis: it is a *copy of the numbers*, for
 * recordings that may not survive. It can only be undone, never set here — freezing is
 * "Save analysis" on the page whose numbers are being kept.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionEditorDialog(
    collection: CollectionEntity,
    recordCount: Int,
    /** Choosing, framing and softening its picture. See `CoverEditor`. */
    coverEditor: (@Composable () -> Unit)? = null,
    /** What the rule can be narrowed by, for the rule editor. */
    filterOptions: FilterOptions,
    /** The terms of an arbitrary filter, resolved to names. See `LibraryViewModel.chipsFor`. */
    chipsOf: (FilterState) -> List<FilterChip>,
    onRename: (String) -> Unit,
    onSetRule: (FilterState) -> Unit,
    /** Living → static, keeping what the rule found. */
    onMakeStatic: () -> Unit,
    /** Frozen → live. Null when it is not frozen. */
    onThaw: (() -> Unit)? = null,
    onTogglePin: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable(collection.collectionId) { mutableStateOf(collection.name) }
    var editingRule by remember { mutableStateOf(false) }
    val rule = collection.rule()
    val chips = rule?.let(chipsOf).orEmpty()

    if (editingRule) {
        CollectionRuleDialog(
            initial = rule ?: FilterState(),
            options = filterOptions,
            chipsOf = chipsOf,
            onDismiss = { editingRule = false },
            onConfirm = { onSetRule(it); editingRule = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit collection") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))
                Text("How it collects", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))

                when {
                    collection.isFrozen -> {
                        Text(
                            "Frozen — these numbers stay as they were. Unfreezing keeps the " +
                                "recordings that still exist and lets the set be edited again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        onThaw?.let {
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = it) { Text("Unfreeze") }
                        }
                    }

                    rule != null -> {
                        Text(
                            "Living — anything matching stays in it as the library grows.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        // The rule as its terms, not as a sentence. It is the same shape as the
                        // library's own filter bar because it is the same object.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            chips.forEach { chip ->
                                androidx.compose.material3.AssistChip(
                                    onClick = { editingRule = true },
                                    label = { Text(chip.label) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { editingRule = true }) { Text("Edit rule") }
                            // Named for what it does, not for what it stops doing. "Stop using a
                            // rule" said nothing about the recordings it was about to drop.
                            OutlinedButton(onClick = onMakeStatic) {
                                Text("Keep these $recordCount and stop updating")
                            }
                        }
                    }

                    else -> {
                        Text(
                            "Static — what is in it is what you put in it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { editingRule = true }) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            // A rule can be added to a set that already has members: the two
                            // combine, which is "every Subtronics recording, plus these three that
                            // belong with them anyway".
                            Text("Give it a rule")
                        }
                    }
                }

                coverEditor?.let { editor ->
                    Spacer(Modifier.height(16.dp))
                    editor()
                }

                Spacer(Modifier.height(16.dp))
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Pin to the library", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "A chip on the filter bar, so it is one tap away.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = collection.isPinned, onCheckedChange = onTogglePin)
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Delete ${collection.displayName}")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onRename(name.trim()); onDismiss() }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Making a collection, asking what a collection actually needs.
 *
 * It was a name field. That is enough for a *static* set and not enough for anything else: a living
 * collection could not be created at all, only made afterwards by editing one — so the useful half
 * of the feature was reachable only by someone who already knew it existed and where.
 *
 * Two questions, and only two. **What it collects** is the whole distinction between the two kinds
 * and cannot sensibly be deferred; **whether it is pinned** is one switch and is the difference
 * between a set you use and one you have to go and find. Everything else can wait for the editor —
 * and a cover has no choice but to wait, because a cover is stored against an id that does not
 * exist until this dialog returns.
 *
 * @param recordCount How many are about to go in, when this was opened from a selection.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewCollectionDialog(
    filterOptions: FilterOptions,
    chipsOf: (FilterState) -> List<FilterChip>,
    recordCount: Int = 0,
    onDismiss: () -> Unit,
    onCreate: (name: String, rule: FilterState?, pinned: Boolean) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var living by rememberSaveable { mutableStateOf(false) }
    var pinned by rememberSaveable { mutableStateOf(false) }
    var rule by remember { mutableStateOf(FilterState()) }
    var editingRule by remember { mutableStateOf(false) }

    val chips = chipsOf(rule)
    // A living collection with no terms would match the whole library, which is not a collection.
    val ready = name.isNotBlank() && (!living || chips.isNotEmpty())

    if (editingRule) {
        CollectionRuleDialog(
            initial = rule,
            options = filterOptions,
            chipsOf = chipsOf,
            onDismiss = { editingRule = false },
            onConfirm = { rule = it; editingRule = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New collection") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "A collection gathers things that belong together but did not happen " +
                        "together — every festival, or everything with Kyle. Whatever you add " +
                        "stays exactly where it is on the timeline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))
                Text("How it collects", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !living,
                        onClick = { living = false },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        icon = {},
                        label = { Text("Static") }
                    )
                    SegmentedButton(
                        selected = living,
                        onClick = {
                            living = true
                            // Straight into the rule, because choosing "living" without saying
                            // what it collects leaves a collection that means nothing yet.
                            if (chips.isEmpty()) editingRule = true
                        },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        icon = {},
                        label = { Text("Living") }
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (living) {
                        "Anything matching stays in it as the library grows."
                    } else {
                        "What is in it is what you put in it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (living) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        chips.forEach { chip ->
                            androidx.compose.material3.AssistChip(
                                onClick = { editingRule = true },
                                label = { Text(chip.label) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            )
                        }
                        OutlinedButton(onClick = { editingRule = true }) {
                            Text(if (chips.isEmpty()) "Set the rule" else "Edit rule")
                        }
                    }
                }

                // Only worth mentioning where there is something waiting to go in. Both can be
                // true at once — a rule *and* these three — which the model has always allowed and
                // nothing has ever offered to set up in one go.
                if (recordCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "$recordCount selected recording${if (recordCount == 1) "" else "s"} " +
                            "will be added" + if (living) ", rule or no rule." else ".",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(16.dp))
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Pin to the library", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "A chip on the filter bar, so it is one tap away.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = pinned, onCheckedChange = { pinned = it })
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    // Said rather than left to be discovered: the reason is not obvious, and the
                    // alternative is looking for a control that cannot be here yet.
                    "A photo can be added once it exists — from the collection's own page.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    onCreate(name.trim(), rule.takeIf { living }, pinned)
                    onDismiss()
                }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
