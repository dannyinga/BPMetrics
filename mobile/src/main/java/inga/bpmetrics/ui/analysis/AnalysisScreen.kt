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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.OutlinedButton
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.filled.StackedBarChart
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
 * Three tabs: what the scope amounts to, its curves, and it split along whichever axes it supports.
 * Tapping a person in the Summary dims the others everywhere, the same interaction as the event
 * page.
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
    /**
     * Opens the export utility for whatever this screen is analysing, as the chosen kind.
     *
     * One button rather than one per kind. "Export" is a single intention and the format is the
     * first question the flow asks anyway — two icons in the bar made the bar answer it twice.
     */
    onExport: (() -> Unit)? = null,
    /**
     * The subject, drawn above the analysis.
     *
     * The half of a detail page that genuinely differs. A recording, an event and a collection are
     * not the same thing and their headers should not pretend otherwise — a recording has a person
     * and a watch, an event has a window and a place, a set has neither. Everything *below* this is
     * one component pointed at a different scope, which is the whole of Sprint 5.
     *
     * Null for a bare question, which has no subject beyond the question itself.
     */
    subjectHeader: (@Composable () -> Unit)? = null,
    /** Actions belonging to the subject rather than to the analysis, for the app bar. */
    subjectActions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    /** The subject's overflow, placed rightmost where an overflow belongs. */
    subjectOverflow: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    /**
     * An extra block in the Summary, contributed by the subject.
     *
     * For a recording this is where it stands among that person's others — "their third highest"
     * — which is a fact about the recording rather than about the scope, but far too tall to sit
     * in a header that has to stay out of the chart's way.
     *
     * Null for every other subject, which has nothing extra to say.
     */
    summaryExtra: (@Composable () -> Unit)? = null,
    /**
     * The subject's edit modal, handed the way in to "What's included".
     *
     * Rendered here rather than by the subject so that one composable still owns the refinement
     * sheet, and so the subject can offer it from inside its editor. Deciding what an analysis
     * covers is an edit to the question being asked, and it belongs with the other edits — it was
     * a text button wedged into the middle of the Summary, beside a section heading it had nothing
     * to do with.
     *
     * Null for a subject with no editor — a bare filter, a saved snapshot — which keeps the
     * Summary's own button, because otherwise the sheet would have no door at all.
     */
    subjectEditor: (@Composable (openRefineScope: () -> Unit) -> Unit)? = null,
    /**
     * Asks to split the stretch the chart is showing.
     *
     * A *request*, not the split itself: the chart says which stretch, and the subject opens its
     * one split dialog on it. The chart used to carry a second dialog of its own, which is how the
     * two ways of splitting came to word their refusals differently and bound their ranges
     * differently.
     *
     * Only a recording can be split, so this is null everywhere else. Times leave as wall-clock
     * instants — the chart works in those — and the subject rebases them.
     */
    onSplitFromTimeline: ((startMs: Long, endMs: Long) -> Unit)? = null
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val availableAxes by viewModel.availableAxes.collectAsStateWithLifecycle()
    val selectedAxis by viewModel.selectedAxis.collectAsStateWithLifecycle()
    val lanes by viewModel.lanes.collectAsStateWithLifecycle()
    val exclusions by viewModel.exclusions.collectAsStateWithLifecycle()
    val curves by viewModel.curves.collectAsStateWithLifecycle()
    val drawsChart by viewModel.chartDrawsItself.collectAsStateWithLifecycle()
    val compareMeasure by viewModel.compareMeasure.collectAsStateWithLifecycle()
    val isReversed by viewModel.isRecordsReversed.collectAsStateWithLifecycle()
    val peopleById by viewModel.peopleById.collectAsStateWithLifecycle()
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

    /**
     * Opens a recording, or null when there is nowhere to go.
     *
     * Null for the recording this page *is*. A recording is the narrowest scope there is, so its
     * own highest peak came from itself — and the Highlights link pointed at the page it was
     * already on, pushing a fresh copy onto the back stack every tap, for ever.
     */
    val openRecord: (Long) -> (() -> Unit)? = { id ->
        if (id == viewModel.selfRecordId) null
        else { { navController.navigate("${Routes.DETAIL}/$id") } }
    }

    var section by remember { mutableStateOf(AnalysisSection.SUMMARY) }

    // Sections with nothing in them are not offered. A Compare tab on a scope with one wearer and
    // no tags would open onto an empty card, which reads as a bug rather than as an absence.
    val sections = remember(uiState.records, availableAxes) {
        AnalysisSection.entries.filter { it.isAvailable(uiState, availableAxes) }
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
                        // Order is deliberate: the subject's own verb, then the one thing every
                        // page can do, then the overflow — which sits rightmost because that is
                        // where an overflow lives on every other Android screen.
                        subjectActions?.invoke(this)

                        if (onExport != null && !uiState.isEmpty) {
                            IconButton(onClick = onExport) {
                                Icon(
                                    Icons.Default.FileUpload,
                                    contentDescription = "Export"
                                )
                            }
                        }
                        // A stored analysis is already frozen; only a live one can be captured.
                        if (onSave != null && !uiState.isEmpty && !uiState.isFrozen) {
                            IconButton(onClick = { showSaveDialog = true }) {
                                Icon(Icons.Default.Save, contentDescription = "Save this analysis")
                            }
                        }

                        subjectOverflow?.invoke(this)
                    }
                )
                // The metric selector used to be pinned here, on every page, above everything. It
                // is a *sort* control — it decides whether a comparison is ranked by peak,
                // average or low — so it now sits with the comparisons it sorts. Its three
                // numbers survive as a line in the subject header, where they are information
                // rather than a control taking the top of the screen.
            }
        }
    ) { paddingValues ->
        if (uiState.isEmpty) {
            EmptyAnalysis(uiState, Modifier.padding(paddingValues))
            return@Scaffold
        }

        Column(Modifier.padding(paddingValues).fillMaxSize()) {
            // Above the section bar: the sections are ways of reading the analysis, and the
            // subject is what is being analysed. Switching from Summary to Compare must not
            // change which thing you are looking at, so the subject sits outside the switch.
            subjectHeader?.invoke()

            SectionBar(
                sections = sections,
                selected = section,
                onSelect = { section = it }
            )

            when (section) {
                AnalysisSection.SUMMARY -> SummarySection(
                    uiState = uiState,
                    subjectHeader = subjectHeader,
                    peopleById = peopleById,
                    isolatedPersonId = isolatedPersonId,
                    onIsolate = { viewModel.isolatePerson(it) },
                    onRefineScope = if (subjectEditor != null) null else {
                        { showScopeRefinement = true }
                    },
                    isRefined = !exclusions.isEmpty,
                    extra = summaryExtra,
                    onOpenRecord = openRecord
                )

                AnalysisSection.TIMELINE -> TimelineSection(
                    uiState = uiState,
                    curves = curves,
                    drawsChart = drawsChart,
                    onDrawChart = { viewModel.requestChart() },
                    peopleById = peopleById,
                    onSplit = onSplitFromTimeline
                )

                AnalysisSection.COMPARE -> CompareSection(
                    axes = availableAxes,
                    selectedAxis = selectedAxis,
                    onSelectAxis = { viewModel.setSplitAxis(it) },
                    lanes = lanes,
                    measure = compareMeasure,
                    onSelectMeasure = { viewModel.setCompareMeasure(it) },
                    metric = selectedMetric,
                    onSelectMetric = { viewModel.setSelectedMetric(it) },
                    isReversed = isReversed,
                    onToggleReverse = { viewModel.toggleRecordsReverse() },
                    onOpenRecord = openRecord
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

    subjectEditor?.invoke { showScopeRefinement = true }
}

/**
 * The parts of an analysis, reachable one at a time.
 *
 * They used to be stacked into a single scroll, which meant a group of forty recordings buried its
 * per-person totals under a list nobody wanted to scroll past to reach them. Sections are cheap to
 * move between and each one is short enough to take in.
 */
/**
 * The three ways of reading one scope.
 *
 * **People stopped being one of them.** Per-person totals are a summary of who was there, not a
 * separate question, and a tab holding one list beside a Summary that held everything else was
 * splitting one answer across two places.
 *
 * **The curves gained one.** They were the top of the Summary, which pushed the numbers below the
 * fold and made every page pay for a chart before saying anything.
 *
 * **Compare sits second.** It answers the question people arrive with — which of these was the
 * most — where the Timeline answers "what did it look like", which is a thing you go and look at
 * rather than a thing you ask.
 *
 * **Recordings stopped being one too.** It was a list of recordings ranked by low, average or peak,
 * which is a comparison — the same question Compare answers, asked through a second control and
 * drawn a second way. It is now an axis, [SplitAxis.Recording], and Compare took its look.
 */
private enum class AnalysisSection(val label: String) {
    SUMMARY("Summary"),
    COMPARE("Compare"),
    TIMELINE("Timeline");

    /**
     * @param axes What the scope can be compared along, which is the only honest test for whether
     *   Compare has anything to show — it used to be asked of the tag categories alone, so a
     *   festival with no tags hid a tab that would have compared its nights.
     */
    fun isAvailable(state: AnalysisUiState, axes: List<SplitAxis>): Boolean = when (this) {
        SUMMARY -> true
        COMPARE -> axes.isNotEmpty()
        // Offered whenever there is anything to draw, including the offer to draw it.
        TIMELINE -> state.records.isNotEmpty()
    }

}

/**
 * Which section is showing.
 *
 * No counts any more. They were there for "Recordings 42", which was worth knowing before opening
 * the tab; the three that remain are ways of reading one scope rather than lists of things, and a
 * number beside "Compare" would only be counting the chips on the tab below it.
 */
@Composable
private fun SectionBar(
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
                    }
                }
            )
        }
    }
}

/**
 * What this scope amounts to: the numbers, and who was there.
 *
 * People used to be a tab. Per-person totals are a summary of who was in a scope, not a separate
 * question about it, so having them next door meant one answer split across two places — and the
 * Summary that remained opened on a chart with the figures below the fold.
 */
@Composable
private fun SummarySection(
    uiState: AnalysisUiState,
    /** Null when there is no subject — a bare question draws the scope card instead. */
    subjectHeader: (@Composable () -> Unit)? = null,
    peopleById: Map<Long, inga.bpmetrics.library.PersonEntity>,
    isolatedPersonId: Long?,
    onIsolate: (Long) -> Unit,
    /** Null where the subject offers the sheet from its own editor instead. */
    onRefineScope: (() -> Unit)?,
    isRefined: Boolean,
    /** Whatever the subject wants to add here. See [AnalysisScreen]. */
    extra: (@Composable () -> Unit)? = null,
    /** Given a record, the way to open it — or null where that is the page we are on. */
    onOpenRecord: (Long) -> (() -> Unit)?
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Suppressed when the subject drew its own header: a detail page already says what it is
        // of, over its cover, and repeating the name and the counts underneath was the same thing
        // twice.
        if (subjectHeader == null) item { ScopeHeader(uiState) }

        // Ordered as the questions are asked: what stood out, who was there, and only then how the
        // time was spent. The bands used to open the page under a heading carrying the duration —
        // a figure that belongs in the header with the other figures — and the standout moments
        // sat below a chart of coloured bars nobody had asked for yet.
        item { Highlights(uiState, onOpenRecord) }

        extra?.let { block -> item { block() } }

        if (uiState.people.isNotEmpty()) {
            item {
                SectionTitle(
                    "Who was there",
                    "${uiState.people.size} " +
                        if (uiState.people.size == 1) "person" else "people"
                )
            }
            items(uiState.people, key = { "person-${it.name}" }) { person ->
                PersonTotalsRow(
                    person = person,
                    profile = person.personId?.let { peopleById[it] },
                    dimmed = isolatedPersonId != null && isolatedPersonId != person.personId,
                    isolated = isolatedPersonId != null && isolatedPersonId == person.personId,
                    // Only offered where there is an id. A saved analysis stores names, so there
                    // is nothing stable to isolate on.
                    onClick = person.personId?.let { { onIsolate(it) } }
                )
            }
        }

        // Last. It is the most detailed thing on the page and the least likely to be what someone
        // came for — and its heading no longer carries the duration, which moved up into the
        // subject header where the low, average and high already were.
        item { SectionTitle("Where the time went", null) }
        item { ZoneBreakdown(uiState.zoneTimes, showDurations = true) }

        // Only for a scope whose subject has no editor to hold it. See [AnalysisScreen].
        onRefineScope?.let { refine ->
            item {
                TextButton(onClick = refine) {
                    Text(if (isRefined) "Scope — refined" else "Refine scope")
                }
            }
        }
    }
}

/**
 * The curves, on their own.
 *
 * They were the top of the Summary, which meant every page opened on a chart and put its figures
 * below the fold — and, for a scope big enough, opened on a wait. A tab is the honest place for
 * something that is one of several ways of reading a scope rather than the first thing about it.
 */
@Composable
private fun TimelineSection(
    uiState: AnalysisUiState,
    curves: ConcurrentAnalysis,
    drawsChart: Boolean,
    onDrawChart: () -> Unit,
    peopleById: Map<Long, inga.bpmetrics.library.PersonEntity>,
    /** Cuts a new recording out of whatever the chart is showing. Null unless this is one. */
    onSplit: ((startMs: Long, endMs: Long) -> Unit)? = null
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            !curves.isEmpty -> item {
                ScopeCurves(curves, peopleById, onSplit)
            }
            // Too much to draw unasked. Offered rather than omitted: a missing chart with no
            // explanation reads as a page that failed, and the reason — how much there is — is the
            // same reason someone might still want it.
            !drawsChart -> item {
                OfferChart(uiState.totalActiveDurationMs, uiState.recordCount, onDrawChart)
            }
            else -> item {
                Text(
                    "Nothing measured in this scope.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * One scope, split and ranked.
 *
 * Two controls, because there are two questions: **what to split by** — people, events, event
 * types, venues, or the values on a tag axis — and **what to compare** once split.
 *
 * Splitting used to live under the chart in the Summary, and comparing was a separate tab ranking
 * tags with the value and a band breakdown on every row at once. That meant one row said two things
 * and neither clearly, and the axis you were splitting by was three screens from the ranking it
 * produced.
 */
@Composable
private fun CompareSection(
    axes: List<SplitAxis>,
    selectedAxis: SplitAxis?,
    onSelectAxis: (String?) -> Unit,
    lanes: List<SplitLane>,
    measure: CompareMeasure,
    onSelectMeasure: (CompareMeasure) -> Unit,
    metric: AnalysisViewModel.MetricType,
    onSelectMetric: (AnalysisViewModel.MetricType) -> Unit,
    isReversed: Boolean,
    onToggleReverse: () -> Unit,
    onOpenRecord: (Long) -> (() -> Unit)?
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // What is being measured comes first, because it decides what the axes below will even
        // show — picking "Person" means nothing until you have said whether you are asking about
        // rate or about time in a band.
        item {
            CompareControls(
                measure = measure,
                onSelectMeasure = onSelectMeasure,
                metric = metric,
                onSelectMetric = onSelectMetric,
                isReversed = isReversed,
                onToggleReverse = onToggleReverse
            )
        }

        item {
            SplitAxisPicker(
                axes = axes,
                selected = selectedAxis,
                onSelect = onSelectAxis
            )
        }

        if (selectedAxis != null && lanes.isNotEmpty()) {
            item {
                SplitLanes(
                    lanes = lanes,
                    measure = measure,
                    metric = metric,
                    onOpenRecord = onOpenRecord
                )
            }
        }
    }
}

/**
 * Rate or bands, and which rate.
 *
 * Three rows of identically-shaped chips — measure, metric, axis — was the problem: nothing in the
 * shape said which row was the question and which was the answer, so the whole tab read as one
 * undifferentiated grid of things to tap. This is now a segmented control over three coloured
 * dials, and only the axes below stay as chips.
 *
 * The dials are graphical because they are three points on one scale rather than three unrelated
 * options, and because they are the colours the rest of the app already uses for low, average and
 * peak. The word for whichever is chosen sits beside them, so the icons never have to carry the
 * meaning alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompareControls(
    measure: CompareMeasure,
    onSelectMeasure: (CompareMeasure) -> Unit,
    metric: AnalysisViewModel.MetricType,
    onSelectMetric: (AnalysisViewModel.MetricType) -> Unit,
    isReversed: Boolean,
    onToggleReverse: () -> Unit
) {
    Column {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            CompareMeasure.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = measure == option,
                    onClick = { onSelectMeasure(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, CompareMeasure.entries.size),
                    icon = {},
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when (option) {
                                    CompareMeasure.RATE -> Icons.Default.MonitorHeart
                                    CompareMeasure.ZONES -> Icons.Default.StackedBarChart
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(option.label)
                        }
                    }
                )
            }
        }

        // Only when it decides anything. Beside "Zones" it would be a control over nothing.
        if (measure == CompareMeasure.RATE) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnalysisViewModel.MetricType.entries.forEach { type ->
                    MetricDial(
                        type = type,
                        selected = metric == type,
                        onClick = { onSelectMetric(type) }
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    metricLabelFor(metric),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = toneFor(metric),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onToggleReverse) {
                    Icon(
                        Icons.Default.SwapVert,
                        contentDescription = if (isReversed) "Highest first" else "Lowest first",
                        tint = if (isReversed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

/**
 * One of low, average, peak — as a coloured dial rather than a word in a box.
 *
 * The arrow says which end of the scale and the colour says which figure, both of them the ones
 * used everywhere else in the app. Filled when chosen, so the row reads as a single setting with
 * one position rather than as three separate switches.
 */
@Composable
private fun MetricDial(
    type: AnalysisViewModel.MetricType,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tone = toneFor(type)
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (selected) tone else tone.copy(alpha = 0.14f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            when (type) {
                AnalysisViewModel.MetricType.LOW -> Icons.AutoMirrored.Filled.TrendingDown
                AnalysisViewModel.MetricType.AVG -> Icons.AutoMirrored.Filled.TrendingFlat
                AnalysisViewModel.MetricType.HIGH -> Icons.AutoMirrored.Filled.TrendingUp
            },
            contentDescription = metricLabelFor(type),
            tint = if (selected) MaterialTheme.colorScheme.surface else tone,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** The one colour for each figure, shared by the dial, the number and the bar under it. */
private fun toneFor(metric: AnalysisViewModel.MetricType) = when (metric) {
    AnalysisViewModel.MetricType.LOW -> BpmLow
    AnalysisViewModel.MetricType.AVG -> BpmAvg
    AnalysisViewModel.MetricType.HIGH -> BpmHigh
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
private fun PersonTotalsRow(
    person: PersonTotals,
    /**
     * Their profile, for the photograph.
     *
     * Null on a frozen selection, which stores names rather than ids and so has nobody to look
     * up — [PersonAvatar] falls back to the initial on their colour, which is what it does
     * everywhere else somebody has no picture.
     */
    profile: inga.bpmetrics.library.PersonEntity?,
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
                // Their face, ringed in their colour, rather than a dot of it. The colour is how
                // the app identifies them everywhere — on a chart lane, on a tile, in an export —
                // so keeping the ring keeps the row tied to those, while the photograph does what
                // a twelve-pixel dot never could and says *who*.
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .border(2.dp, colour.copy(alpha = alpha), CircleShape)
                        .padding(2.dp)
                ) {
                    inga.bpmetrics.ui.components.PersonAvatar(profile, size = 28.dp)
                }
                Spacer(Modifier.width(10.dp))
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

/**
 * The curves for whatever is in scope.
 *
 * The same chart the event page and the recording page each had their own copy of. One recording
 * draws one lane, a day draws one per person, a festival draws the same — because they are all a
 * scope, and a scope is a set of recordings on a shared clock.
 *
 * Scrubbing and isolating are local to the chart: they are ways of *looking*, not facts about the
 * analysis, so they do not belong in the ViewModel with the things that are.
 */
@Composable
private fun ScopeCurves(
    analysis: ConcurrentAnalysis,
    peopleById: Map<Long, inga.bpmetrics.library.PersonEntity> = emptyMap(),
    onSplit: ((startMs: Long, endMs: Long) -> Unit)? = null
) {
    var scrubbedMs by remember(analysis) { mutableStateOf<Long?>(null) }
    var isolatedId by remember(analysis) { mutableStateOf<String?>(null) }
    val window = rememberConcurrentViewWindow(analysis)

    Column {
        SectionTitle(
            "Over time",
            if (analysis.series.size == 1) "The recording, end to end"
            else "${analysis.series.size} lanes on one clock"
        )
        Spacer(Modifier.height(8.dp))

        // Who is on the chart, as faces. Tap-to-isolate was already in the chart itself — on the
        // curve, which on a busy plot means hitting a two-pixel line among six others. A face is a
        // 40dp target that says whose curve it is before you tap it, which the curve cannot.
        if (analysis.series.size > 1) {
            SeriesStrip(
                series = analysis.series,
                peopleById = peopleById,
                isolatedId = isolatedId,
                onIsolate = { isolatedId = if (isolatedId == it) null else it }
            )
            Spacer(Modifier.height(8.dp))
        }

        ConcurrentChart(
            analysis = analysis,
            window = window,
            scrubbedMs = scrubbedMs,
            onScrub = { scrubbedMs = it },
            isolatedId = isolatedId,
            onIsolate = { isolatedId = it },
            modifier = Modifier.fillMaxWidth().height(220.dp)
        )

        // What the chart is showing, and the ways of changing it. Pinch and drag worked before and
        // still do; these are the same thing for anyone who does not know that, plus the one
        // control a gesture cannot give you — a way back to the whole thing.
        Spacer(Modifier.height(6.dp))
        ViewWindowControls(window, analysis.clock, onSplit)

        // The moments everyone spiked at once. Only meaningful with more than one lane, which is
        // why it lived on the same-time screen — but that screen was this chart with a heading,
        // and a scope of overlapping recordings is just a scope.
        if (analysis.peaks.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionTitle(
                "Moments you reacted together",
                "Ranked by how far each person was into their own range, so one being fitter " +
                    "than another does not decide it."
            )
            Spacer(Modifier.height(4.dp))
            analysis.peaks.forEach { moment ->
                MomentRow(
                    clock = analysis.clock,
                    moment = moment,
                    isSelected = scrubbedMs == moment.wallClockMs,
                    onClick = { scrubbedMs = moment.wallClockMs }
                )
            }
        }
    }
}

/**
 * Who is on the chart, as faces, and which one is being singled out.
 *
 * Isolating a curve existed already — by tapping the curve. On a plot with six lanes that is a
 * two-pixel target among five others, and it asks you to know whose line is whose *before* you can
 * ask which one is whose. A face with a ring in their own colour answers that standing still.
 *
 * A ring rather than a dot, because the colour is the thing that ties the face to the line and a
 * dot beside a photograph puts them in competition. Someone with no photograph still appears — the
 * avatar falls back to their initial on their colour — so a group is never partly missing.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SeriesStrip(
    series: List<ConcurrentSeries>,
    peopleById: Map<Long, inga.bpmetrics.library.PersonEntity>,
    isolatedId: String?,
    onIsolate: (String) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        series.forEach { lane ->
            val singled = isolatedId == lane.id
            // Dimmed only when something *else* is singled out. With nothing isolated every lane
            // is on the chart, so every face should look like it.
            val alpha = if (isolatedId == null || singled) 1f else 0.4f
            val tone = Color(lane.colorArgb)
            val person = peopleById.values.firstOrNull { it.displayName == lane.label }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onIsolate(lane.id) }
                    .padding(horizontal = 2.dp)
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(tone.copy(alpha = alpha))
                        .padding(if (singled) 3.dp else 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (person != null) {
                        inga.bpmetrics.ui.components.PersonAvatar(
                            person = person,
                            size = if (singled) 36.dp else 38.dp
                        )
                    } else {
                        Box(
                            Modifier
                                .size(if (singled) 36.dp else 38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                lane.label.take(1).uppercase().ifBlank { "?" },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    lane.label.takeIf { it.isNotBlank() } ?: "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(56.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

/**
 * What the chart is showing, and how to change it.
 *
 * Pinch and drag already worked. They are also invisible: nothing on a chart says it can be zoomed,
 * and there is no gesture at all for "put it back", so a chart someone had pinched into stayed that
 * way until they left the page. These are the same operations with a handle on them.
 *
 * The span is stated rather than implied. "6m 20s of 2h 14m" is the one fact that makes the rest of
 * the controls legible, and it is what turns the window into a *selection* — which is what Split
 * then acts on.
 */
@Composable
private fun ViewWindowControls(
    window: ConcurrentViewWindow,
    clock: java.time.ZoneId,
    onSplit: ((startMs: Long, endMs: Long) -> Unit)?
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    append(shortDuration(window.spanMs))
                    if (window.isZoomed) {
                        append("  ·  ")
                        append(getTimeString(window.startMs, clock))
                        append(" – ")
                        append(getTimeString(window.endMs, clock))
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = { window.zoomBy(0.5f, 0.5f, 1f) }) {
                Icon(Icons.Default.Remove, contentDescription = "Show more time")
            }
            IconButton(onClick = { window.zoomBy(2f, 0.5f, 1f) }) {
                Icon(Icons.Default.Add, contentDescription = "Show less time")
            }
            // Only once something is hidden. A reset beside an unzoomed chart does nothing.
            if (window.isZoomed) {
                IconButton(onClick = { window.reset() }) {
                    Icon(Icons.Default.ZoomOutMap, contentDescription = "Show the whole thing")
                }
            }
        }

        // The scrollbar *is* the pan control, and it also draws where you are in the whole — which
        // a chart zoomed into ten minutes of a festival otherwise cannot say at all.
        if (window.visibleFraction < 0.999f) {
            androidx.compose.material3.Slider(
                value = window.scrollFraction,
                onValueChange = { window.scrollTo(it) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        onSplit?.let { requestSplit ->
            OutlinedButton(
                // Hands the window over and stops there. This used to open a split dialog of its
                // own — a second one, which knew the window but not the recording it was a window
                // *onto*, so it could not bound the range or count what was in it. The window is
                // the coarse selection; the one dialog is where it gets said precisely.
                onClick = { requestSplit(window.startMs, window.endMs) },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.CallSplit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Split this stretch")
            }
        }
    }
}

/**
 * The chart, offered rather than drawn.
 *
 * For a scope big enough that fetching and merging every reading in it is a visible wait — a
 * festival is tens of hours across several people, which is hundreds of thousands of readings.
 * Drawing that unasked made opening the page slow for everyone, including the people who came to
 * read the numbers.
 *
 * Says how much there is, because that is both the reason it is not drawn and the reason someone
 * might still want it.
 */
@Composable
private fun OfferChart(activeMs: Long, recordCount: Int, onDraw: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            SectionTitle(
                "Over time",
                "$recordCount recording${if (recordCount == 1) "" else "s"}, " +
                    "${shortDuration(activeMs)} of readings"
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDraw) { Text("Draw the chart") }
        }
    }
}

/**
 * One moment everyone spiked at.
 *
 * Lifted from the same-time screen when that screen turned out to be this chart with a heading.
 * Tapping puts the scrub line on it, which is the point: the number is only interesting next to the
 * curves that produced it.
 */
@Composable
private fun MomentRow(
    moment: GroupMoment,
    isSelected: Boolean,
    clock: java.time.ZoneId,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    getTimeString(moment.wallClockMs, clock),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${moment.participants} wearing watches",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "${moment.intensityPercent}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}


/**
 * The two or three facts worth knowing before looking at anything else.
 *
 * Summary would otherwise be a header and nothing, which is not worth a tab of its own.
 */
@Composable
private fun Highlights(uiState: AnalysisUiState, onOpenRecord: (Long) -> (() -> Unit)?) {
    val hardest = uiState.people.maxByOrNull { it.maxBpm }
    val longest = uiState.people.maxByOrNull { it.activeDurationMs }
    val peak = uiState.records.maxByOrNull { it.maxBpm ?: 0.0 }

    if (hardest == null && peak == null) return

    // The recording each claim came out of. A highlight is a statement about one moment, and the
    // moment is in a recording — saying "Kyle hit 186" and giving no way to go and look at it made
    // the section a dead end. Matched on the person where there is one and on the frozen name
    // otherwise, so a saved snapshot still points somewhere.
    val hardestRecord = hardest?.let { person ->
        uiState.records
            .filter {
                if (person.personId != null) it.personId == person.personId
                else it.wearerName == person.name
            }
            .maxByOrNull { it.maxBpm ?: 0.0 }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Highlights", null)

        hardest?.let {
            HighlightRow(
                "Highest peak",
                it.name,
                "${it.maxBpm.toInt()} bpm",
                it.colorArgb?.let { argb -> Color(argb) },
                onOpen = hardestRecord?.let { r -> onOpenRecord(r.recordId) }
            )
        }
        // Only worth saying when it is not the same person again — repeating one name twice
        // reads as a bug in the screen rather than as a fact about the weekend.
        //
        // Not a link: a total across eleven recordings is not any one of them, and picking the
        // longest to open would be answering a question nobody asked.
        longest?.takeIf { it.name != hardest?.name }?.let {
            HighlightRow(
                "Most time recorded",
                it.name,
                shortDuration(it.activeDurationMs),
                it.colorArgb?.let { argb -> Color(argb) },
                onOpen = null
            )
        }
        peak?.takeIf { it.eventName.isNotBlank() }?.let {
            HighlightRow(
                "Peak came from",
                it.eventName,
                "${it.maxBpm?.toInt() ?: 0} bpm",
                null,
                onOpen = onOpenRecord(it.recordId)
            )
        }
    }
}

@Composable
private fun HighlightRow(
    label: String,
    subject: String,
    value: String,
    colour: Color?,
    /** Null where the claim is a total rather than one recording. See [Highlights]. */
    onOpen: (() -> Unit)?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onOpen != null) it.clickable(onClick = onOpen) else it }
    ) {
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
            // Only where there is somewhere to go. A chevron on every row would promise a
            // destination the totals row does not have.
            if (onOpen != null) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
