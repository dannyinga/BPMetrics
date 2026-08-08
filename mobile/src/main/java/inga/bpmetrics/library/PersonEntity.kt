package inga.bpmetrics.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Someone who wears a watch.
 *
 * A wearer used to be a bare string typed in wherever it was needed, which meant "Kyle", "kyle" and
 * "Kyle " were three different people to every filter and grouping in the app. A person is now a
 * profile that is picked from a list, so there is exactly one of each.
 *
 * Records point at a person by [personId] rather than copying the name, so correcting a spelling
 * fixes every recording that person ever made. What does *not* follow is a watch changing hands:
 * the record remembers who was wearing it, not who has the watch now, so handing Watch A to Kyle on
 * Saturday and Ben on Sunday leaves each day attributed correctly.
 *
 * @property personId Stable identifier. What records and watches actually store.
 * @property name What to call them. Free to change without disturbing history.
 * @property colorArgb Their colour everywhere it matters — the stripe on a recording in the library,
 * their curve in a concurrent analysis, their line and pill in an exported video. One colour, set in
 * one place, so a person looks the same wherever they appear.
 * @property createdAt When the profile was made, used only to order the list sensibly.
 */
@Entity(tableName = "people")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val personId: Long = 0,
    val name: String,
    val colorArgb: Int,
    val createdAt: Long = 0L,
    /**
     * This person's own resting rate, or null to use the app-wide figure.
     *
     * On the person rather than in Settings because it *is* a fact about a person: a runner's
     * resting rate and a stranger's are different numbers, and a single app-wide value would make
     * time-in-zone say something false about whichever of them it did not describe. Settings holds
     * the fallback for anyone who has not given one.
     */
    @ColumnInfo(defaultValue = "NULL") val restingBpm: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val maxBpm: Int? = null
) {
    /** How to refer to this person when their name has somehow been left blank. */
    val displayName: String get() = name.takeIf { it.isNotBlank() } ?: "Unnamed person"

    /** Whether this person has figures of their own, rather than inheriting the defaults. */
    val hasOwnZones: Boolean get() = restingBpm != null || maxBpm != null
}
