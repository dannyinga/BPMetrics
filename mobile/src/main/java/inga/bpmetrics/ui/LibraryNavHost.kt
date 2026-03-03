package inga.bpmetrics.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import inga.bpmetrics.library.LibraryRepository

/**
 * The main navigation host for the mobile application.
 *
 * This composable sets up the [NavHost] and defines the navigation graph, including
 * the library list screen and the detailed record view screen.
 *
 * @param repository The [LibraryRepository] used to provide data to the ViewModels.
 */
@Composable
fun LibraryNavHost(repository: LibraryRepository) {
    val navController = rememberNavController()
    // Lazily initialize the library view model to be shared across the library screen
    val libraryViewModel by lazy { LibraryViewModel(repository) }

    NavHost(
        navController = navController,
        startDestination = Routes.LIBRARY
    ) {
        // Route 1: The main record library list
        composable(Routes.LIBRARY) {
            LibraryScreen(navController, libraryViewModel)
        }

        // Route 2: Detailed view for a specific BPM record
        composable(
            route = "${Routes.DETAIL}/{recordId}",
            arguments = listOf(navArgument("recordId") {
                type = NavType.LongType
            })
        ) { backStackEntry ->
            // Extract the recordId from navigation arguments
            val recordId = backStackEntry.arguments?.getLong("recordId")
                ?: return@composable

            // Create the record-specific ViewModel using its custom factory
            val viewModel: BpmRecordViewModel = viewModel(
                factory = BpmRecordViewModel.Factory(repository, recordId)
            )

            BpmRecordScreen(
                viewModel = viewModel, 
                onBack = { navController.popBackStack() }, 
                onDeleted = { navController.popBackStack() }
            )
        }
    }
}

/**
 * Constants and helper functions for defining navigation routes in the app.
 */
object Routes {
    /** Route identifier for the library list screen. */
    const val LIBRARY = "library"
    /** Base route identifier for the record detail screen. */
    const val DETAIL = "detail"

    /**
     * Generates a navigation route for a specific record ID.
     * 
     * @param recordId The unique ID of the BPM record.
     * @return A formatted route string including the ID parameter.
     */
    fun recordRoute(recordId: Long) = "$DETAIL/$recordId"
}
