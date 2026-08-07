package inga.bpmetrics.ui

import androidx.activity.compose.BackHandler
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
import inga.bpmetrics.ui.analysis.AnalysisRecord
import inga.bpmetrics.ui.analysis.AnalysisScreen
import inga.bpmetrics.ui.analysis.AnalysisViewModel
import inga.bpmetrics.ui.analysis.ConcurrentAnalysis
import inga.bpmetrics.ui.analysis.ConcurrentAnalysisScreen
import inga.bpmetrics.ui.analysis.SavedAnalysesScreen
import inga.bpmetrics.ui.graph.BpmGraphDetailScreen
import inga.bpmetrics.ui.record.BpmRecordScreen
import inga.bpmetrics.ui.record.BpmRecordViewModel
import inga.bpmetrics.ui.library.LibraryScreen
import inga.bpmetrics.ui.library.LibraryViewModel
import inga.bpmetrics.ui.navigation.AppDestination
import inga.bpmetrics.ui.navigation.AppDrawerContent
import inga.bpmetrics.ui.settings.SettingsScreen
import inga.bpmetrics.ui.settings.SettingsViewModel
import inga.bpmetrics.ui.tags.TagManagementScreen
import inga.bpmetrics.ui.tags.TagManagementViewModel
import inga.bpmetrics.ui.export.RenderQueueScreen
import inga.bpmetrics.ui.export.VideoExportDialog
import inga.bpmetrics.ui.incoming.IncomingScreen
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

    // Hosted here rather than inside the analysis screen so the export survives navigating away
    // mid-configuration, and so both the saved and unsaved screens share one dialog.
    var videoExportRequest by remember {
        mutableStateOf<Pair<List<inga.bpmetrics.library.BpmRecord>, String?>?>(null)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Swiping open is only offered on a top-level section, and never while the Library is in
        // selection mode, where the gesture would fight multi-select.
        gesturesEnabled = drawerState.isOpen ||
            (currentDestination != null && selectedRecordIds.isEmpty()),
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

        NavHost(
            navController = navController,
            startDestination = Routes.LIBRARY
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
                    onAnalyseCurrentFilter = { filter ->
                        analysisFilter = filter
                        navController.navigate(Routes.ANALYSIS_LIVE)
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
                        onExportVideo = { recs, graphTitle -> videoExportRequest = recs to graphTitle },
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

            composable(Routes.INCOMING) {
                IncomingScreen(onOpenDrawer = openDrawer)
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
                    onSave = { name ->
                        scope.launch {
                            repository.saveConcurrentAnalysis(
                                name = name,
                                recordIds = concurrentRecordIds,
                                windowStartMs = analysis.windowStartMs,
                                windowEndMs = analysis.windowEndMs,
                                // Watches are passed so the stored rows carry each watch's given
                                // name. Tags are not: a same-time analysis does not rank by them,
                                // and they are dropped when the rows are written.
                                records = AnalysisRecord
                                    .from(selected, categories = emptyList(), watches = watches)
                                    .map { it.toSnapshot() }
                            )
                            navController.navigateToSection(AppDestination.ANALYSIS)
                        }
                    },
                    onExportVideo = { recs, graphTitle -> videoExportRequest = recs to graphTitle },
                    onOpenDrawer = openDrawer
                )
            }

            composable(Routes.ABOUT) {
                AboutScreen(onOpenDrawer = openDrawer)
            }

            composable(Routes.SETTINGS) {
                val settingsViewModel: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.Factory(repository, settingsRepository)
                )
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onOpenDrawer = openDrawer,
                    onLeave = { navController.navigateToSection(AppDestination.LIBRARY) }
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
                BpmRecordScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onDeleted = { navController.popBackStack() },
                    onShowDetailedGraph = { navController.navigate("${Routes.GRAPH_DETAIL}/$recordId") },
                    onManageTags = { navController.navigate(Routes.TAG_MANAGEMENT) }
                )
            }

            composable(
                route = "${Routes.GRAPH_DETAIL}/{recordId}",
                arguments = listOf(navArgument("recordId") { type = NavType.LongType })
            ) { backStackEntry ->
                val recordId = backStackEntry.arguments?.getLong("recordId") ?: return@composable
                val viewModel: BpmRecordViewModel = viewModel(
                    factory = BpmRecordViewModel.Factory(repository, recordId)
                )
                BpmGraphDetailScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Routes.RENDER_QUEUE) {
                RenderQueueScreen(navController, onOpenDrawer = openDrawer)
            }
        }

        videoExportRequest?.let { (recordsToExport, graphTitle) ->
            VideoExportDialog(
                record = recordsToExport.first(),
                records = recordsToExport,
                graphTitle = graphTitle,
                onDismiss = { videoExportRequest = null },
                onExport = { config, _ ->
                    BpmExportService.startExport(
                        context,
                        recordsToExport.first().metadata.recordId,
                        graphTitle ?: "Same-time export (${recordsToExport.size} wearers)",
                        config,
                        null
                    )
                    videoExportRequest = null
                }
            )
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
    const val GRAPH_DETAIL = "graph_detail"
    const val RENDER_QUEUE = "render_queue"
    const val WATCHES = "watches"
    const val PEOPLE = "people"
    const val ABOUT = "about"
    const val INCOMING = "incoming"

    /** A live analysis of the Library's current filter, which can be saved. */
    const val ANALYSIS_LIVE = "analysis_live"

    /** A stored analysis, rendered from what was captured when it was saved. */
    const val ANALYSIS_SAVED = "analysis_saved"

    /** Everyone's curves over one shared stretch of time. */
    const val ANALYSIS_CONCURRENT = "analysis_concurrent"
}
