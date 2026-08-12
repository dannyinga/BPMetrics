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
    LaunchedEffect(Unit) {
        viewModel.message.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

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
            AppearanceSection(viewModel)
            LibrarySection(viewModel)
            HeartRateSection(viewModel)
            ExportSection(viewModel)
            StorageSection(viewModel)
            SyncSection(viewModel)
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

    SettingsGroup("Appearance", "Colour, and how times are written") {
        // No theme choice: the app is dark, deliberately. The charts, the export panel and the
        // metric ramp are designed against it, and offering light and system when only one of the
        // three is supported is worse than not offering the choice.
        SwitchRow(
            "Wallpaper colours",
            // Android 12 is where the platform gained this. Below it the toggle would be a
            // control over nothing, so it says so rather than pretending.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                "Take the app's colours from your wallpaper"
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
private fun HeartRateSection(viewModel: SettingsViewModel) {
    val resting by viewModel.restingBpm.collectAsStateWithLifecycle()
    val max by viewModel.maxBpm.collectAsStateWithLifecycle()

    SettingsGroup("Heart rate", "The figures zones are measured against") {
        Hint(
            "A fallback for anyone without figures of their own. Set a person's own resting and " +
                "maximum on their profile — these two are what everyone else is measured against."
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(
                value = resting,
                label = "Resting",
                modifier = Modifier.weight(1f)
            ) { viewModel.setRestingBpm(it) }
            NumberField(
                value = max,
                label = "Maximum",
                modifier = Modifier.weight(1f)
            ) { viewModel.setMaxBpm(it) }
        }
    }
}

@Composable
private fun StorageSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf<StorageInspector.Backup?>(null) }

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
        Label("Database backups")
        Hint(
            // Why this is worth a section: they have always been taken, and until now the only way
            // to reach one was a cable and adb.
            "Taken automatically before each upgrade. Restoring replaces everything in the app " +
                "with what that backup held."
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
                        Hint(StorageInspector.formatSize(backup.bytes))
                    }
                    TextButton(onClick = { confirming = backup }) { Text("Restore") }
                    TextButton(onClick = { viewModel.deleteBackup(context, backup) }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    confirming?.let { backup ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "Everything currently in the app will be replaced with what this backup held " +
                        "on ${getDateString(backup.takenAtMs, ReaderClock)}. Your current database is kept " +
                        "alongside the backups in case you want it back, and BPMetrics will close " +
                        "so the change can take effect."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    viewModel.restoreBackup(context, backup)
                }) {
                    Text("Restore", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SyncSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val incoming by viewModel.incomingCount.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val retry by viewModel.syncRetryMinutes.collectAsStateWithLifecycle()

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
            Toast.makeText(context, "Checking the watch…", Toast.LENGTH_SHORT).show()
        }) {
            Text("Check for recordings now")
        }

        Spacer(Modifier.height(12.dp))
        NumberField(
            value = retry,
            label = "Retry every (minutes)",
            modifier = Modifier.fillMaxWidth()
        ) { viewModel.setSyncRetryMinutes(it) }
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
        Toast.makeText(context, "Could not gather diagnostics", Toast.LENGTH_SHORT).show()
    }
}

/** Closes the app so a restored database is picked up on the next launch. */
private fun relaunch(context: Context) {
    Toast.makeText(context, "Restored. Reopen BPMetrics.", Toast.LENGTH_LONG).show()
    (context as? android.app.Activity)?.finishAffinity()
    // The process is what holds the open database handle, so it has to go with the activity.
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
        { android.os.Process.killProcess(android.os.Process.myPid()) },
        1200L
    )
}

// --- Shared pieces ---

@Composable
private fun SettingsGroup(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    // Collapsed on open, and saveable so that a rotation — or a trip into the preset editor and
    // back — does not silently close the section someone was working in. Keyed by title because
    // the sections are siblings in one Column and would otherwise share a slot.
    var expanded by rememberSaveable(title) { mutableStateOf(false) }

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
 * A number, written through as it is typed.
 *
 * Holds its own text so a half-typed "6" on the way to "60" is not committed and echoed back — the
 * value is applied whenever what is typed parses, and the field keeps showing the characters.
 */
@Composable
private fun NumberField(
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
    onValue: (Int) -> Unit
) {
    var editing by remember(value) { mutableStateOf<String?>(null) }

    OutlinedTextField(
        value = editing ?: value.toString(),
        onValueChange = { typed ->
            val cleaned = typed.filter { it.isDigit() }.take(4)
            editing = cleaned
            cleaned.toIntOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
        ),
        modifier = modifier
    )
}

/**
 * Export settings.
 *
 * Not a second home for the appearance options — those live in a preset, and duplicating them here
 * is exactly what this screen used to do wrong. What belongs here is everything *about* presets
 * that no single preset can hold: which one new exports start from, getting one in or out of the
 * app, and the time zone stamped on a video when nothing else says.
 */
@Composable
private fun ExportSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val timeZone by viewModel.defaultTimeZone.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<inga.bpmetrics.library.ExportPresetEntity?>(null) }
    var editing by remember { mutableStateOf<inga.bpmetrics.library.ExportPresetEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
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
        Label("Default preset")
        Hint("What a new export opens with. The look itself is edited in the export utility.")
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
                    TextButton(onClick = { editing = preset }) { Text("Edit") }
                    TextButton(onClick = {
                        viewModel.stagePresetForExport(preset)
                        exportLauncher.launch("${preset.name}.bpmpreset.json")
                    }) { Text("Share") }
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

        Spacer(Modifier.height(16.dp))
        Label("Time zone")
        Hint(
            // Stamped onto a video's clock labels. Almost always the phone's own, and worth
            // changing exactly once: when the footage was filmed somewhere else.
            "Used for the times drawn on an export. Change it when the recordings were made in " +
                "a different zone from the one you are in now."
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = timeZone,
            onValueChange = { viewModel.setDefaultTimeZone(it) },
            label = { Text("Zone id") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Hint("This phone is in ${java.time.ZoneId.systemDefault().id}")
    }

    editing?.let { entity ->
        val loaded = inga.bpmetrics.export.ExportPreset.fromJson(entity.configJson)
            ?: inga.bpmetrics.export.ExportPreset(name = entity.name)
        PresetEditorDialog(
            initial = loaded.copy(name = entity.name),
            heading = "Edit preset",
            onePerson = previewSubjects.first,
            severalPeople = previewSubjects.second,
            people = peopleById,
            onDismiss = { editing = null },
            onSave = { newName, preset ->
                viewModel.updatePreset(entity, newName, preset)
                editing = null
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

    val subject = if (showSeveral) severalPeople else onePerson

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Full screen rather than a boxed dialog: this is a form with six collapsible sections in
        // it, and the cramped Split dialog was the lesson about putting one of those in a box.
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(heading) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
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
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Preset name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))

                // Something to judge it against. Without this the placement chips, the panel
                // opacity and the colours all describe a picture nobody can see.
                if (subject.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
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
                            // The two look genuinely different: a lone curve is a blue-to-red
                            // gradient, several take a colour each. A preset that reads well for
                            // one can be unreadable for the other.
                            label = { Text("Several people") }
                        )
                    }

                    inga.bpmetrics.ui.export.ExportPreview(
                        records = subject,
                        preset = draft,
                        // No clip and no footage: the curves are drawn on the canvas alone, which
                        // is what an export with nothing behind it looks like anyway.
                        clip = null,
                        placement = inga.bpmetrics.ui.export.GraphPlacement.of(draft),
                        onPlacementChange = { draft = it.into(draft) },
                        overlay = null,
                        colours = PreviewSubjects.coloursFor(subject, people),
                        title = name.ifBlank { "Preview" },
                        at = scrubAt,
                        onScrub = { scrubAt = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(14.dp))
                }

                inga.bpmetrics.ui.export.LookSections(
                    preset = draft,
                    onChange = { draft = it },
                    overlay = null,
                    onPickOverlay = {},
                    onClearOverlay = {},
                    // No clips here, and no preview to judge them against. Both of those belong to
                    // an export; a preset is the part that outlives one.
                    hasClips = false,
                    syncOffsetMs = draft.syncOffsetMs,
                    onSyncOffsetChange = { draft = draft.copy(syncOffsetMs = it) },
                    framing = inga.bpmetrics.ui.export.GraphPlacement.of(draft),
                    onFramingChange = { draft = it.into(draft) },
                    hasPreview = subject.isNotEmpty(),
                    showPresetBar = false
                )
                Spacer(Modifier.height(32.dp))
            }
        }
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
