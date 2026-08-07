package inga.bpmetrics.library

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A queued render, stored so it survives the process that queued it.
 *
 * A render takes minutes and a phone is free to kill the app during any of them. Holding the queue
 * in memory meant a swipe-away or an OOM lost every pending job with no trace — not even a row
 * saying a render had been abandoned, so the only symptom was a video that never appeared.
 *
 * **Records are stored as ids, never as data.** A `VideoExportConfig` carries whole `BpmRecord`s,
 * data points and all, and writing those here would duplicate the library into a second table that
 * immediately starts drifting from it. The ids are rehydrated from the library when the job runs,
 * which also means a job whose recording was deleted fails honestly instead of rendering a stale
 * copy of it.
 *
 * The appearance half is [presetJson] — the same `ExportPreset` used everywhere else, so there is
 * one serialization format for "how an export looks" rather than a second one that only the queue
 * understands.
 */
@Entity(tableName = "render_jobs")
data class RenderJobEntity(
    @PrimaryKey val jobId: String,

    /** The record the service reports against, and whose title names the notification. */
    val recordId: Long,
    val title: String,

    /** Every record this job draws, comma-separated. Rehydrated from the library at render time. */
    val recordIdsCsv: String,

    /** Appearance, framing, sync offset and frame rate, as an `ExportPreset`. */
    val presetJson: String,

    /** Per-record colours, as `recordId:argb` pairs. Content, so not in the preset. */
    val colorsCsv: String,

    val graphTitle: String?,
    val startTimeMs: Long,
    val endTimeMs: Long,

    val overlayUri: String?,
    /** The clip's resolved start, so a resumed job aligns exactly as the original would have. */
    val overlayStartedAtMs: Long?,
    val targetUri: String?,

    val status: String,
    val error: String?,

    /**
     * What this job is of, in words.
     *
     * Held rather than derived because it has to survive the recordings being deleted — a queue
     * that turns into six untitled rows the moment someone tidies their library is not a queue
     * anyone can act on.
     */
    val presetName: String?,
    val sourceLabel: String?,
    val recordCount: Int,

    /** Insertion order, so a restored queue runs in the order it was built. */
    val queuedAt: Long
)

@Dao
interface RenderJobDao {

    /** Oldest first: a restored queue should run in the order it was queued. */
    @Query("SELECT * FROM render_jobs ORDER BY queuedAt ASC")
    suspend fun getAll(): List<RenderJobEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: RenderJobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(jobs: List<RenderJobEntity>)

    @Query("DELETE FROM render_jobs WHERE jobId = :jobId")
    suspend fun delete(jobId: String)

    @Query("DELETE FROM render_jobs WHERE jobId NOT IN (:keep)")
    suspend fun deleteMissing(keep: List<String>)

    @Query("DELETE FROM render_jobs")
    suspend fun deleteAll()

    /**
     * Marks renders that were in flight when the process died.
     *
     * Nothing else can distinguish "rendering" from "was rendering when the app was killed": the
     * status is only ever written by a process that is now gone. Anything still claiming to render
     * at startup was interrupted, and saying so is the whole point of persisting the queue.
     */
    @Query("UPDATE render_jobs SET status = :failed, error = :reason WHERE status = :rendering")
    suspend fun markInterrupted(rendering: String, failed: String, reason: String)
}
