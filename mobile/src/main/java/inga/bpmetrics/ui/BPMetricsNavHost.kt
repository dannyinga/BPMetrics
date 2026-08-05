package inga.bpmetrics.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.ui.about.AboutScreen
import inga.bpmetrics.ui.analysis.AnalysisScreen
import inga.bpmetrics.ui.analysis.AnalysisViewModel
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
import inga.bpmetrics.ui.incoming.IncomingScreen
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
                LibraryScreen(navController, libraryViewModel, onOpenDrawer = openDrawer)
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
                val availableWearers by libraryViewModel.availableWearers.collectAsState()
                val availableWatches by libraryViewModel.availableWatches.collectAsState()

                SavedAnalysesScreen(
                    savedAnalyses = savedAnalyses,
                    repository = repository,
                    availableWearers = availableWearers,
                    availableWatches = availableWatches,
                    onOpenDrawer = openDrawer,
                    onOpen = { navController.navigate("${Routes.ANALYSIS_SAVED}/$it") },
                    onNewAnalysis = { filter ->
                        // The analysis carries its own scope, so starting one never changes what
                        // the Library is filtered to.
                        analysisFilter = filter
                        navController.navigate(Routes.ANALYSIS_LIVE)
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
                val viewModel: AnalysisViewModel = viewModel(
                    factory = AnalysisViewModel.savedFactory(repository, analysisId)
                )
                AnalysisScreen(
                    navController = navController,
                    viewModel = viewModel,
                    onOpenDrawer = openDrawer,
                    title = "Saved Analysis"
                )
            }

            composable(Routes.WATCHES) {
                WatchesScreen(onOpenDrawer = openDrawer)
            }

            composable(Routes.INCOMING) {
                IncomingScreen(onOpenDrawer = openDrawer)
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
    const val ABOUT = "about"
    const val INCOMING = "incoming"

    /** A live analysis of the Library's current filter, which can be saved. */
    const val ANALYSIS_LIVE = "analysis_live"

    /** A stored analysis, rendered from what was captured when it was saved. */
    const val ANALYSIS_SAVED = "analysis_saved"
}
