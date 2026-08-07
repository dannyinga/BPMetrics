package inga.bpmetrics.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import inga.bpmetrics.ui.theme.BpmHigh

/**
 * A top-level section of the app, reachable from the navigation drawer.
 *
 * These are siblings rather than a stack: navigating between them replaces the current section
 * instead of pushing onto it. Detail screens (a record, a graph) are not listed here — they are
 * pushed on top of whichever section opened them.
 *
 * @property route The navigation route, matching a constant in [inga.bpmetrics.ui.Routes].
 * @property label The name shown in the drawer.
 * @property icon The icon shown in the drawer.
 */
enum class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    LIBRARY(inga.bpmetrics.ui.Routes.LIBRARY, "Library", Icons.AutoMirrored.Filled.LibraryBooks),
    ANALYSIS(inga.bpmetrics.ui.Routes.ANALYSIS, "Analysis", Icons.AutoMirrored.Filled.Sort),
    TAGS(inga.bpmetrics.ui.Routes.TAG_MANAGEMENT, "Tags", Icons.Default.Sell),
    INCOMING(inga.bpmetrics.ui.Routes.INCOMING, "Incoming", Icons.Default.CloudDownload),
    EXPORT(inga.bpmetrics.ui.Routes.EXPORT, "Export", Icons.Default.VideoLibrary),
    PEOPLE(inga.bpmetrics.ui.Routes.PEOPLE, "People", Icons.Default.People),
    WATCHES(inga.bpmetrics.ui.Routes.WATCHES, "Watches", Icons.Default.Watch),
    SETTINGS(inga.bpmetrics.ui.Routes.SETTINGS, "Settings", Icons.Default.Settings),
    ABOUT(inga.bpmetrics.ui.Routes.ABOUT, "About", Icons.Default.Info);

    companion object {
        /** The destination matching [route], or null if it is a detail screen. */
        fun fromRoute(route: String?): AppDestination? = entries.firstOrNull { it.route == route }
    }
}

/**
 * Contents of the navigation drawer.
 *
 * @param currentRoute The route currently displayed, used to highlight the active section.
 * @param activeRenderCount Number of queued or rendering jobs; shown as a badge on Render Queue.
 * @param incomingCount Number of records still arriving from watches; badged on Incoming.
 * @param onNavigate Invoked with the chosen destination. The caller is responsible for closing
 * the drawer and performing the navigation.
 */
@Composable
fun AppDrawerContent(
    currentRoute: String?,
    activeRenderCount: Int,
    incomingCount: Int,
    onNavigate: (AppDestination) -> Unit
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "BPMetrics",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 28.dp)
            )
            Spacer(Modifier.height(24.dp))

            // About is separated from the working sections: it is reference material, not a place
            // the user works.
            val sections = AppDestination.entries.filter { it != AppDestination.ABOUT }

            sections.forEach { destination ->
                NavigationDrawerItem(
                    label = { Text(destination.label) },
                    icon = { Icon(destination.icon, contentDescription = null) },
                    badge = {
                        val count = when (destination) {
                            // The badge follows the queue into the Export section, which is where
                            // step 4 now shows it.
                            AppDestination.EXPORT -> activeRenderCount
                            AppDestination.INCOMING -> incomingCount
                            else -> 0
                        }
                        if (count > 0) ActivityBadge(count)
                    },
                    selected = destination.route == currentRoute,
                    onClick = { onNavigate(destination) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp))

            NavigationDrawerItem(
                label = { Text(AppDestination.ABOUT.label) },
                icon = { Icon(AppDestination.ABOUT.icon, contentDescription = null) },
                selected = AppDestination.ABOUT.route == currentRoute,
                onClick = { onNavigate(AppDestination.ABOUT) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )

            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Count of in-flight work, so activity is visible without opening the section. */
@Composable
private fun ActivityBadge(count: Int) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(BpmHigh, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}
