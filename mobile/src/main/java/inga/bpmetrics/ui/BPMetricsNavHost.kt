package inga.bpmetrics.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import inga.bpmetrics.BPMetricsApp
import inga.bpmetrics.datasync.IncomingRecordManager
import inga.bpmetrics.datasync.isActive
import inga.bpmetrics.export.RenderQueueManager
import inga.bpmetrics.export.RenderStatus
import inga.bpmetrics.export.BpmExportService
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.LoadedAnalysis
import inga.bpmetrics.ui.about.AboutScreen
import inga.bpmetrics.ui.analysis.AnalysisScreen
import inga.bpmetrics.ui.analysis.AnalysisViewModel
import inga.bpmetrics.ui.analysis.ConcurrentAnalysis
import inga.bpmetrics.ui.analysis.ConcurrentAnalysisScreen
import inga.bpmetrics.ui.analysis.EventAnalysisScreen
import inga.bpmetrics.ui.analysis.EventDetailViewModel
import inga.bpmetrics.ui.analysis.SavedAnalysesScreen
import inga.bpmetrics.ui.record.BpmRecordScreen
import inga.bpmetrics.ui.record.BpmRecordViewModel
import inga.bpmetrics.ui.library.LibraryScreen
import inga.bpmetrics.ui.library.LibraryViewMode
import inga.bpmetrics.ui.library.LibraryViewModel
import inga.bpmetrics.ui.navigation.AppDestination
import inga.bpmetrics.ui.navigation.AppDrawerContent
import inga.bpmetrics.ui.settings.SettingsScreen
import inga.bpmetrics.ui.settings.SettingsViewModel
import inga.bpmetrics.ui.tags.TagManagementScreen
import inga.bpmetrics.ui.tags.TagManagementViewModel
import inga.bpmetrics.ui.export.ExportKind
import inga.bpmetrics.ui.export.ExportSource
import inga.bpmetrics.ui.export.ExportStep
import inga.bpmetrics.ui.export.ExportUtilityScreen
import inga.bpmetrics.ui.export.ExportUtilityViewModel
import inga.bpmetrics.ui.people.PeopleScreen
import inga.bpmetrics.ui.watches.WatchesScreen
import kotlinx.coroutines.launch

/**
 * The main navigation host for the mobile application.
 *
 * Top-level sections live behind a navigation drawer and behave as siblings; detail screens are
 * pushed on top of whichever section opened them and keep ordinary back-arrow navigation.
 */
@Composable
fun BPMetricsNavHost(repository: LibraryRepository) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val app = context.applicationContext as BPMetricsApp
    val settingsRepository = app.settingsRepository

    val libraryViewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModel.Factory(repository)
    )

    // Hoisted above the NavHost so an entry point can prime it before navigating — a tile's
    // "Export video" already knows its source, and making it walk through step 1 to say something
    // it has just said would be a regression on a flow that is currently two taps.
    val exportViewModel: ExportUtilityViewModel = viewModel(
        factory = ExportUtilityViewModel.Factory(repository, settingsRepository)
    )

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val currentDestination = AppDestination.fromRoute(currentRoute)

    val selectedRecordIds by libraryViewModel.selectedRecordIds.collectAsState()
    val queue by RenderQueueManager.queue.collectAsState(initial = emptyList())
    val activeRenderCount = queue.count {
        it.status == RenderStatus.RENDERING || it.status == RenderStatus.QUEUED
    }

    val incoming by IncomingRecordManager.incoming.collectAsState()
    val incomingCount = incoming.count { it.status.isActive }

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    // What a new analysis covers. Held here rather than in the Library's own filter so the two
    // are independent: analysing a subset should not re-filter the library underneath the user.
    var analysisFilter by remember { mutableStateOf(LibraryViewModel.FilterState()) }

    // Same-time analysis is chosen by hand rather than filtered. A filter describes a kind of
    // recording; comparing people at one moment means naming the exact recordings that overlap,
    // which no filter expresses.
    var concurrentRecordIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var awaitingConcurrentSelection by remember { mutableStateOf(false) }

    /**
     * Opens the export utility already knowing what it is exporting.
     *
     * Lands on **Contents**, not Look. An entry point answers step 1 — *what is this of* — and
     * nothing more; which clips to draw on, or which timelines become images, is a separate
     * question that has not been asked yet. Skipping to step 3 meant the flow silently accepted
     * defaults for a step the user never saw, and then previewed the result as though they had
     * chosen it.
     *
     * The label is passed through because a saved analysis exported from its own screen arrives as
     * a bare set of recordings, and would otherwise lose the name that was the point of saving it.
     */
    val openExportAs: (List<inga.bpmetrics.library.BpmRecord>, String?, ExportKind) -> Unit =
        { recs, label, kind ->
            if (recs.isNotEmpty()) {
                exportViewModel.startAt(
                    source = ExportSource.Recordings(recs.map { it.metadata.recordId }.toSet()),
                    step = ExportStep.CONTENTS,
                    label = label,
                    kind = kind
                )
                navController.navigateToSection(AppDestination.EXPORT)
            }
        }

    val openExport: (List<inga.bpmetrics.library.BpmRecord>, String?) -> Unit = { recs, label ->
        openExportAs(recs, label, ExportKind.VIDEO)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Swipeable closed, never swipeable open. An edge swipe is a gesture people make by
        // accident — against a horizontally scrolling row, or reaching for system back — and the
        // drawer now holds five screens nobody visits mid-task. The menu button opens it.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            AppDrawerContent(
                currentRoute = currentRoute,
                activeRenderCount = activeRenderCount,
                incomingCount = incomingCount,
                onNavigate = { destination ->
                    scope.launch { drawerState.close() }
                    // Leaving for anywhere but the Library abandons a pending pick, so the prompt
                    // does not linger over an unrelated visit later.
                    if (destination != AppDestination.LIBRARY) awaitingConcurrentSelection = false
                    navController.navigateToSection(destination)
                }
            )
        }
    ) {
        // Back closes the drawer rather than leaving the current section.
        BackHandler(enabled = drawerState.isOpen) {
            scope.launch { drawerState.close() }
        }

        // Back from any section other than the start destination returns to the Library rather
        // than exiting the app.
        BackHandler(
            enabled = !drawerState.isOpen &&
                currentDestination != null &&
                currentDestination != AppDestination.LIBRARY
        ) {
            navController.navigateToSection(AppDestination.LIBRARY)
        }

        // The bar wraps the NavHost rather than living inside each screen, so it does not
        // re-enter on every navigation — and is absent on detail screens, which are pushed *on
        // top* of a section and should not offer a sideways move out of it.
        androidx.compose.material3.Scaffold(
            bottomBar = {
                // On every top-level section, not only the four in it. A management screen is a
                // sibling of the others, not something pushed on top of one, so removing the
                // way back would strand it. No item is selected there, which is honest.
                if (currentDestination != null) {
                    inga.bpmetrics.ui.navigation.AppNavigationBar(
                        currentRoute = currentRoute,
                        activeRenderCount = activeRenderCount,
                        onNavigate = { navController.navigateToSection(it) }
                    )
                }
            }
        ) { barPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.LIBRARY,
            // Consumed as well as applied. Every screen still has its own Scaffold, and an inset-aware
            // component nested inside another applies the system bars a second time unless it is
            // told they have been handled — which showed up as a dead strip under every list.
            modifier = androidx.compose.ui.Modifier
                .padding(barPadding)
                .consumeWindowInsets(barPadding)
        ) {
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    navController = navController,
                    viewModel = libraryViewModel,
                    onOpenDrawer = openDrawer,
                    awaitingConcurrentSelection = awaitingConcurrentSelection,
                    onAnalyseTogether = { ids ->
                        concurrentRecordIds = ids
                        awaitingConcurrentSelection = false
                        libraryViewModel.clearSelection()
                        navController.navigate(Routes.ANALYSIS_CONCURRENT)
                    },
                    onExportSelection = { recs, exportKind ->
                        libraryViewModel.clearSelection()
                        openExportAs(recs, null, exportKind)
                    }
                )
            }

            composable(Routes.TAG_MANAGEMENT) {
                val viewModel: TagManagementViewModel = viewModel(
                    factory = TagManagementViewModel.Factory(repository)
                )
                TagManagementScreen(navController, viewModel, onOpenDrawer = openDrawer)
            }

            // The drawer lands on the shelf of stored analyses rather than a live one, because a
            // saved analysis is the thing worth coming back to.
            composable(Routes.ANALYSIS) {
                // Room hands back a new Flow per call, and collection is keyed on the instance --
                // rebuilding it each recomposition would restart the query and blink the empty
                // state on the way back.
                val savedAnalyses = remember { repository.getSavedAnalyses() }
                val availablePeople by libraryViewModel.availablePeople.collectAsState()
                val availableWatches by libraryViewModel.availableWatches.collectAsState()

                SavedAnalysesScreen(
                    savedAnalyses = savedAnalyses,
                    repository = repository,
                    availablePeople = availablePeople,
                    availableWatches = availableWatches,
                    onOpenDrawer = openDrawer,
                    onOpen = { navController.navigate("${Routes.ANALYSIS_SAVED}/$it") },
                    onNewAnalysis = { filter ->
                        // The analysis carries its own scope, so starting one never changes what
                        // the Library is filtered to.
                        analysisFilter = filter
                        navController.navigate(Routes.ANALYSIS_LIVE)
                    },
                    onPickForConcurrentAnalysis = {
                        // No filter dialog for this one: the user picks the exact recordings in
                        // the Library, because "these three, which overlapped" is not a filter.
                        awaitingConcurrentSelection = true
                        navController.navigateToSection(AppDestination.LIBRARY)
                    },
                    onDelete = { id -> scope.launch { repository.deleteSavedAnalysis(id) } }
                )
            }

            composable(Routes.ANALYSIS_LIVE) {
                val viewModel: AnalysisViewModel = viewModel(
                    key = analysisFilter.hashCode().toString(),
                    factory = AnalysisViewModel.liveFactory(
                        repository = repository,
                        filter = analysisFilter
                    )
                )
                AnalysisScreen(
                    navController = navController,
                    viewModel = viewModel,
                    onOpenDrawer = openDrawer,
                    onSave = { name, records ->
                        scope.launch {
                            repository.saveAnalysis(
                                name = name,
                                filterDescription = "${records.size} recordings",
                                records = records.map { it.toSnapshot() }
                            )
                            navController.navigateToSection(AppDestination.ANALYSIS)
                        }
                    }
                )
            }

            composable(
                route = "${Routes.ANALYSIS_SAVED}/{analysisId}",
                arguments = listOf(navArgument("analysisId") { type = NavType.LongType })
            ) { backStackEntry ->
                val analysisId = backStackEntry.arguments?.getLong("analysisId") ?: return@composable

                // The two kinds of saved analysis open onto different screens, and only the
                // stored row knows which this is.
                val saved by produceState<LoadedAnalysis?>(initialValue = null, analysisId) {
                    value = repository.loadSavedAnalysis(analysisId)
                }
                val metadata = saved?.metadata

                if (metadata?.isConcurrent == true) {
                    val allRecords by repository.records.collectAsState()
                    val watches by libraryViewModel.availableWatches.collectAsState()
                    val people by libraryViewModel.availablePeople.collectAsState()
                    val savedIds = remember(saved) { saved!!.records.map { it.recordId }.toSet() }

                    val stillPresent = remember(allRecords, savedIds) {
                        allRecords.filter { it.metadata.recordId in savedIds }
                    }
                    val analysis = remember(stillPresent, watches, people, metadata) {
                        ConcurrentAnalysis.from(
                            records = stillPresent,
                            watches = watches,
                            people = people,
                            window = metadata.windowStartMs?.let { start ->
                                metadata.windowEndMs?.let { end -> start..end }
                            }
                        )
                    }

                    ConcurrentAnalysisScreen(
                        analysis = analysis,
                        title = metadata.name,
                        records = stillPresent,
                        graphTitle = metadata.name,
                        // Already saved, so the action would only create a duplicate.
                        onSave = null,
                        onExportVideo = { recs, graphTitle -> openExport(recs, graphTitle) },
                        onOpenDrawer = openDrawer
                    )
                } else {
                    val viewModel: AnalysisViewModel = viewModel(
                        factory = AnalysisViewModel.savedFactory(repository, analysisId)
                    )
                    AnalysisScreen(
                        navController = navController,
                        viewModel = viewModel,
                        onOpenDrawer = openDrawer,
                        title = metadata?.name ?: "Saved Analysis"
                    )
                }
            }

            composable(Routes.WATCHES) {
                WatchesScreen(onOpenDrawer = openDrawer)
            }

            composable(Routes.PEOPLE) {
                PeopleScreen(onOpenDrawer = openDrawer)
            }

            composable(Routes.ANALYSIS_CONCURRENT) {
                val allRecords by repository.records.collectAsState()
                val watches by libraryViewModel.availableWatches.collectAsState()
                val people by libraryViewModel.availablePeople.collectAsState()

                // Curves are heavy, so the analysis is rebuilt only when its inputs actually
                // change rather than on every recomposition.
                val analysis = remember(allRecords, watches, people, concurrentRecordIds) {
                    ConcurrentAnalysis.from(
                        records = allRecords.filter { it.metadata.recordId in concurrentRecordIds },
                        watches = watches,
                        people = people
                    )
                }

                val selected = remember(allRecords, concurrentRecordIds) {
                    allRecords.filter { it.metadata.recordId in concurrentRecordIds }
                }

                ConcurrentAnalysisScreen(
                    analysis = analysis,
                    title = "Same-time analysis",
                    records = selected,
                    // Keeping a set of same-time recordings now makes an event rather than a saved
                    // analysis. It is the same thing named better: it survives in the Library, it
                    // merges a person's split recordings into one lane, and it can be grouped —
                    // none of which a frozen analysis row could do.
                    onSave = { name ->
                        scope.launch {
                            val eventId = repository.createEvent(name)
                            repository.assignRecordsToEvent(concurrentRecordIds, eventId)
                            navController.navigate("${Routes.EVENT_DETAIL}/$eventId") {
                                popUpTo(Routes.ANALYSIS_CONCURRENT) { inclusive = true }
                            }
                        }
                    },
                    onExportVideo = { recs, graphTitle -> openExport(recs, graphTitle) },
                    onOpenDrawer = openDrawer
                )
            }

            composable(
                route = "${Routes.EVENT_DETAIL}/{eventId}",
                arguments = listOf(navArgument("eventId") { type = NavType.LongType })
            ) { backStackEntry ->
                val eventId = backStackEntry.arguments?.getLong("eventId") ?: return@composable
                val eventViewModel: EventDetailViewModel = viewModel(
                    // Keyed on the event, or navigating from one event to another through a group
                    // would reuse the first one's ViewModel and show the wrong chart.
                    key = "event-$eventId",
                    factory = EventDetailViewModel.Factory(repository, eventId)
                )
                EventAnalysisScreen(
                    viewModel = eventViewModel,
                    onBack = { navController.popBackStack() },
                    onOpenRecord = { navController.navigate("${Routes.DETAIL}/$it") },
                    onOpenGroup = { navController.navigate("${Routes.GROUP_DETAIL}/$it") },
                    onExportVideo = { recs, graphTitle -> openExport(recs, graphTitle) },
                    onExportImage = { recs, graphTitle -> openExportAs(recs, graphTitle, ExportKind.IMAGE) }
                )
            }

            composable(
                route = "${Routes.GROUP_DETAIL}/{groupId}",
                arguments = listOf(navArgument("groupId") { type = NavType.LongType })
            ) { backStackEntry ->
                val groupId = backStackEntry.arguments?.getLong("groupId") ?: return@composable
                val groupViewModel: AnalysisViewModel = viewModel(
                    key = "group-$groupId",
                    factory = AnalysisViewModel.groupFactory(repository, groupId)
                )
                AnalysisScreen(
                    navController = navController,
                    viewModel = groupViewModel,
                    onOpenDrawer = openDrawer,
                    onBack = { navController.popBackStack() },
                    onSave = { name, records ->
                        scope.launch {
                            repository.saveAnalysis(
                                name = name,
                                filterDescription = "Group",
                                records = records.map { it.toSnapshot() }
                            )
                            navController.navigateToSection(AppDestination.ANALYSIS)
                        }
                    },
                    onExportImage = {
                        // Scoped as the *group* rather than as its recordings, so the utility can
                        // still offer one image per event. Flattening it to a bare set of records
                        // here would throw away the structure that choice depends on.
                        exportViewModel.startAt(
                            source = ExportSource.Group(groupId),
                            step = ExportStep.CONTENTS,
                            kind = ExportKind.IMAGE
                        )
                        navController.navigateToSection(AppDestination.EXPORT)
                    }
                )
            }

            composable(Routes.ABOUT) {
                AboutScreen(onOpenDrawer = openDrawer)
            }

            composable(Routes.SETTINGS) {
                val settingsViewModel: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.Factory(repository, settingsRepository)
                )
                // No onLeave: nothing is staged any more, so leaving cannot lose anything and
                // the screen has nothing to ask about on the way out.
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onOpenDrawer = openDrawer
                )
            }

            composable(
                route = "${Routes.DETAIL}/{recordId}",
                arguments = listOf(navArgument("recordId") { type = NavType.LongType })
            ) { backStackEntry ->
                val recordId = backStackEntry.arguments?.getLong("recordId") ?: return@composable
                val viewModel: BpmRecordViewModel = viewModel(
                    factory = BpmRecordViewModel.Factory(repository, recordId)
                )
                val thisRecord by viewModel.record.collectAsState()
                BpmRecordScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onDeleted = { navController.popBackStack() },
                    onExportImage = {
                        openExportAs(listOfNotNull(thisRecord), null, ExportKind.IMAGE)
                    },
                    onExportVideo = {
                        openExportAs(listOfNotNull(thisRecord), null, ExportKind.VIDEO)
                    },
                    onManageTags = { navController.navigate(Routes.TAG_MANAGEMENT) },
                    onOpenEvent = { navController.navigate("${Routes.EVENT_DETAIL}/$it") },
                    onOpenGroup = { navController.navigate("${Routes.GROUP_DETAIL}/$it") }
                )
            }

            composable(Routes.EXPORT) {
                ExportUtilityScreen(
                    viewModel = exportViewModel,
                    onOpenDrawer = openDrawer,
                    onOpenQueue = { navController.navigate(Routes.RENDER_QUEUE) }
                )
            }

            composable(Routes.RENDER_QUEUE) {
                inga.bpmetrics.ui.export.RenderQueueScreen(onOpenDrawer = openDrawer)
            }
        }
        }
    }
}

/**
 * Switches to a top-level section.
 *
 * Sections replace one another rather than stacking, so repeatedly opening the drawer cannot build
 * a deep back stack. State of the section being left is saved and restored on return.
 */
private fun NavHostController.navigateToSection(destination: AppDestination) {
    navigate(destination.route) {
        popUpTo(Routes.LIBRARY) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Constants for defining navigation routes in the app.
 */
object Routes {
    /** Combined analysis route. */
    const val ANALYSIS = "analysis"
    const val TAG_MANAGEMENT = "tag_management"
    const val LIBRARY = "library"
    const val DETAIL = "detail"
    const val SETTINGS = "settings"

    /** The staged export flow: source, contents, look, make. */
    const val EXPORT = "export"

    /**
     * What is rendering, and what has rendered.
     *
     * A section of its own rather than the tail of the export flow. Folding it into step 4 meant
     * the last thing the flow asked was "start these" and the first thing it then showed was a
     * list of everything ever queued — so finishing an export looked like being handed a backlog.
     * Renders also outlive the flow that made them: coming back to check on one should not mean
     * walking through four steps of a new export to reach it.
     */
    const val RENDER_QUEUE = "render_queue"
    const val WATCHES = "watches"
    const val PEOPLE = "people"
    const val ABOUT = "about"

    /** A live analysis of the Library's current filter, which can be saved. */
    const val ANALYSIS_LIVE = "analysis_live"

    /** A stored analysis, rendered from what was captured when it was saved. */
    const val ANALYSIS_SAVED = "analysis_saved"

    /** Everyone's curves over one shared stretch of time. */
    const val ANALYSIS_CONCURRENT = "analysis_concurrent"

    /** One event: everyone who was there, as one lane each. */
    const val EVENT_DETAIL = "event_detail"

    /**
     * One group, aggregated. Renders the same screen as [ANALYSIS_LIVE] — a group is a scope, not
     * a different kind of analysis.
     */
    const val GROUP_DETAIL = "group_detail"
}
