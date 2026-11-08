package inga.bpmetrics.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import inga.bpmetrics.ui.components.ExpandableSection
import kotlinx.coroutines.launch
import inga.bpmetrics.ui.components.FlowRow
import inga.bpmetrics.ui.library.LibraryViewModel
import inga.bpmetrics.ui.util.ReaderClock
import inga.bpmetrics.ui.util.StringFormatHelpers.getDateString

/**
 * Settings, grouped and applied immediately.
 *
 * Two things changed here at once. Changes now take effect as they are made — the previous screen
 * staged everything, tracked whether it was dirty, and asked "Would you like to save your changes
 * before leaving?" on the way out, which no other settings screen on the platform does and which
 * was the only reason it needed a back handler.
 *
 * And most of what it contained is gone: three of its four sections were export defaults, which
 * presets replaced. What is left is what a settings screen for *this* app should actually hold,
 * most of which did not exist before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenDrawer: () -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.refreshStorage(context) }

    val restarting by viewModel.restarting.collectAsStateWithLifecycle()
    LaunchedEffect(restarting) {
        if (restarting) relaunch(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Open navigation drawer")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            // Status first: whether recordings are still on their way is the one thing here that
            // changes on its own, and it was last, below six groups of preferences. Then Backup,
            // open, because it's what people come here to do. Heart rate, the sync retry interval
            // and the export time zone are gone: nothing read them (Audit #7, D1).
            SyncSection(viewModel)
            BackupSection(viewModel)
            LibrarySection(viewModel)
            AppearanceSection(viewModel)
            ExportSection(viewModel)
            StorageSection(viewModel)
            // No About section here: the app already has one, and two places claiming to be the
            // version number is how they come to disagree.
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AppearanceSection(viewModel: SettingsViewModel) {
    val dynamic by viewModel.dynamicColour.collectAsStateWithLifecycle()
    val use24 by viewModel.use24Hour.collectAsStateWithLifecycle()
    val dateFormat by viewModel.dateFormat.collectAsStateWithLifecycle()

    SettingsGroup("Appearance", "Color, and how times are written") {
        // No theme choice: the app is dark, deliberately. The charts, the export panel and the
        // metric ramp are designed against it, and offering light and system when only one of the
        // three is supported is worse than not offering the choice.
        SwitchRow(
            "Wallpaper colors",
            // Android 12 is where the platform gained this. Below it the toggle would be a
            // control over nothing, so it says so rather than pretending.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                "Take the app's colors from your wallpaper"
            } else {
                "Not available on this version of Android"
            },
            checked = dynamic,
            enabled = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
        ) { viewModel.setDynamicColour(it) }

        SwitchRow("24-hour clock", null, checked = use24) { viewModel.setUse24Hour(it) }

        Spacer(Modifier.height(8.dp))
        Label("Date format")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DateFormats.ALL.forEach { (pattern, example) ->
                FilterChip(
                    selected = dateFormat == pattern,
                    onClick = { viewModel.setDateFormat(pattern) },
                    label = { Text(example) }
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Hint("Today reads as ${getDateString(System.currentTimeMillis(), ReaderClock)}")
    }
}

@Composable
private fun LibrarySection(viewModel: SettingsViewModel) {
    val categories by viewModel.allCategories.collectAsStateWithLifecycle(initialValue = emptyList())
    val namingCategoryId by viewModel.defaultNamingCategoryId.collectAsStateWithLifecycle()
    val defaultSort by viewModel.defaultSort.collectAsStateWithLifecycle()

    SettingsGroup("Library", "How the library opens, and how new recordings are named") {
        Label("Sorted by")
        // The sort decides the shape as well as the order, so this is also the "opens on"
        // preference the segmented view switcher used to need.
        Hint(
            "By time keeps your events; any other order lists every recording. Updated as you " +
                "change it in the library, so it reopens the way you left it."
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LibraryViewModel.SortOption.entries.forEach { option ->
                FilterChip(
                    selected = defaultSort == option.name,
                    onClick = { viewModel.setDefaultSort(option.name) },
                    label = { Text("${option.label} — ${option.shapeLabel}") }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Label("Auto-naming")
        Hint("New recordings are named after a tag from this category, when one applies.")
        Spacer(Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = namingCategoryId == null,
                onClick = { viewModel.setDefaultNamingCategory(null) },
                label = { Text("Off") }
            )
            categories.forEach { category ->
                FilterChip(
                    selected = namingCategoryId == category.categoryId,
                    onClick = { viewModel.setDefaultNamingCategory(category.categoryId) },
                    label = { Text(category.name) }
                )
            }
        }
    }
}

@Composable
private fun StorageSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf<StorageInspector.Backup?>(null) }
    var deletingBackup by remember { mutableStateOf<StorageInspector.Backup?>(null) }

    SettingsGroup("Storage", "What this app is using, and the backups it keeps") {
        val report = storage
        if (report == null) {
            Hint("Measuring…")
            return@SettingsGroup
        }

        report.items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.label, style = MaterialTheme.typography.bodyMedium)
                    item.detail?.let { Hint(it) }
                }
                Text(
                    StorageInspector.formatSize(item.bytes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Total", fontWeight = FontWeight.Bold)
            Text(StorageInspector.formatSize(report.totalBytes), fontWeight = FontWeight.Bold)
        }
        Hint("${StorageInspector.formatSize(report.freeBytes)} free on the phone")

        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { viewModel.clearStagedExports(context) }) {
            Text("Clear staged exports")
        }

        Spacer(Modifier.height(16.dp))
        Label("Earlier copies of the library")
        Hint(
            // Why this is worth a section: they have always been taken, and until now the only way
            // to reach one was a cable and adb.
            //
            // Called "Roll back" rather than "Restore" because the Backup section has a Restore
            // that does the opposite: that one adds a file to the library, this one replaces the
            // library outright. Two buttons with one name and opposite effects is how data is lost.
            "Taken automatically before each upgrade and before each roll back. Rolling back " +
                "replaces everything in the app with what that copy held."
        )
        Spacer(Modifier.height(6.dp))

        if (report.backups.isEmpty()) {
            Hint("No backups yet — one is taken the next time the database changes shape.")
        } else {
            report.backups.forEach { backup ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            getDateString(backup.takenAtMs, ReaderClock),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Hint(
                            (if (backup.isFromBeforeRollBack) "Your library just before a roll back"
                            else "Before an upgrade") +
                                " · " + StorageInspector.formatSize(backup.bytes)
                        )
                    }
                    TextButton(onClick = { confirming = backup }) { Text("Roll back") }
                    TextButton(onClick = { deletingBackup = backup }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    confirming?.let { backup ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Roll back to ${getDateString(backup.takenAtMs, ReaderClock)}?") },
            text = {
                Text(
                    "Everything currently in the app will be replaced with what this copy held. " +
                        "Anything recorded or changed since then goes. Your current library is " +
                        "kept in this list as \"Your library just before a roll back\", so this " +
                        "can be undone, and BPMetrics will close so the change can take effect."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    viewModel.restoreBackup(context, backup)
                }) {
                    Text("Roll back", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Cancel") }
            }
        )
    }

    deletingBackup?.let { backup ->
        AlertDialog(
            onDismissRequest = { deletingBackup = null },
            title = { Text("Delete this copy?") },
            text = {
                Text(
                    "The copy from ${getDateString(backup.takenAtMs, ReaderClock)} will be gone " +
                        "for good, and you won't be able to roll back to it. Your library as it " +
                        "is now isn't affected."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deletingBackup = null
                    viewModel.deleteBackup(context, backup)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingBackup = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SyncSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val incoming by viewModel.incomingCount.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    SettingsGroup("Sync", "What is still coming from the watch") {
        Text(
            if (incoming == 0) {
                "Nothing waiting."
            } else {
                "$incoming recording${if (incoming == 1) "" else "s"} still arriving."
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(10.dp))
        // The per-record detail that used to be a section of its own in the drawer. A list empty
        // almost all of the time does not earn a permanent place in the navigation, and the
        // question it answers is the one this section is already about.
        inga.bpmetrics.ui.incoming.IncomingList()

        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = {
            // The watch has always shown a pending count; the phone showed nothing, so a transfer
            // that had quietly stalled looked identical to one that had never been started.
            scope.launch {
                (context.applicationContext as inga.bpmetrics.BPMetricsApp)
                    .dataClientProcessor.sweepExistingRecords()
            }
            inga.bpmetrics.ui.feedback.Feedback.say("Checking the watch…")
        }) {
            Text("Check for recordings now")
        }
    }
}

/**
 * Bundles recent logcat output and the storage breakdown into a shareable file.
 *
 * Read back from the app's own logcat buffer rather than a file we keep: nothing here is written
 * down until someone asks for it, which is the right default for something that carries names.
 */
fun shareDiagnostics(context: Context, version: String) {
    runCatching {
        val report = StorageInspector.inspect(context)
        val logs = runCatching {
            val process = Runtime.getRuntime().exec("logcat -d -t 500 -v time")
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrElse { "Could not read logs: ${it.message}" }

        val text = buildString {
            appendLine("BPMetrics $version")
            appendLine("Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MODEL}")
            appendLine()
            appendLine("Storage")
            report.items.forEach {
                appendLine("  ${it.label}: ${StorageInspector.formatSize(it.bytes)}")
            }
            appendLine("  Free: ${StorageInspector.formatSize(report.freeBytes)}")
            appendLine("  Backups: ${report.backups.size}")
            appendLine()
            appendLine("Recent logs")
            appendLine(logs)
        }

        val file = java.io.File(context.cacheDir, "bpmetrics-diagnostics.txt")
        file.writeText(text)
        inga.bpmetrics.export.ExportUtils.shareFile(context, file, "text/plain")
    }.onFailure {
        inga.bpmetrics.ui.feedback.Feedback.failed("Couldn't gather diagnostics.")
    }
}

/** Closes the app so a restored database is picked up on the next launch. */
private fun relaunch(context: Context) {
    // A Toast, not [Feedback]: the app closes a moment later, and a snackbar would go with it.
    Toast.makeText(context, "Rolled back. Reopen BPMetrics.", Toast.LENGTH_LONG).show()
    (context as? android.app.Activity)?.finishAffinity()
    // The process is what holds the open database handle, so it has to go with the activity.
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
        { android.os.Process.killProcess(android.os.Process.myPid()) },
        1200L
    )
}

// --- Shared pieces ---

/**
 * One file holding everything the app knows.
 *
 * Here rather than in the library's selection menu, which is where the only door to this used to
 * be: it could not be reached without first selecting recordings, and then ignored the selection
 * and wrote the whole library regardless. A backup is not an operation on a selection.
 *
 * Deliberately two plain buttons and a sentence saying what is in the file. The value of a backup
 * is entirely in whether someone believes it enough to rely on it, and a list of what it contains
 * does more for that than any amount of styling.
 */
@Composable
private fun BackupSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val busy by viewModel.backupBusy.collectAsStateWithLifecycle()
    val writing by viewModel.writingBackup.collectAsStateWithLifecycle()
    val lastRestore by viewModel.lastRestore.collectAsStateWithLifecycle()
    val plan by viewModel.importSession.plan.collectAsStateWithLifecycle()
    val progress by viewModel.importSession.progress.collectAsStateWithLifecycle()

    plan?.let {
        inga.bpmetrics.ui.restore.ImportPreviewDialog(
            plan = it,
            progress = progress,
            onConfirm = { choices -> viewModel.importSession.confirm(context, choices) },
            onDismiss = { viewModel.importSession.dismiss() }
        )
    }

    val saveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.writeBackup(context, it) } }

    // Every type, not "application/json". A .bpmjson written by this app is often reported by the
    // system as octet-stream, and filtering on the JSON type greys out the file the user is
    // looking straight at.
    val openLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.readBackup(context, it) } }

    SettingsGroup("Backup", "One file with everything in it", initiallyExpanded = true) {
        Hint(
            "A backup holds every recording and its readings, the people and their photographs, " +
                "watches, venues, your events and how they nest, collections and their rules, " +
                "tags, cover images, saved export looks, and these settings."
        )
        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                enabled = !busy,
                onClick = {
                    saveLauncher.launch(inga.bpmetrics.export.JsonExporter.suggestedFileName())
                },
                modifier = Modifier.weight(1f)
            ) { Text(if (writing) "Backing up…" else "Back up") }

            // Progress on the button doing the work. It used to show on Back up while Restore
            // stayed enabled for a second tap.
            OutlinedButton(
                enabled = !busy,
                onClick = { openLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    progress?.let { "Restoring ${it.done} of ${it.total}" }
                        ?: if (busy && !writing) "Reading…" else "Restore from file"
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Hint(
            "Restoring adds to this library rather than replacing it. You'll see everything in " +
                "the file first, and choose for each item whether to add it, replace yours with " +
                "it, or skip it."
        )

        // What the last restore actually did, kept on screen. A toast says "restored" and is gone
        // before anyone can check it against what they expected — and the failure this is here to
        // make visible is a restore that reports success while quietly bringing back less than the
        // file held.
        lastRestore?.let { result ->
            Spacer(Modifier.height(12.dp))
            Label("Last restore")
            Spacer(Modifier.height(4.dp))
            if (!result.succeeded) {
                Text(
                    result.failure ?: "Failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Hint(
                    buildString {
                        append("${result.recordsImported} recordings")
                        if (result.recordsAlreadyHere > 0) {
                            append(" (${result.recordsAlreadyHere} more were already here)")
                        }
                        append(", ${result.eventsCreated} events")
                        append(", ${result.analysesRestored} collections")
                        append(", ${result.peopleCreated} people")
                        append(", ${result.presetsRestored} presets")
                        append(", ${result.settingsRestored} settings")
                        if (result.presetsSkipped > 0) {
                            append(" — ${result.presetsSkipped} preset")
                            if (result.presetsSkipped != 1) append("s")
                            append(" came from a newer version and were skipped")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    subtitle: String,
    /** Open when the screen opens. One group is, so the screen doesn't open on seven closed rows. */
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    // Saveable so that a rotation — or a trip into the preset editor and back — does not silently
    // close the section someone was working in. Keyed by title because the sections are siblings
    // in one Column and would otherwise share a slot.
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        ExpandableSection(
            title = title,
            isExpanded = expanded,
            onToggle = { expanded = !expanded },
            titleStyle = MaterialTheme.typography.titleSmall
        ) {
            Column(Modifier.padding(bottom = 8.dp)) {
                Hint(subtitle)
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SwitchRow(
    label: String,
    detail: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            detail?.let { Hint(it) }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * Export settings.
 *
 * Not a second home for the appearance options — those live in a preset, and duplicating them here
 * is exactly what this screen used to do wrong. What belongs here is everything *about* presets
 * that no single preset can hold: which one new exports start from, and getting one in or out of
 * the app.
 */
@Composable
private fun ExportSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<inga.bpmetrics.library.ExportPresetEntity?>(null) }
    // Saveable, by id: Position turns the screen for a landscape look, which recreates the activity,
    // and a remembered editor closed then, leaving the app on its side with the edits gone.
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editing = editingId?.let { id -> presets.firstOrNull { it.presetId == id } }
    var creating by rememberSaveable { mutableStateOf(false) }
    val previewSubjects by viewModel.previewSubjects.collectAsStateWithLifecycle()
    val peopleById by viewModel.peopleById.collectAsStateWithLifecycle()

    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importPreset(context, it) }
    }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.writePendingPreset(context, it) }
    }

    SettingsGroup("Export", "Presets, and what a new export starts from") {
        Label("Presets")
        // Honest about what the choice does (UX Audit E4): a new export opens with the look used
        // last, and this only answers before there is one.
        Hint("A new export starts from the look you used last. The one picked here is for your first export.")
        Spacer(Modifier.height(6.dp))

        if (presets.isEmpty()) {
            Hint("No presets yet.")
        } else {
            presets.forEach { preset ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = preset.isDefault,
                        onClick = { viewModel.setDefaultPreset(preset) }
                    )
                    Column(Modifier.weight(1f)) {
                        Text(preset.name, style = MaterialTheme.typography.bodyMedium)
                        if (preset.isBuiltIn) Hint("Shipped with the app")
                    }
                    TextButton(onClick = { editingId = preset.presetId }) { Text("Edit") }
                    // "Export", and the same file the export flow writes: it was "Share" for what is a
                    // save dialog, with an unsanitised name and a different extension (UX Audit E3).
                    TextButton(onClick = {
                        viewModel.stagePresetForExport(preset)
                        exportLauncher.launch("${inga.bpmetrics.export.FileNames.of(preset.name)}.bpmpreset")
                    }) { Text("Export") }
                    // Built-ins are protected in the DAO as well as here: a preset the app relies
                    // on being present is not something a stray tap should be able to remove.
                    if (!preset.isBuiltIn) {
                        TextButton(onClick = { deleting = preset }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { creating = true }) { Text("New preset") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                Text("Import")
            }
        }

    }

    editing?.let { entity ->
        val loaded = inga.bpmetrics.export.ExportPreset.fromJson(entity.configJson)
            ?: inga.bpmetrics.export.ExportPreset(name = entity.name)
        // A built-in is kept as shipped and its changes are saved as a new preset, as the export
        // flow already did. Here they could be overwritten and renamed (UX Audit E3).
        PresetEditorDialog(
            initial = loaded.copy(name = if (entity.isBuiltIn) "${entity.name} (mine)" else entity.name),
            heading = if (entity.isBuiltIn) "New preset from ${entity.name}" else "Edit preset",
            onePerson = previewSubjects.first,
            severalPeople = previewSubjects.second,
            people = peopleById,
            onDismiss = { editingId = null },
            onSave = { newName, preset ->
                if (entity.isBuiltIn) viewModel.createPreset(newName, preset)
                else viewModel.updatePreset(entity, newName, preset)
                editingId = null
            }
        )
    }

    if (creating) {
        PresetEditorDialog(
            // Started from the shipped defaults rather than from whichever preset happened to be
            // selected, so "new" means new.
            initial = inga.bpmetrics.export.ExportPreset(name = ""),
            heading = "New preset",
            onePerson = previewSubjects.first,
            severalPeople = previewSubjects.second,
            people = peopleById,
            onDismiss = { creating = false },
            onSave = { newName, preset ->
                viewModel.createPreset(newName, preset)
                creating = false
            }
        )
    }

    deleting?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${preset.name}?") },
            text = { Text("Exports already queued keep the settings they were made with.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePreset(preset)
                    deleting = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Creating or editing a preset, with every setting an export offers.
 *
 * The same [inga.bpmetrics.ui.export.LookSections] the export utility uses, not a second copy of
 * the controls — a preset edited here and a preset edited there have to mean the same thing, and
 * two implementations would be two chances for them not to.
 *
 * What is missing is the preview, and deliberately: judging a look against footage is what the
 * export flow is for. This is where one is set up in advance, so the placement chips are offered
 * and the drag is not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetEditorDialog(
    initial: inga.bpmetrics.export.ExportPreset,
    heading: String,
    // A preset preview draws real curves, so these carry their readings.
    onePerson: List<inga.bpmetrics.library.BpmRecordWithPoints>,
    severalPeople: List<inga.bpmetrics.library.BpmRecordWithPoints>,
    people: Map<Long, inga.bpmetrics.library.PersonEntity>,
    onDismiss: () -> Unit,
    onSave: (String, inga.bpmetrics.export.ExportPreset) -> Unit
) {
    // Saveable, not remembered. A plain `remember` is discarded when the activity is recreated, and
    // a rotation does exactly that — so every unsaved edit in this dialog vanished the moment the
    // phone was turned, with the dialog still open and looking untouched. Nothing warns you; the
    // sliders simply read what they read before you started.
    //
    // The preset goes through its JSON, which is what it is stored as anyway, so this cannot drift
    // from what saving would write. Wrapped in a Saver rather than made Parcelable for that reason:
    // a second serialisation of the same type is a second thing to keep in step.
    var draft by rememberSaveable(stateSaver = ExportPresetSaver) { mutableStateOf(initial) }
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var showSeveral by rememberSaveable { mutableStateOf(false) }
    var scrubAt by rememberSaveable { mutableStateOf(0.45f) }
    var tab by rememberSaveable { mutableStateOf(inga.bpmetrics.ui.export.LookTab.CANVAS) }
    var positioning by rememberSaveable { mutableStateOf<inga.bpmetrics.ui.export.PositionTarget?>(null) }
    var positioningFull by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    val subject = if (showSeveral) severalPeople else onePerson
    // Back with changes asks first. It used to drop them without a word (UX Audit E3).
    val changed = draft != initial || name != initial.name
    val leave = { if (changed) confirmDiscard = true else onDismiss() }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = leave,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(heading) },
                    navigationIcon = {
                        IconButton(onClick = leave) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Cancel"
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { onSave(name.trim(), draft) },
                            enabled = name.isNotBlank()
                        ) { Text("Save") }
                    }
                )
            }
        ) { padding ->
            // The same editor as the export flow, tabs and all (S14B.1): the preview and the name
            // stay put, and the tabs take the rest of the screen.
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Preset name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))

                    // Something to judge it against. Without this the placement chips, the panel
                    // opacity and the colours all describe a picture nobody can see.
                    if (subject.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = !showSeveral,
                                onClick = { showSeveral = false },
                                label = { Text("One person") }
                            )
                            FilterChip(
                                selected = showSeveral,
                                onClick = { showSeveral = true },
                                // A lone curve is a blue-to-red gradient, several take a colour
                                // each. A preset that reads well for one can fail the other.
                                label = { Text("Several people") }
                            )
                        }

                        inga.bpmetrics.ui.export.ExportPreview(
                            records = subject,
                            preset = draft,
                            // No clip and no footage: the curves on the canvas alone.
                            clip = null,
                            placement = inga.bpmetrics.ui.export.GraphPlacement.of(draft),
                            onPlacementChange = { draft = it.into(draft) },
                            overlay = null,
                            colours = PreviewSubjects.coloursFor(subject, people),
                            title = name.ifBlank { "Preview" },
                            at = scrubAt,
                            onScrub = { scrubAt = it },
                            onTapRegion = { tab = inga.bpmetrics.ui.export.LookTab.of(it) },
                            onPresetChange = { draft = it },
                            positioning = positioning,
                            onPositionDone = { positioning = null },
                            onOpenPosition = { positioningFull = true; positioning = inga.bpmetrics.ui.export.PositionTarget.GRAPH },
                            positionFull = positioningFull,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                inga.bpmetrics.ui.export.LookSections(
                    preset = draft,
                    onChange = { draft = it },
                    tab = tab,
                    onTab = { tab = it },
                    modifier = Modifier.weight(1f),
                    onPosition = if (subject.isNotEmpty()) { { positioningFull = false; positioning = it } } else null,
                    people = subject.size
                )
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard your changes?") },
            text = { Text("Nothing you changed here is saved yet.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDismiss() }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            }
        )
    }
}

/**
 * Keeps a preset across an activity being recreated.
 *
 * Through its own JSON, which is how a preset is stored anyway — so what survives a rotation and
 * what would have been saved to the database are produced by the same code. A `Parcelable` would be
 * a second serialisation of the same type and a second thing to remember to update when a field is
 * added; this one cannot fall behind, because `toJson` is already the thing that must not.
 *
 * A payload that fails to read back gives null, and `rememberSaveable` then falls through to the
 * initial value — the same state the dialog would have had before any of this existed.
 */
private val ExportPresetSaver = androidx.compose.runtime.saveable.Saver<
    inga.bpmetrics.export.ExportPreset,
    String
>(
    save = { it.toJson() },
    restore = { inga.bpmetrics.export.ExportPreset.fromJson(it) }
)
