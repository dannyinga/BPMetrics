package inga.bpmetrics.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import inga.bpmetrics.ui.components.BpmEmptyState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import inga.bpmetrics.library.ZoneTime
import inga.bpmetrics.ui.Routes
import inga.bpmetrics.ui.components.FlowRow
import inga.bpmetrics.ui.tags.EffectiveTagChip
import inga.bpmetrics.ui.tags.TagSelectionDialog
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import inga.bpmetrics.ui.theme.BpmAvg
import inga.bpmetrics.ui.theme.BpmHigh
import inga.bpmetrics.ui.theme.BpmLow

/**
 * Aggregate analysis of a set of recordings, whatever named that set.
 *
 * A group and a filter are the same screen — see §4.3 of the product doc. Both ask "across these
 * recordings, what happened and who did what", and only the header differs. Building the group its
 * own page would have been two implementations of one question, free to answer differently.
 *
 * Top to bottom: what is in scope, the three headline numbers, rankings by whichever comparison the
 * records support, per-person totals, and the recordings themselves. Tapping a person anywhere dims
 * the others everywhere, the same interaction as the event page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(
    navController: NavController,
    viewModel: AnalysisViewModel,
    onOpenDrawer: () -> Unit,
    /** Set when this was pushed on top of something, which gets a back arrow instead of a drawer. */
    onBack: (() -> Unit)? = null,
    title: String? = null,
    onSave: ((name: String, records: List<AnalysisRecord>) -> Unit)? = null,
    /** Opens the export utility as an image, scoped to whatever this screen is analysing. */
    onExportImage: (() -> Unit)? = null
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val availableAxes by viewModel.availableAxes.collectAsStateWithLifecycle()
    val selectedAxis by viewModel.selectedAxis.collectAsStateWithLifecycle()
    val lanes by viewModel.lanes.collectAsStateWithLifecycle()
    val exclusions by viewModel.exclusions.collectAsStateWithLifecycle()
    val scopeEntries by viewModel.scopeEntries.collectAsStateWithLifecycle()
    var showScopeRefinement by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    if (showScopeRefinement) {
        ScopeRefinementDialog(
            entries = scopeEntries,
            onToggle = { entry, include -> viewModel.toggleScopeEntry(entry, include) },
            onReset = { viewModel.clearExclusions() },
            onDismiss = { showScopeRefinement = false }
        )
    }
    val selectedMetric by viewModel.selectedMetric.collectAsStateWithLifecycle()
    val selectedCategoryId by viewModel.selectedCategoryTabId.collectAsStateWithLifecycle()
    val isolatedPersonId by viewModel.isolatedPersonId.collectAsStateWithLifecycle()

    val themeColor = when (selectedMetric) {
        AnalysisViewModel.MetricType.LOW -> BpmLow
        AnalysisViewModel.MetricType.AVG -> BpmAvg
        AnalysisViewModel.MetricType.HIGH -> BpmHigh
    }

    var section by remember { mutableStateOf(AnalysisSection.SUMMARY) }

    // Sections with nothing in them are not offered. A Compare tab on a scope with one wearer and
    // no tags would open onto an empty card, which reads as a bug rather than as an absence.
    val sections = remember(uiState.availableCategories, uiState.people, uiState.records) {
        AnalysisSection.entries.filter { it.isAvailable(uiState) }
    }
    // Whatever was selected can stop existing — filing the last recording out of a group empties
    // People, and the screen would otherwise keep rendering a section that is no longer listed.
    if (section !in sections) section = sections.firstOrNull() ?: AnalysisSection.SUMMARY

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    title = {
                        // Two lines, because one was not enough to say what was being analysed.
                        // A scope title is a collection or event name — "Coachella Day 1" — and at
                        // headlineSmall on a 5-inch phone those clipped against the action icons
                        // with no ellipsis, so the header silently stopped naming its subject at
                        // exactly the widths where naming it mattered most.
                        //
                        // The kind line underneath is the guarantee: even when the name truncates,
                        // the header still says what this is and how much of it there is.
                        Column {
                            Text(
                                title ?: uiState.scope.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                uiState.scopeSubtitle(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back"
                                )
                            }
                        } else {
                            IconButton(onClick = onOpenDrawer) {
                                Icon(Icons.Default.Menu, contentDescription = "Open navigation menu")
                            }
                        }
                    },
                    actions = {
                        if (onExportImage != null && !uiState.isEmpty) {
                            IconButton(onClick = onExportImage) {
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = "Export as image"
                                )
                            }
                        }
                        // A stored analysis is already frozen; only a live one can be captured.
                        if (onSave != null && !uiState.isEmpty && !uiState.isFrozen) {
                            IconButton(onClick = { showSaveDialog = true }) {
                                Icon(Icons.Default.Save, contentDescription = "Save this analysis")
                            }
                        }
                    }
                )
                if (!uiState.isEmpty) {
                    MetricSelector(uiState, selectedMetric) { viewModel.setSelectedMetric(it) }
                    HorizontalDivider()
                }
            }
        }
    ) { paddingValues ->
        if (uiState.isEmpty) {
            EmptyAnalysis(uiState, Modifier.padding(paddingValues))
            return@Scaffold
        }

        Column(Modifier.padding(paddingValues).fillMaxSize()) {
            SectionBar(
                uiState = uiState,
                sections = sections,
                selected = section,
                onSelect = { section = it }
            )

            when (section) {
                AnalysisSection.SUMMARY -> SummarySection(
                    uiState = uiState,
                    tagging = viewModel.tagging,
                    axes = availableAxes,
                    selectedAxis = selectedAxis,
                    lanes = lanes,
                    onSelectAxis = { viewModel.setSplitAxis(it) },
                    onRefineScope = { showScopeRefinement = true },
                    isRefined = !exclusions.isEmpty
                )

                AnalysisSection.COMPARE -> CompareSection(
                    uiState = uiState,
                    selectedCategoryId = selectedCategoryId,
                    themeColor = themeColor,
                    isolatedPersonId = isolatedPersonId,
                    peopleByName = remember(uiState.people) {
                        uiState.people.associateBy { it.name }
                    },
                    onSelectCategory = { viewModel.setSelectedCategoryTab(it) },
                    onToggleReverse = { viewModel.toggleRankingsReverse() },
                    onOpenRanking = { ranking ->
                        // An event bar opens the event; anything else opens the recording that
                        // produced its number.
                        ranking.eventId?.let {
                            navController.navigate("${Routes.EVENT_DETAIL}/$it")
                        } ?: ranking.topRecordId?.let {
                            navController.navigate("${Routes.DETAIL}/$it")
                        }
                    }
                )

                AnalysisSection.PEOPLE -> PeopleSection(
                    people = uiState.people,
                    isolatedPersonId = isolatedPersonId,
                    onIsolate = { viewModel.isolatePerson(it) }
                )

                AnalysisSection.RECORDINGS -> RecordingsSection(
                    records = uiState.records,
                    metric = selectedMetric,
                    themeColor = themeColor,
                    isolatedPersonId = isolatedPersonId,
                    onToggleReverse = { viewModel.toggleRecordsReverse() },
                    onOpen = { navController.navigate("${Routes.DETAIL}/$it") }
                )
            }
        }
    }

    if (showSaveDialog && onSave != null) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save this analysis") },
            text = {
                Column {
                    OutlinedTextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        label = { Text("Name") },
                        placeholder = { Text(uiState.scope.title) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Saves these ${uiState.recordCount} recordings and their numbers as they " +
                            "are now. Editing or deleting a recording later will not change it.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = saveName.isNotBlank(),
                    onClick = {
                        onSave(saveName, uiState.records)
                        saveName = ""
                        showSaveDialog = false
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") } }
        )
    }
}

/**
 * The parts of an analysis, reachable one at a time.
 *
 * They used to be stacked into a single scroll, which meant a group of forty recordings buried its
 * per-person totals under a list nobody wanted to scroll past to reach them. Sections are cheap to
 * move between and each one is short enough to take in.
 */
private enum class AnalysisSection(val label: String) {
    SUMMARY("Summary"),
    COMPARE("Compare"),
    PEOPLE("People"),
    RECORDINGS("Recordings");

    fun isAvailable(state: AnalysisUiState): Boolean = when (this) {
        SUMMARY -> true
        COMPARE -> state.availableCategories.isNotEmpty()
        PEOPLE -> state.people.isNotEmpty()
        RECORDINGS -> state.records.isNotEmpty()
    }

    fun count(state: AnalysisUiState): Int? = when (this) {
        SUMMARY -> null
        COMPARE -> state.availableCategories.size
        PEOPLE -> state.people.size
        RECORDINGS -> state.records.size
    }
}

/**
 * Which section is showing.
 *
 * Carries counts so the bar says how much is behind each one — "Recordings 42" is the difference
 * between knowing what you are about to open and finding out.
 */
@Composable
private fun SectionBar(
    uiState: AnalysisUiState,
    sections: List<AnalysisSection>,
    selected: AnalysisSection,
    onSelect: (AnalysisSection) -> Unit
) {
    if (sections.size < 2) return

    SecondaryScrollableTabRow(
        selectedTabIndex = sections.indexOf(selected).coerceAtLeast(0),
        containerColor = Color.Transparent,
        edgePadding = 12.dp,
        divider = { HorizontalDivider() }
    ) {
        sections.forEach { entry ->
            Tab(
                selected = entry == selected,
                onClick = { onSelect(entry) },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(entry.label, maxLines = 1)
                        entry.count(uiState)?.let { count ->
                            Text(
                                "$count",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun SummarySection(
    uiState: AnalysisUiState,
    tagging: ScopeTagging?,
    axes: List<SplitAxis>,
    selectedAxis: SplitAxis?,
    lanes: List<SplitLane>,
    onSelectAxis: (String?) -> Unit,
    onRefineScope: () -> Unit,
    isRefined: Boolean
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { ScopeHeader(uiState) }

        // Above the totals, because choosing to compare changes what every figure below means.
        if (axes.isNotEmpty()) {
            item {
                SplitAxisPicker(
                    axes = axes,
                    selected = selectedAxis,
                    onSelect = onSelectAxis
                )
            }
        }
        if (lanes.isNotEmpty()) {
            item { SplitLanes(lanes) }
        }

        // Offered whether or not anything is excluded yet, so "what is actually in this" is
        // answerable without first having to change something.
        item {
            androidx.compose.material3.TextButton(onClick = onRefineScope) {
                Text(if (isRefined) "Some recordings excluded — review" else "What's included")
            }
        }

        // Where the whole scope's time went. The same split every person row and every ranking bar
        // shows, summed — one definition of "time in the peak band" for the entire screen.
        if (uiState.zoneTimes.any { it.durationMs > 0L }) {
            item {
                Column {
                    SectionTitle("Time in range", "Across everything in scope")
                    Spacer(Modifier.height(8.dp))
                    ZoneBreakdown(uiState.zoneTimes, showDurations = true)
                }
            }
        }
        // Only a group can carry tags. A filter describes a selection rather than an occasion, and
        // a saved analysis is frozen — offering the action there would have nowhere to write.
        tagging?.let { item { GroupTags(it) } }
        item { Highlights(uiState) }
    }
}

/**
 * Tags on the group, which reach every event in it and every recording in those.
 *
 * This is the level a festival name belongs at: applied once, true of everything underneath, and
 * correct again the moment an event is moved out — because nothing was written downward.
 */
@Composable
private fun GroupTags(tagging: ScopeTagging) {
    val tags by tagging.tags.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tags", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            IconButton(onClick = { showDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Tag this collection")
            }
        }
        if (tags.isEmpty()) {
            Text(
                "A tag here applies to everything inside this collection, however deeply it is nested.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tags.forEach { effective ->
                    EffectiveTagChip(
                        effective = effective,
                        onRemove = { scope.launch { tagging.removeTag(effective.tag.tagId) } }
                    )
                }
            }
        }
    }

    if (showDialog) {
        val categories by tagging.categories.collectAsStateWithLifecycle(initialValue = emptyList())
        TagSelectionDialog(
            onDismiss = { showDialog = false },
            onSave = { selected ->
                scope.launch { tagging.setTags(selected) }
                showDialog = false
            },
            categories = categories,
            getTagsByCategoryFlow = { tagging.tagsInCategory(it) },
            onCreateTag = { axis, name, onMade ->
                scope.launch { tagging.createTag(axis, name)?.let(onMade) }
            },
            initialSelectedTagIds = tags.map { it.tag.tagId }
        )
    }
}

/**
 * The two or three facts worth knowing before looking at anything else.
 *
 * Summary would otherwise be a header and nothing, which is not worth a tab of its own.
 */
@Composable
private fun Highlights(uiState: AnalysisUiState) {
    val hardest = uiState.people.maxByOrNull { it.maxBpm }
    val longest = uiState.people.maxByOrNull { it.activeDurationMs }
    val peak = uiState.records.maxByOrNull { it.maxBpm ?: 0.0 }

    if (hardest == null && peak == null) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Highlights", null)

        hardest?.let {
            HighlightRow(
                "Highest peak",
                it.name,
                "${it.maxBpm.toInt()} bpm",
                it.colorArgb?.let { argb -> Color(argb) }
            )
        }
        // Only worth saying when it is not the same person again — repeating one name twice
        // reads as a bug in the screen rather than as a fact about the weekend.
        longest?.takeIf { it.name != hardest?.name }?.let {
            HighlightRow(
                "Most time recorded",
                it.name,
                shortDuration(it.activeDurationMs),
                it.colorArgb?.let { argb -> Color(argb) }
            )
        }
        peak?.takeIf { it.eventName.isNotBlank() }?.let {
            HighlightRow("Peak came from", it.eventName, "${it.maxBpm?.toInt() ?: 0} bpm", null)
        }
    }
}

@Composable
private fun HighlightRow(label: String, subject: String, value: String, colour: Color?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            colour?.let {
                Box(Modifier.size(12.dp).clip(CircleShape).background(it))
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(subject, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CompareSection(
    uiState: AnalysisUiState,
    selectedCategoryId: Long?,
    themeColor: Color,
    isolatedPersonId: Long?,
    peopleByName: Map<String, PersonTotals>,
    onSelectCategory: (Long) -> Unit,
    onToggleReverse: () -> Unit,
    onOpenRanking: (TagRankingWithRecord) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            RankingsCard(
                uiState = uiState,
                selectedCategoryId = selectedCategoryId,
                themeColor = themeColor,
                isolatedPersonId = isolatedPersonId,
                peopleByName = peopleByName,
                onSelectCategory = onSelectCategory,
                onToggleReverse = onToggleReverse,
                onOpenRanking = onOpenRanking
            )
        }
    }
}

@Composable
private fun PeopleSection(
    people: List<PersonTotals>,
    isolatedPersonId: Long?,
    onIsolate: (Long) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionTitle("Per person", "Totals across everything in scope") }
        items(people, key = { "person-${it.name}" }) { person ->
            PersonTotalsRow(
                person = person,
                dimmed = isolatedPersonId != null && isolatedPersonId != person.personId,
                isolated = isolatedPersonId != null && isolatedPersonId == person.personId,
                // Only offered where there is an id. A saved analysis stores names, so there is
                // nothing stable to isolate on.
                onClick = person.personId?.let { { onIsolate(it) } }
            )
        }
    }
}

@Composable
private fun RecordingsSection(
    records: List<AnalysisRecord>,
    metric: AnalysisViewModel.MetricType,
    themeColor: Color,
    isolatedPersonId: Long?,
    onToggleReverse: () -> Unit,
    onOpen: (Long) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("${records.size} recordings", "By ${metricLabel(metric).lowercase()}")
                IconButton(onClick = onToggleReverse) {
                    Icon(Icons.Default.SwapVert, contentDescription = "Reverse order")
                }
            }
        }
        items(records, key = { "record-${it.recordId}" }) { record ->
            RecordRow(
                record = record,
                metric = metric,
                themeColor = themeColor,
                dimmed = isolatedPersonId != null && isolatedPersonId != record.personId,
                onClick = { onOpen(record.recordId) }
            )
        }
    }
}

/**
 * The three headline numbers, which double as the metric selector.
 *
 * Labelled now rather than left as three coloured hearts. The colours mean something once you know
 * them, and nothing at all the first time — and it is the selected one that decides how every
 * ranking below is sorted, which is too consequential to leave implied.
 */
@Composable
private fun MetricSelector(
    uiState: AnalysisUiState,
    selected: AnalysisViewModel.MetricType,
    onSelect: (AnalysisViewModel.MetricType) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom
    ) {
        MetricOption("Lowest", uiState.minTrio, BpmLow, selected == AnalysisViewModel.MetricType.LOW) {
            onSelect(AnalysisViewModel.MetricType.LOW)
        }
        MetricOption("Average", uiState.avgTrio, BpmAvg, selected == AnalysisViewModel.MetricType.AVG) {
            onSelect(AnalysisViewModel.MetricType.AVG)
        }
        MetricOption("Highest", uiState.maxTrio, BpmHigh, selected == AnalysisViewModel.MetricType.HIGH) {
            onSelect(AnalysisViewModel.MetricType.HIGH)
        }
    }
}

@Composable
private fun MetricOption(
    label: String,
    value: Int,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) color else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isSelected) FontWeight.Bold else null
        )
        Text(
            value.toString(),
            style = if (isSelected) {
                MaterialTheme.typography.headlineMedium
            } else {
                MaterialTheme.typography.headlineSmall
            },
            fontWeight = FontWeight.Bold,
            color = if (isSelected) color else color.copy(alpha = 0.45f)
        )
        Box(
            Modifier
                .padding(top = 4.dp)
                .height(3.dp)
                .width(if (isSelected) 40.dp else 0.dp)
                .background(color, MaterialTheme.shapes.extraSmall)
        )
    }
}

/**
 * What is being analysed, said once and properly.
 *
 * This replaced three lines that read "Categories: All / Tags: All / Date Range: All Time" whatever
 * was selected — three lines of vertical space that told you nothing about the thing you were
 * looking at, and no counts at all.
 */
@Composable
private fun ScopeHeader(uiState: AnalysisUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            uiState.scope.detail.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            uiState.dateRangeText.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                HeaderStat("Recordings", "${uiState.recordCount}", Modifier.weight(1f))
                if (uiState.people.isNotEmpty()) {
                    HeaderStat("People", "${uiState.people.size}", Modifier.weight(1f))
                }
                if (uiState.eventCount > 0) {
                    HeaderStat("Events", "${uiState.eventCount}", Modifier.weight(1f))
                }
                HeaderStat(
                    "Active",
                    shortDuration(uiState.totalActiveDurationMs),
                    Modifier.weight(1.2f)
                )
            }
        }
    }
}

@Composable
private fun HeaderStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RankingsCard(
    uiState: AnalysisUiState,
    selectedCategoryId: Long?,
    themeColor: Color,
    isolatedPersonId: Long?,
    peopleByName: Map<String, PersonTotals>,
    onSelectCategory: (Long) -> Unit,
    onToggleReverse: () -> Unit,
    onOpenRanking: (TagRankingWithRecord) -> Unit
) {
    val currentName = uiState.availableCategories
        .firstOrNull { it.categoryId == uiState.currentCategoryId }
        ?.name
        .orEmpty()

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTitle("Compare by $currentName", null)
            IconButton(onClick = onToggleReverse) {
                Icon(Icons.Default.SwapVert, contentDescription = "Reverse order")
            }
        }

        val selectedTabIndex = uiState.availableCategories
            .indexOfFirst { it.categoryId == selectedCategoryId }
            .coerceAtLeast(0)

        SecondaryScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = Color.Transparent,
            edgePadding = 0.dp,
            divider = {}
        ) {
            uiState.availableCategories.forEach { category ->
                Tab(
                    selected = uiState.currentCategoryId == category.categoryId,
                    onClick = { onSelectCategory(category.categoryId) },
                    text = { Text(category.name, maxLines = 1) }
                )
            }
        }

        // Bars run relative to the leader rather than to a fixed 200 bpm ceiling. Against 200,
        // a set of recordings all between 150 and 175 produced five bars of near-identical
        // length — technically honest, and useless for the one thing a ranking is for.
        val leader = uiState.categoricalRankings.maxOfOrNull { it.averageBpm } ?: 1.0

        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                uiState.categoricalRankings.forEach { ranking ->
                    val person = peopleByName[ranking.tagName]
                    val dimmed = isolatedPersonId != null &&
                        person != null &&
                        person.personId != isolatedPersonId

                    RankingBar(
                        label = ranking.tagName,
                        sublabel = if (ranking.recordCount > 0) {
                            "${ranking.recordCount} recording" +
                                if (ranking.recordCount == 1) "" else "s"
                        } else null,
                        progress = (ranking.averageBpm / leader).toFloat(),
                        value = ranking.averageBpm.toInt(),
                        // A wearer's own colour where the bar is a person, so the Wearer tab
                        // matches every other place they appear.
                        color = person?.colorArgb?.let { Color(it) } ?: themeColor,
                        dimmed = dimmed,
                        // Which artist kept people up there, rather than only who touched the
                        // highest number once — the question a peak alone cannot answer.
                        zoneTimes = ranking.zoneTimes,
                        onClick = { onOpenRanking(ranking) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RankingBar(
    label: String,
    sublabel: String?,
    progress: Float,
    value: Int,
    color: Color,
    dimmed: Boolean,
    zoneTimes: List<ZoneTime> = emptyList(),
    onClick: () -> Unit
) {
    val alpha = if (dimmed) 0.3f else 1f
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 1
            )
            sublabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(18.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(18.dp)
                        .background(color.copy(alpha = alpha))
                )
            }
            Text(
                value.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = color.copy(alpha = alpha)
            )
        }
        if (zoneTimes.any { it.durationMs > 0L }) {
            Spacer(Modifier.height(4.dp))
            ZoneBreakdown(zoneTimes, showDurations = false, alpha = alpha)
        }
    }
}


@Composable
private fun PersonTotalsRow(
    person: PersonTotals,
    dimmed: Boolean,
    isolated: Boolean,
    onClick: (() -> Unit)?
) {
    val alpha = if (dimmed) 0.35f else 1f
    val colour = person.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(
                if (isolated) Modifier.border(2.dp, colour, MaterialTheme.shapes.medium)
                else Modifier
            )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(colour.copy(alpha = alpha))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    person.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${person.recordCount} recording${if (person.recordCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                MiniStat("Min", "${person.minBpm.toInt()}", BpmLow, alpha, Modifier.weight(1f))
                MiniStat("Avg", "${person.avgBpm.toInt()}", BpmAvg, alpha, Modifier.weight(1f))
                MiniStat("Max", "${person.maxBpm.toInt()}", BpmHigh, alpha, Modifier.weight(1f))
                MiniStat(
                    "Active",
                    shortDuration(person.activeDurationMs),
                    MaterialTheme.colorScheme.onSurface,
                    alpha,
                    Modifier.weight(1.2f)
                )
            }
            // Who spent their evening up there, which a peak on its own cannot say.
            if (person.zoneTimes.any { it.durationMs > 0L }) {
                Spacer(Modifier.height(10.dp))
                ZoneBreakdown(person.zoneTimes, showDurations = true, alpha = alpha)
            }
        }
    }
}

@Composable
private fun MiniStat(
    label: String,
    value: String,
    color: Color,
    alpha: Float,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color.copy(alpha = alpha)
        )
    }
}

@Composable
private fun RecordRow(
    record: AnalysisRecord,
    metric: AnalysisViewModel.MetricType,
    themeColor: Color,
    dimmed: Boolean,
    onClick: () -> Unit
) {
    val alpha = if (dimmed) 0.35f else 1f
    val value = when (metric) {
        AnalysisViewModel.MetricType.LOW -> record.minBpm
        AnalysisViewModel.MetricType.AVG -> record.avgBpm
        AnalysisViewModel.MetricType.HIGH -> record.maxBpm
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    (record.personColorArgb?.let { Color(it) } ?: themeColor).copy(alpha = alpha)
                )
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                record.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 1
            )
            // Who and where, so a list of recordings is readable without opening any of them.
            val context = listOfNotNull(
                record.wearerName.takeIf { it.isNotBlank() },
                record.eventName.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (context.isNotEmpty()) {
                Text(
                    context,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    maxLines = 1
                )
            }
        }
        Text(
            value?.toInt()?.toString() ?: "—",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = themeColor.copy(alpha = alpha)
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String?) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Says what would fill the screen rather than only that it is empty.
 *
 * The three scopes are empty for different reasons, and "No data available for current filter" was
 * wrong for two of them.
 */
/**
 * What is being analysed, and how much of it — the line under the scope name.
 *
 * Deliberately says the *kind* rather than repeating the name: the name is directly above, and when
 * it truncates this is the only thing left saying whether "Coachella Day 1…" is a collection, an
 * event or a saved snapshot.
 */
private fun AnalysisUiState.scopeSubtitle(): String {
    val kind = when (scope) {
        is AnalysisScope.Group -> "Collection"
        is AnalysisScope.Saved -> "Saved analysis"
        else -> "Filtered"
    }
    if (isEmpty) return kind
    val recordings = if (recordCount == 1) "1 recording" else "$recordCount recordings"
    val people = people.size
    return if (people > 1) "$kind · $recordings · $people people" else "$kind · $recordings"
}

@Composable
private fun EmptyAnalysis(uiState: AnalysisUiState, modifier: Modifier = Modifier) {
    BpmEmptyState(
        modifier = modifier,
        icon = Icons.Default.Insights,
        title = when (uiState.scope) {
            is AnalysisScope.Group -> "Nothing in this collection yet"
            is AnalysisScope.Saved -> "This analysis has no recordings"
            else -> "Nothing matches this filter"
        },
        body = when (uiState.scope) {
            is AnalysisScope.Group ->
                "Add events to this collection, and file recordings into them, and their " +
                    "numbers will appear here."
            is AnalysisScope.Saved ->
                "It was saved without any, or its recordings have since been deleted."
            else ->
                "Narrow or widen the filter in the Library and analyse again."
        }
    )
}

private fun metricLabel(metric: AnalysisViewModel.MetricType) = when (metric) {
    AnalysisViewModel.MetricType.LOW -> "Lowest"
    AnalysisViewModel.MetricType.AVG -> "Average"
    AnalysisViewModel.MetricType.HIGH -> "Highest"
}
