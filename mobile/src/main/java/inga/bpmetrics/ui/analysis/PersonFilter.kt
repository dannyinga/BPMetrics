package inga.bpmetrics.ui.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.ui.components.PersonAvatar

/**
 * Narrowing an analysis to some of the people in it.
 *
 * "Bass Canyon, just me" is a question the page could not ask. Isolating a lane on the timeline
 * brought one curve forward, but every figure around it — the totals, the Highlights, the rankings,
 * the zone split — still described everyone, so the one thing you wanted to know about yourself was
 * never on screen as a number.
 */

/**
 * The two rules, in one place, with nothing Compose about them — so they can be tested.
 */
object PersonFilterRule {

    /**
     * The recordings a filter keeps. Empty means everyone.
     *
     * A recording with nobody attached is dropped under any filter. It is not any of the people
     * chosen, and counting it for them would put a stranger's evening in their stats.
     */
    fun apply(records: List<AnalysisRecord>, people: Set<Long>): List<AnalysisRecord> =
        if (people.isEmpty()) records else records.filter { it.personId in people }

    /**
     * What to store for a set of ticks.
     *
     * Everyone ticked is stored as no filter at all. Otherwise the badge would claim the page is
     * narrowed while it shows exactly what it always showed — and a later recording from a fifth
     * person would be silently left out of an analysis that was meant to include everybody.
     */
    fun normalise(chosen: Set<Long>, inScope: Collection<Long>): Set<Long> =
        if (inScope.isNotEmpty() && chosen.containsAll(inScope)) emptySet() else chosen
}

/**
 * The toolbar button. Offered only where there is a choice to make — one person is not a filter.
 *
 * Carries a badge while narrowed, because a page quietly showing one person's stats under a header
 * that names four is the misreading this whole feature has to avoid.
 */
@Composable
fun PersonFilterButton(
    selectedCount: Int,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        BadgedBox(
            badge = {
                if (selectedCount > 0) Badge { Text("$selectedCount") }
            }
        ) {
            Icon(
                Icons.Default.People,
                contentDescription = if (selectedCount > 0) {
                    "Showing $selectedCount ${if (selectedCount == 1) "person" else "people"}"
                } else {
                    "Choose whose recordings to show"
                }
            )
        }
    }
}

/**
 * Says the analysis is narrowed, on every tab, with the way out beside it.
 *
 * Above the tabs rather than inside one, because the filter is not a Summary setting: it changes the
 * Timeline and Compare as well, and a notice that disappeared on switching tabs would leave those
 * two looking complete. The badge on the button is easy to miss; a line of text over the numbers is
 * not.
 */
@Composable
fun PersonFilterNotice(
    chosen: List<PersonEntity>,
    ofTotal: Int,
    onEdit: () -> Unit,
    onClear: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onEdit)
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.FilterAlt,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = buildString {
                    append("Showing ")
                    // Names while they fit; a count once they would not. "Danny, Colin and Tyler"
                    // is readable, and six names is a paragraph over the numbers.
                    append(
                        if (chosen.size <= 3) chosen.joinToString(", ") { it.displayName }
                        else "${chosen.size} people"
                    )
                    append(" of $ofTotal")
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClear) { Text("Show everyone") }
        }
    }
}

/**
 * Who to show, ticked in their own colours.
 *
 * Nothing ticked means everyone, and that is the state the dialog opens in when there is no filter —
 * rather than every box ticked, which would make "just me" a matter of unticking three other people.
 * Applied on confirm rather than per tick, so narrowing from four people to two does not recompute
 * every figure on the page twice on the way.
 */
@Composable
fun PersonFilterDialog(
    people: List<PersonEntity>,
    selected: Set<Long>,
    onApply: (Set<Long>) -> Unit,
    onDismiss: () -> Unit
) {
    var picked by remember(selected) { mutableStateOf(selected) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Show recordings from") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { picked = emptySet() }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = picked.isEmpty(), onCheckedChange = { picked = emptySet() })
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Everyone",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (picked.isEmpty()) FontWeight.Bold else FontWeight.Normal
                    )
                }
                HorizontalDivider()

                people.forEach { person ->
                    val on = person.personId in picked
                    val toggle = {
                        picked = if (on) picked - person.personId else picked + person.personId
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = toggle)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Checkbox(checked = on, onCheckedChange = { toggle() })
                        Spacer(Modifier.width(4.dp))
                        // Ringed in their colour: it is the colour of their curve on the chart
                        // this is about to narrow, so the choice is made in the same terms.
                        PersonAvatar(person = person, size = 30.dp, ring = Color(person.colorArgb))
                        Spacer(Modifier.width(12.dp))
                        Text(person.displayName, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(picked) }) { Text("Show") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Says the page leaves parts of its scope out, with the way to change it and the way back (S15.5).
 *
 * The same band as [PersonFilterNotice], because it does the same job: the figures describe less
 * than the title says, and the page has to admit it.
 */
@Composable
fun RefinedScopeNotice(
    leftOut: Int,
    onEdit: () -> Unit,
    onClear: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onEdit)
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.FilterAlt,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Leaving out $leftOut " + if (leftOut == 1) "part" else "parts",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClear) { Text("Include everything") }
        }
    }
}
