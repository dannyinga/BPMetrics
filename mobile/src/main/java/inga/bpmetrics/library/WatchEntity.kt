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
 * A watch and its wearer are separate things, and this stores both. [deviceName] identifies the
 * hardware — "Watch A", the one with the blue strap — and stays put. [currentWearerName] is who
 * has it at the moment, and is what gets stamped onto arriving records before being frozen there.
 * Conflating them would mean renaming the watch every time it changed hands, and losing the
 * ability to ask "what did this particular watch record" once it had.
 *
 * @property watchId Stable identifier. For a watch running a current build this is the UUID it
 * generates on first run. For records that predate that field it is the reported device id, which
 * cannot separate two watches of the same model — hence the merge tooling.
 * @property deviceName What this watch is called. Blank until someone names it, in which case the
 * hardware model stands in.
 * @property currentWearerName Who is wearing it now. Stamped onto each arriving record and then
 * frozen there, so changing this never rewrites past recordings.
 * @property lastKnownModel The hardware model last reported, shown when the watch has no name.
 * @property lastKnownNodeId The Data Layer node last seen for this watch, for diagnostics.
 * @property colorArgb Colour used for this watch's line in multi-watch exports, so a person keeps
 * the same colour between exports. Null means fall back to the palette.
 * @property firstSeen When this watch was first registered.
 * @property lastSeen When a record last arrived from it.
 */
@Entity(tableName = "watches")
data class WatchEntity(
    @PrimaryKey val watchId: String,
    @ColumnInfo(defaultValue = "") val deviceName: String = "",
    @ColumnInfo(defaultValue = "") val currentWearerName: String = "",
    @ColumnInfo(defaultValue = "") val lastKnownModel: String = "",
    @ColumnInfo(defaultValue = "") val lastKnownNodeId: String = "",
    val colorArgb: Int? = null,
    val firstSeen: Long = 0L,
    val lastSeen: Long = 0L
) {
    /**
     * How to refer to this watch: its given name, else the hardware model.
     *
     * Always the device — never the wearer — so picking a watch from a list means picking the
     * hardware, and stays stable when it changes hands.
     */
    val displayName: String
        get() = deviceName.takeIf { it.isNotBlank() }
            ?: lastKnownModel.takeIf { it.isNotBlank() }
            ?: "Unknown watch"

    /** Whether the watch has been given a name of its own. */
    val isNamed: Boolean get() = deviceName.isNotBlank()

    /** Whether a wearer is set, which decides what gets stamped onto arriving records. */
    val hasWearer: Boolean get() = currentWearerName.isNotBlank()
}
