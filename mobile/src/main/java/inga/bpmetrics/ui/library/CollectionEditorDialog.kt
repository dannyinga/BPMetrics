package inga.bpmetrics.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import inga.bpmetrics.ui.components.EditorHeading
import inga.bpmetrics.ui.components.EditorHint
import inga.bpmetrics.ui.components.ItemEditorDialog
import inga.bpmetrics.ui.components.NameField
import inga.bpmetrics.ui.components.NotesField

/** What happens to a collection's rule when its editor is saved. */
sealed interface RuleChange {
    data object Unchanged : RuleChange

    /** Gives it this rule, or replaces the one it has. */
    data class Set(val filter: FilterState) : RuleChange

    /** Living → static, keeping what the rule found. See `LibraryRepository.materialiseCollection`. */
    data object MakeStatic : RuleChange
}

/** What the collection editor hands back when it is saved. */
data class CollectionEdit(
    val name: String,
    val notes: String,
    val pinned: Boolean,
    val rule: RuleChange = RuleChange.Unchanged,
    /** Frozen → live. See `LibraryRepository.thawCollection`. */
    val thaw: Boolean = false
)

/**
 * Everything a collection is, in one place — including what makes it *living*.
 *
 * The model has carried both kinds since §8: a rule that is re-asked every time the set is read,
 * hand-picked members that stay as you left them, and any mix of the two. So the two states are
 * named and both directions are offered:
 *
 * - **Living.** A rule, editable here. Recordings made tomorrow that match are in it tomorrow.
 * - **Static.** A list. What is in it is what somebody put in it.
 *
 * Going living → static **keeps what the rule found** — see
 * `LibraryRepository.materialiseCollection`. Simply dropping the rule throws away everything the
 * rule found and nobody named by hand; that is right when the rule was a mistake and wrong when the
 * rule was the point and you now want the answer held still.
 *
 * **Frozen** is a third thing and deliberately not on this axis: it is a *copy of the numbers*, for
 * recordings that may not survive. It can only be undone, never set here — freezing is
 * "Save analysis" on the page whose numbers are being kept.
 *
 * **Every change waits for Save.** The pin, the rule, unfreezing and "stop updating" each used to
 * apply the moment they were tapped, so Cancel kept a rename from happening and nothing else.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionEditorDialog(
    collection: CollectionEntity,
    recordCount: Int,
    /** What the rule can be narrowed by, for the rule editor. */
    filterOptions: FilterOptions,
    /** The terms of an arbitrary filter, resolved to names. See `LibraryViewModel.chipsFor`. */
    chipsOf: (FilterState) -> List<FilterChip>,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (CollectionEdit) -> Unit
) {
    val id = collection.collectionId
    var name by rememberSaveable(id) { mutableStateOf(collection.name) }
    var notes by rememberSaveable(id) { mutableStateOf(collection.notes) }
    var pinned by rememberSaveable(id) { mutableStateOf(collection.isPinned) }
    var thaw by rememberSaveable(id) { mutableStateOf(false) }
    var ruleChange by remember(id) { mutableStateOf<RuleChange>(RuleChange.Unchanged) }
    var editingRule by remember { mutableStateOf(false) }
    var tried by rememberSaveable(id) { mutableStateOf(false) }

    // The rule as it will be once saved, which is what the editor shows.
    val rule = when (val change = ruleChange) {
        RuleChange.Unchanged -> collection.rule()
        is RuleChange.Set -> change.filter
        RuleChange.MakeStatic -> null
    }
    val chips = rule?.let(chipsOf).orEmpty()

    if (editingRule) {
        CollectionRuleDialog(
            initial = rule ?: FilterState(),
            options = filterOptions,
            chipsOf = chipsOf,
            onDismiss = { editingRule = false },
            onConfirm = { ruleChange = RuleChange.Set(it); editingRule = false }
        )
    }

    ItemEditorDialog(
        title = "Edit collection",
        onDismiss = onDismiss,
        problem = "It needs a name.".takeIf { tried && name.isBlank() },
        onDelete = onDelete,
        deleteLabel = "Delete ${collection.displayName}",
        onConfirm = {
            if (name.isBlank()) {
                tried = true
            } else {
                onSave(CollectionEdit(name.trim(), notes.trim(), pinned, ruleChange, thaw))
            }
        }
    ) {
        NameField(name, { name = it }, error = "Give it a name.".takeIf { tried && name.isBlank() })
        NotesField(notes) { notes = it }

        EditorHeading("How it collects")
        when {
            collection.isFrozen && !thaw -> {
                EditorHint(
                    "Frozen — these numbers stay as they were. Unfreezing keeps the recordings " +
                        "that still exist and lets the set be edited again."
                )
                OutlinedButton(onClick = { thaw = true }) { Text("Unfreeze") }
            }

            collection.isFrozen -> {
                PendingChange("Unfreezes when you save.") { thaw = false }
            }

            ruleChange == RuleChange.MakeStatic -> {
                PendingChange("Keeps these $recordCount and stops updating when you save.") {
                    ruleChange = RuleChange.Unchanged
                }
            }

            rule != null -> {
                EditorHint("Living — anything matching stays in it as the library grows.")
                // The rule as its terms, not as a sentence. It is the same shape as the library's
                // own filter bar because it is the same object.
                RuleChips(chips) { editingRule = true }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editingRule = true }) { Text("Edit rule") }
                    // Only for a rule it already had: one set in this editor and not yet saved has
                    // found nothing to keep.
                    if (collection.rule() != null) {
                        // Named for what it does, not for what it stops doing.
                        OutlinedButton(onClick = { ruleChange = RuleChange.MakeStatic }) {
                            Text("Keep these $recordCount and stop updating")
                        }
                    }
                }
            }

            else -> {
                EditorHint("Static — what is in it is what you put in it.")
                OutlinedButton(onClick = { editingRule = true }) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    // A rule can be added to a set that already has members: the two combine,
                    // which is "every Subtronics recording, plus these three that belong with them
                    // anyway".
                    Text("Give it a rule")
                }
            }
        }

        PinRow(pinned) { pinned = it }
    }
}

/**
 * Making a collection, asking what a collection actually needs.
 *
 * Two questions beyond its name. **What it collects** is the whole distinction between the two kinds
 * and cannot sensibly be deferred; **whether it is pinned** is one switch and is the difference
 * between a set you use and one you have to go and find. A cover has to wait, because a cover is
 * stored against an id that does not exist until this dialog returns.
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
    onCreate: (CollectionEdit) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var living by rememberSaveable { mutableStateOf(false) }
    var pinned by rememberSaveable { mutableStateOf(false) }
    var rule by remember { mutableStateOf(FilterState()) }
    var editingRule by remember { mutableStateOf(false) }
    var tried by rememberSaveable { mutableStateOf(false) }

    val chips = chipsOf(rule)
    // A living collection with no terms would match the whole library, which is not a collection.
    val problems = listOfNotNull(
        "It needs a name.".takeIf { name.isBlank() },
        "A living collection needs a rule.".takeIf { living && chips.isEmpty() }
    )

    if (editingRule) {
        CollectionRuleDialog(
            initial = rule,
            options = filterOptions,
            chipsOf = chipsOf,
            onDismiss = { editingRule = false },
            onConfirm = { rule = it; editingRule = false }
        )
    }

    ItemEditorDialog(
        title = "New collection",
        confirmLabel = "Create",
        onDismiss = onDismiss,
        problem = problems.joinToString(" ").takeIf { tried && problems.isNotEmpty() },
        onConfirm = {
            if (problems.isNotEmpty()) {
                tried = true
            } else {
                onCreate(
                    CollectionEdit(
                        name = name.trim(),
                        notes = notes.trim(),
                        pinned = pinned,
                        rule = if (living) RuleChange.Set(rule) else RuleChange.Unchanged
                    )
                )
                onDismiss()
            }
        }
    ) {
        EditorHint(
            "A collection gathers things that belong together but did not happen together — " +
                "every festival, or everything with Kyle. Whatever you add stays exactly where it " +
                "is on the timeline."
        )
        NameField(name, { name = it }, error = "Give it a name.".takeIf { tried && name.isBlank() })
        NotesField(notes) { notes = it }

        EditorHeading("How it collects")
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
                    // Straight into the rule, because choosing "living" without saying what it
                    // collects leaves a collection that means nothing yet.
                    if (chips.isEmpty()) editingRule = true
                },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = {},
                label = { Text("Living") }
            )
        }
        EditorHint(
            if (living) "Anything matching stays in it as the library grows."
            else "What is in it is what you put in it."
        )

        if (living) {
            RuleChips(chips) { editingRule = true }
            OutlinedButton(onClick = { editingRule = true }) {
                Text(if (chips.isEmpty()) "Set the rule" else "Edit rule")
            }
            if (tried && chips.isEmpty()) EditorHint("Set what it collects.", isError = true)
        }

        // Only worth mentioning where there is something waiting to go in. Both can be true at
        // once — a rule *and* these three.
        if (recordCount > 0) {
            EditorHint(
                "$recordCount selected recording${if (recordCount == 1) "" else "s"} will be added" +
                    if (living) ", rule or no rule." else "."
            )
        }

        PinRow(pinned) { pinned = it }

        // Said rather than left to be discovered: the reason is not obvious, and the alternative
        // is looking for a control that cannot be here yet.
        EditorHint("A photo can be added once it exists — from the collection's own page.")
    }
}

/** A rule's terms as chips, each opening the rule. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleChips(chips: List<FilterChip>, onEdit: () -> Unit) {
    if (chips.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { chip ->
            AssistChip(
                onClick = onEdit,
                label = { Text(chip.label) },
                leadingIcon = {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                }
            )
        }
    }
}

/** A change that waits for Save, said as what will happen, with the way back. */
@Composable
private fun PendingChange(text: String, onUndo: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onUndo) { Text("Undo") }
    }
}

/** Whether the collection sits on the library's filter bar. */
@Composable
private fun PinRow(pinned: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.PushPin,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("Pin to the library", style = MaterialTheme.typography.bodyMedium)
            EditorHint("A chip on the filter bar, so it is one tap away.")
        }
        Switch(checked = pinned, onCheckedChange = onChange)
    }
}
