package inga.bpmetrics.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A watch that recordings have arrived from, or that has been registered ahead of time.
 *
 * The registry exists so a watch can be given a human name — the person wearing it — from the
 * phone. That name is stamped onto each record as it arrives and then frozen: renaming a watch
 * never rewrites history. Hand a watch to Kyle on Saturday and Ben on Sunday, rename it in
 * between, and each day's recordings keep the name they were made under.
 *
 * @property watchId Stable identifier. For a watch running a current build this is the UUID it
 * generates on first run. For records that predate that field it is the reported device id, which
 * cannot separate two watches of the same model — hence the merge tooling.
 * @property customName The name given on the phone. Blank until someone sets one.
 * @property lastKnownModel The hardware model last reported, shown when there is no custom name.
 * @property lastKnownNodeId The Data Layer node last seen for this watch, for diagnostics.
 * @property colorArgb Colour used for this watch's line in multi-watch exports, so a person keeps
 * the same colour between exports. Null means fall back to the palette.
 * @property firstSeen When this watch was first registered.
 * @property lastSeen When a record last arrived from it.
 */
@Entity(tableName = "watches")
data class WatchEntity(
    @PrimaryKey val watchId: String,
    @ColumnInfo(defaultValue = "") val customName: String = "",
    @ColumnInfo(defaultValue = "") val lastKnownModel: String = "",
    @ColumnInfo(defaultValue = "") val lastKnownNodeId: String = "",
    val colorArgb: Int? = null,
    val firstSeen: Long = 0L,
    val lastSeen: Long = 0L
) {
    /** The name to show for this watch: the one given on the phone, else the hardware model. */
    val displayName: String
        get() = customName.takeIf { it.isNotBlank() }
            ?: lastKnownModel.takeIf { it.isNotBlank() }
            ?: "Unknown watch"

    /** Whether a name has been set, which decides what gets stamped onto arriving records. */
    val isNamed: Boolean get() = customName.isNotBlank()
}
