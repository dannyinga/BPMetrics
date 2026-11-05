package inga.bpmetrics.ui.restore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import inga.bpmetrics.export.RestoreChoice
import inga.bpmetrics.export.RestoreChoices
import inga.bpmetrics.export.RestoreItem
import inga.bpmetrics.export.RestoreKind

/**
 * Everything a file holds, each with Add, Replace or Skip, before anything is written.
 *
 * Grouped by kind, because a backup can hold hundreds of recordings and a flat list of them is a
 * scroll nobody finishes. Each kind has one shortcut for all of its items and opens to set them one
 * by one. What's here already starts on Skip, which keeps yours as it is; what's new starts on Add.
 *
 * Stays up while the confirmed choices are applied, with a count, and can't be dismissed then: a
 * restore is one transaction, and walking away from it only hid that it was still running.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportPreviewDialog(
    plan: ImportSession.Plan,
    progress: ImportSession.Progress?,
    onConfirm: (RestoreChoices) -> Unit,
    onDismiss: () -> Unit
) {
    val preview = plan.preview
    val overrides = remember(plan) { mutableStateMapOf<String, RestoreChoice>() }
    val choices = RestoreChoices(overrides.toMap())
    fun choiceOf(item: RestoreItem) = choices.of(item.kind, item.key, item.matched)
    val working = progress != null

    val counts = preview.items.groupingBy { choiceOf(it) }.eachCount()
    val anything = preview.changesAnything(choices)

    Dialog(
        onDismissRequest = { if (!working) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("What to bring in")
                            if (plan.of > 1) {
                                Text(
                                    "File ${plan.position} of ${plan.of}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(enabled = !working, onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    },
                    actions = {
                        TextButton(enabled = anything && !working, onClick = { onConfirm(choices) }) {
                            Text(if (working) "Working…" else "Bring in")
                        }
                    }
                )

                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { if (progress.total == 0) 0f else progress.done / progress.total.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Working through ${progress.done} of ${progress.total} recordings…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }

                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            if (!anything) "Nothing to bring in with these choices."
                            else listOfNotNull(
                                counts[RestoreChoice.ADD]?.let { "Adds $it" },
                                counts[RestoreChoice.REPLACE]?.let { "Replaces $it" },
                                counts[RestoreChoice.SKIP]?.let { "Skips $it" }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }

                    RestoreKind.entries.forEach { kind ->
                        val ofKind = preview.of(kind)
                        if (ofKind.isEmpty()) return@forEach
                        item(key = kind.name) {
                            KindCard(
                                kind = kind,
                                items = ofKind,
                                choiceOf = ::choiceOf,
                                enabled = !working,
                                onChoose = { item, choice ->
                                    overrides[RestoreChoices.id(item.kind, item.key)] = choice
                                }
                            )
                        }
                    }

                    item {
                        Column(Modifier.padding(top = 8.dp)) {
                            if (preview.unreadableRecordings > 0) {
                                Text(
                                    "${preview.unreadableRecordings} recording" +
                                        "${if (preview.unreadableRecordings == 1) "" else "s"} in the " +
                                        "file can't be read and will be left out.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            Text(
                                // Said outright, because the other way back (Roll back) does replace
                                // the library, and the two used to sit side by side under one word.
                                "Nothing in your library is deleted. Skip keeps yours as it is. " +
                                    "Replace writes the file's version over yours, though never a " +
                                    "recording's readings. Skipping an event puts what was in it " +
                                    "into the event above it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindCard(
    kind: RestoreKind,
    items: List<RestoreItem>,
    choiceOf: (RestoreItem) -> RestoreChoice,
    enabled: Boolean,
    onChoose: (RestoreItem, RestoreChoice) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val here = items.count { it.matched }
    val new = items.size - here

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    kind.many.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    listOfNotNull(
                        new.takeIf { it > 0 }?.let { "$it new" },
                        here.takeIf { it > 0 }?.let {
                            if (kind == RestoreKind.SETTING) "$it differ from yours" else "$it already here"
                        }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    tally(items.map(choiceOf)),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                OutlinedButton(enabled = enabled, onClick = { menu = true }) { Text("All…") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    shortcuts(kind).forEach { (label, pick) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                menu = false
                                items.forEach { item -> pick(item)?.let { onChoose(item, it) } }
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (open) "Hide each ${kind.one}" else "Show each ${kind.one}"
            )
        }

        if (open) {
            HorizontalDivider()
            // Not lazy: inside a lazy list already, and capped by what a file carries per kind.
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                items.forEach { item ->
                    ItemRow(item, choiceOf(item), enabled) { onChoose(item, it) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemRow(
    item: RestoreItem,
    choice: RestoreChoice,
    enabled: Boolean,
    onChoose: (RestoreChoice) -> Unit
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(item.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = listOfNotNull(
            when {
                item.kind == RestoreKind.SETTING -> "${shown(item.key, item.from)} → ${shown(item.key, item.to)}"
                item.matched -> "Already here"
                else -> "New"
            },
            item.at?.let { inga.bpmetrics.ui.util.StringFormatHelpers.getDateString(it, inga.bpmetrics.ui.util.ReaderClock) },
            item.detail
        ).joinToString(" · ")
        Text(
            sub,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            item.options.forEachIndexed { i, option ->
                SegmentedButton(
                    selected = option == choice,
                    enabled = enabled,
                    onClick = { onChoose(option) },
                    shape = SegmentedButtonDefaults.itemShape(i, item.options.size)
                ) { Text(label(item, option), maxLines = 1) }
            }
        }
    }
}

/** What a choice is called for this item. Skip on something here keeps yours. */
private fun label(item: RestoreItem, choice: RestoreChoice): String = when {
    item.kind == RestoreKind.SETTING && choice == RestoreChoice.SKIP -> "Keep mine"
    item.kind == RestoreKind.SETTING -> "Use file's"
    choice == RestoreChoice.ADD && item.matched -> "Add a copy"
    choice == RestoreChoice.ADD -> "Add"
    choice == RestoreChoice.REPLACE -> "Replace"
    else -> "Skip"
}

/** "Adds 3 · Skips 110", for a kind's current choices. */
private fun tally(chosen: List<RestoreChoice>): String {
    val c = chosen.groupingBy { it }.eachCount()
    return listOfNotNull(
        c[RestoreChoice.ADD]?.let { "Adds $it" },
        c[RestoreChoice.REPLACE]?.let { "Replaces $it" },
        c[RestoreChoice.SKIP]?.let { "Skips $it" }
    ).joinToString(" · ")
}

/** The shortcuts offered for every item of a kind, as a label and what each item becomes. */
private fun shortcuts(kind: RestoreKind): List<Pair<String, (RestoreItem) -> RestoreChoice?>> =
    if (kind == RestoreKind.SETTING) listOf(
        "Keep mine for all" to { _ -> RestoreChoice.SKIP },
        "Use the file's for all" to { _ -> RestoreChoice.REPLACE }
    ) else listOfNotNull(
        "Add new, skip what's here" to { item ->
            if (item.matched) RestoreChoice.SKIP else RestoreChoice.ADD
        },
        ("Add new, replace what's here" to { item: RestoreItem ->
            if (item.matched) RestoreChoice.REPLACE else RestoreChoice.ADD
        }).takeIf { RestoreChoice.REPLACE in kind.options(matched = true) },
        "Skip all" to { _ -> RestoreChoice.SKIP }
    )

/** A stored setting value as Settings would describe it. */
private fun shown(key: String, value: String?): String = when {
    value == null -> "not set"
    value == "true" -> "On"
    value == "false" -> "Off"
    key == "date_format" -> runCatching {
        java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern(value))
    }.getOrDefault(value)
    key == "default_sort" -> runCatching {
        inga.bpmetrics.ui.library.LibraryViewModel.SortOption.valueOf(value).label
    }.getOrDefault(value)
    else -> value
}
