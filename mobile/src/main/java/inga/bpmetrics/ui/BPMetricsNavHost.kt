package inga.bpmetrics.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import inga.bpmetrics.BPMetricsApp
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.ui.analysis.AnalysisScreen
import inga.bpmetrics.ui.analysis.AnalysisViewModel
import inga.bpmetrics.ui.graph.BpmGraphDetailScreen
import inga.bpmetrics.ui.record.BpmRecordScreen
import inga.bpmetrics.ui.record.BpmRecordViewModel
import inga.bpmetrics.ui.library.LibraryScreen
import inga.bpmetrics.ui.library.LibraryViewModel
import inga.bpmetrics.ui.settings.SettingsScreen
import inga.bpmetrics.ui.settings.SettingsViewModel
import inga.bpmetrics.ui.tags.TagManagementScreen
import inga.bpmetrics.ui.tags.TagManagementViewModel

/**
 * The main navigation host for the mobile application.
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

    NavHost(
        navController = navController,
        startDestination = Routes.LIBRARY
    ) {
        composable(Routes.LIBRARY) {
            LibraryScreen(navController, libraryViewModel)
        }

        composable(Routes.TAG_MANAGEMENT) {
            val viewModel: TagManagementViewModel = viewModel(
                factory = TagManagementViewModel.Factory(repository)
            )
            TagManagementScreen(navController, viewModel)
        }

        composable(Routes.ANALYSIS) {
            val filterState by libraryViewModel.filterState.collectAsState()
            
            // Pass the pre-filtered records flow and current filter state to the AnalysisViewModel
            val viewModel: AnalysisViewModel = viewModel(
                factory = AnalysisViewModel.Factory(
                    repository = repository,
                    filteredRecords = libraryViewModel.filteredRecords,
                    initialFilter = filterState
                )
            )
            AnalysisScreen(navController, viewModel)
        }

        composable(Routes.SETTINGS) {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(repository, settingsRepository)
            )
            SettingsScreen(onBack = { navController.popBackStack() }, viewModel = settingsViewModel)
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
}
