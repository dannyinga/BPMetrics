package inga.bpmetrics.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
    val icon: ImageVector,
    /**
     * Whether this is somewhere you *work*, rather than somewhere you go to set something up.
     *
     * The split is the whole point of the navigation. Four working destinations sit in a bar and
     * are always one tap away; the rest are visited occasionally and deliberately, and live behind
     * the menu. Nine drawer items with no grouping was the signal that they needed it.
     */
    val isPrimary: Boolean
) {
    // The bar is drawn in declaration order, so this list is the layout. Library sits in the
    // middle: it is the one opened most, and the middle of a bottom bar is the easiest place on
    // the screen to reach with a thumb.
    //
    // Collections rather than Analysis. They were two lists over one table: a frozen analysis is
    // a collection with its numbers kept, so the Analysis tab was showing a filtered subset of
    // what this shows all of. And analysis is not a *place* — §8.5 of the product doc: a detail
    // screen is a scope, its numbers and a split, so you analyse by opening a recording, an event
    // or a collection. Naming a tab after the verb implied a fourth kind of thing to go and find.
    COLLECTIONS(inga.bpmetrics.ui.Routes.COLLECTIONS, "Collections", Icons.Default.Bookmarks, true),
    LIBRARY(inga.bpmetrics.ui.Routes.LIBRARY, "Library", Icons.AutoMirrored.Filled.LibraryBooks, true),
    EXPORT(inga.bpmetrics.ui.Routes.EXPORT, "Export", Icons.Default.VideoLibrary, true),

    // The render queue is in neither the bar nor the menu. It folded into the Export screen's
    // header, which is where an export ends up and therefore where anyone goes looking for it —
    // and a second door to it in the menu made the menu a mixture of places you work and places
    // you configure. Still a destination, because Export navigates to it; just not listed.
    RENDER_QUEUE(inga.bpmetrics.ui.Routes.RENDER_QUEUE, "Render queue", Icons.Default.Movie, false),

    // The management screens, and then Settings and About where every other Android app puts them.
    PEOPLE(inga.bpmetrics.ui.Routes.PEOPLE, "People", Icons.Default.People, false),
    WATCHES(inga.bpmetrics.ui.Routes.WATCHES, "Watches", Icons.Default.Watch, false),
    TAGS(inga.bpmetrics.ui.Routes.TAG_MANAGEMENT, "Tags", Icons.Default.Sell, false),
    LOCATIONS(inga.bpmetrics.ui.Routes.LOCATIONS, "Locations", Icons.Default.Place, false),
    SETTINGS(inga.bpmetrics.ui.Routes.SETTINGS, "Settings", Icons.Default.Settings, false),
    ABOUT(inga.bpmetrics.ui.Routes.ABOUT, "About", Icons.Default.Info, false);

    companion object {
        /** The four in the navigation bar, in order. */
        val primary: List<AppDestination> get() = entries.filter { it.isPrimary }

        /** Everything behind the menu. */
        val secondary: List<AppDestination> get() = entries.filter { !it.isPrimary }

        /** The destination matching [route], or null if it is a detail screen. */
        fun fromRoute(route: String?): AppDestination? = entries.firstOrNull { it.route == route }
    }
}

/**
 * The four places you work, always one tap away.
 *
 * A bar rather than a drawer because Android's convention for three to five top-level destinations
 * is a bar, and because Queue in particular needs reaching while something is rendering — which is
 * exactly when someone is least inclined to go hunting through a menu for it.
 */
@Composable
fun AppNavigationBar(
    currentRoute: String?,
    activeRenderCount: Int,
    onNavigate: (AppDestination) -> Unit
) {
    NavigationBar {
        AppDestination.primary.forEach { destination ->
            NavigationBarItem(
                selected = destination.route == currentRoute,
                onClick = { onNavigate(destination) },
                icon = {
                    // Badged on Export, not on the queue. The queue is not in this bar — it is in
                    // Export's header — so this is where a render in flight has to show, or it is
                    // invisible until someone happens to open the screen it is running under.
                    if (destination == AppDestination.EXPORT && activeRenderCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge {
                                    Text(activeRenderCount.toString())
                                }
                            }
                        ) {
                            Icon(destination.icon, contentDescription = null)
                        }
                    } else {
                        Icon(destination.icon, contentDescription = null)
                    }
                },
                label = { Text(destination.label) }
            )
        }
    }
}

/**
 * Contents of the navigation drawer.
 *
 * @param currentRoute The route currently displayed, used to highlight the active section.
 * @param activeRenderCount Number of queued or rendering jobs; shown as a badge on Render Queue.
 * @param incomingCount Number of records still arriving from watches; badged on Settings, which
 * is where Sync now lives. Without it, a transfer in flight would be invisible until someone
 * happened to open that section.
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
    // Narrower than the default 360dp. The sheet holds five items now that the working sections
    // moved to the bar, and a full-width panel for a short list reads as though something is
    // missing from it.
    ModalDrawerSheet(modifier = Modifier.width(268.dp)) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "BPMetrics",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 28.dp)
            )
            Spacer(Modifier.height(24.dp))

            // Only the things you set up: people, watches, tags, settings. The places you *work*
            // are in the navigation bar, and the render queue is reached from the Export screen it
            // belongs to — listing either here would be a second door to the same place, and the
            // menu would stop being one kind of thing.
            //
            // About is separated below: it is reference material, not somewhere you configure.
            val sections = AppDestination.secondary.filter {
                it != AppDestination.ABOUT && it != AppDestination.RENDER_QUEUE
            }

            sections.forEach { destination ->
                NavigationDrawerItem(
                    label = { Text(destination.label) },
                    icon = { Icon(destination.icon, contentDescription = null) },
                    badge = {
                        // Sync lives in Settings now, so a transfer in flight is badged there —
                        // otherwise it would be invisible until someone happened to look.
                        // Sync lives in Settings, so a transfer in flight is badged there.
                        // Nothing badges the render queue here any more — it is not in this menu,
                        // and Export shows its own count in the header where the queue now lives.
                        val count = if (destination == AppDestination.SETTINGS) incomingCount else 0
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
