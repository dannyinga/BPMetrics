package inga.bpmetrics.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The venue registry.
 *
 * Modelled on [PersonDao] and [WatchDao] because a location is the same kind of thing: made once,
 * referred to by many, renamed in one place. That is what makes comparing across venues a
 * comparison of identities rather than of strings that have to match.
 */
@Dao
interface LocationDao {

    @Query("SELECT * FROM locations ORDER BY createdAt ASC, locationId ASC")
    fun getAllFlow(): Flow<List<LocationEntity>>

    @Query("SELECT * FROM locations")
    suspend fun getAll(): List<LocationEntity>

    @Query("SELECT * FROM locations WHERE locationId = :locationId")
    suspend fun get(locationId: Long): LocationEntity?

    @Insert
    suspend fun insert(location: LocationEntity): Long

    @Query("UPDATE locations SET name = :name WHERE locationId = :locationId")
    suspend fun rename(locationId: Long, name: String)

    @Query("UPDATE locations SET timeZoneId = :timeZoneId WHERE locationId = :locationId")
    suspend fun updateZone(locationId: Long, timeZoneId: String?)

    /**
     * Where it is, if anyone captured it.
     *
     * Both together, because half a coordinate pair is not a place — and a latitude with no
     * longitude is the kind of row that makes a map draw something off the coast of Ghana.
     */
    @Query(
        "UPDATE locations SET latitude = :latitude, longitude = :longitude " +
            "WHERE locationId = :locationId"
    )
    suspend fun updateCoordinates(locationId: Long, latitude: Double?, longitude: Double?)

    @Query("UPDATE locations SET photoPath = :path WHERE locationId = :locationId")
    suspend fun updatePhoto(locationId: Long, path: String?)

    @Query("SELECT photoPath FROM locations WHERE locationId = :locationId")
    suspend fun photoPathOf(locationId: Long): String?

    @Query("DELETE FROM locations WHERE locationId = :locationId")
    suspend fun delete(locationId: Long)

    /** How many events name this place, so a delete can say what it is about to affect. */
    @Query("SELECT COUNT(*) FROM events WHERE locationId = :locationId")
    suspend fun countEventsAt(locationId: Long): Int
}
