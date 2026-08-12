package inga.bpmetrics.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Somewhere things happen, in the registry sense — a venue, a room, a house.
 *
 * The same kind of thing as a person or a watch: made once, pointed at by many events, renamed in
 * one place. That is what makes "the Gorge against Showbox" a comparison of *identities* rather than
 * of two strings that have to match, and it is why the Gorge spelled three ways is not three venues.
 *
 * **The time zone is chosen here, not derived from coordinates.** The only reason to work a zone out
 * from a latitude is not knowing it, and someone naming a venue knows what time it is there. Asking
 * once, on a form filled in rarely, beats bundling several megabytes of boundary data that can be
 * subtly wrong near a border — and it keeps the feature working with no network and no third-party
 * dependency, which is the condition this app is most often used in.
 *
 * @property timeZoneId An IANA id from the platform's own tzdata, which Android keeps current. The
 * offsets and daylight-saving rules come from there; this only says which set of them applies.
 * @property latitude Optional and informational. Nothing depends on coordinates — they are for a map
 * one day, and for remembering where somewhere was. A location typed by hand is worth exactly as
 * much as one captured from the device.
 */
@Entity(tableName = "locations")
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val locationId: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "NULL") val timeZoneId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val latitude: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val longitude: Double? = null,
    /** A picture for it, stored the same way a person's photograph is. See [CoverStore]. */
    @ColumnInfo(defaultValue = "NULL") val photoPath: String? = null,
    val createdAt: Long = 0L
) {
    val displayName: String get() = name.takeIf { it.isNotBlank() } ?: "Untitled place"

    val hasCoordinates: Boolean get() = latitude != null && longitude != null

    /**
     * The zone this place keeps, or null if nobody has said.
     *
     * Validated rather than trusted: `TimeZone.getTimeZone` answers GMT for anything it does not
     * recognise, so a typo or a row from a build with a different id set would resolve silently to
     * GMT — which on the west coast is seven hours out and looks like a real answer.
     */
    val zone: java.util.TimeZone?
        get() = timeZoneId
            ?.takeIf { it in java.util.TimeZone.getAvailableIDs() }
            ?.let { java.util.TimeZone.getTimeZone(it) }
}
