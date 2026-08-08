package inga.bpmetrics.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Access to the people who wear the watches, and to the records attributed to each of them.
 */
@Dao
interface PersonDao {

    /** Everyone, oldest profile first, so the list does not reshuffle as names are edited. */
    @Query("SELECT * FROM people ORDER BY createdAt ASC, personId ASC")
    fun getAllPeopleFlow(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM people ORDER BY createdAt ASC, personId ASC")
    suspend fun getAllPeople(): List<PersonEntity>

    @Query("SELECT * FROM people WHERE personId = :personId")
    suspend fun getPerson(personId: Long): PersonEntity?

    /** Finds someone by name, for matching up the string-based import and split paths. */
    @Query("SELECT * FROM people WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): PersonEntity?

    @Insert
    suspend fun insertPerson(person: PersonEntity): Long

    /**
     * Renames someone.
     *
     * This does reach backwards: records hold a [PersonEntity.personId], not a copy of the name, so
     * a correction here shows up on every recording they ever made. That is the point — the thing
     * history must not follow is a *watch* changing hands, not a spelling being fixed.
     */
    @Query("UPDATE people SET name = :name WHERE personId = :personId")
    suspend fun updateName(personId: Long, name: String)

    /**
     * This person's own resting and maximum rate, or null to inherit the app-wide figures.
     *
     * Written together because they describe one thing — the range zones are measured across — and
     * setting one without the other invites a resting rate above a maximum.
     */
    @Query("UPDATE people SET restingBpm = :restingBpm, maxBpm = :maxBpm WHERE personId = :personId")
    suspend fun updateZones(personId: Long, restingBpm: Int?, maxBpm: Int?)

    @Query("UPDATE people SET colorArgb = :colorArgb WHERE personId = :personId")
    suspend fun updateColor(personId: Long, colorArgb: Int)

    @Query("DELETE FROM people WHERE personId = :personId")
    suspend fun deletePerson(personId: Long)

    /** How many records are attributed to someone, so the UI can say what deleting them costs. */
    @Query("SELECT COUNT(*) FROM bpm_records WHERE personId = :personId")
    suspend fun countRecordsForPerson(personId: Long): Int

    /**
     * Unlinks a person's records without erasing who made them.
     *
     * Each record still carries the name it was stamped with, so a deleted profile leaves its
     * recordings readable rather than anonymous.
     */
    @Query("UPDATE bpm_records SET personId = NULL WHERE personId = :personId")
    suspend fun unlinkRecords(personId: Long)

    /** Releases any watch this person was assigned to, so it is not left pointing at nothing. */
    @Query("UPDATE watches SET currentPersonId = NULL WHERE currentPersonId = :personId")
    suspend fun unlinkWatches(personId: Long)

    /**
     * Attributes a chosen set of recordings to someone.
     *
     * The name is written alongside the link, matching what happens at ingest, so a recording stays
     * readable if that profile is removed later.
     */
    @Query(
        """
        UPDATE bpm_records
        SET personId = :personId, wearerName = :wearerName
        WHERE recordId IN (:recordIds)
        """
    )
    suspend fun assignPersonToRecords(recordIds: List<Long>, personId: Long?, wearerName: String): Int

    /** Re-attributes a watch's records within a date range, for correcting a late-named watch. */
    @Query(
        """
        UPDATE bpm_records
        SET personId = :personId, wearerName = :wearerName
        WHERE watchId = :watchId AND date BETWEEN :fromDate AND :toDate
        """
    )
    suspend fun reattributeRecords(
        watchId: String,
        personId: Long,
        wearerName: String,
        fromDate: Long,
        toDate: Long
    ): Int
}
