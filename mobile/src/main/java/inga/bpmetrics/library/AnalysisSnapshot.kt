package inga.bpmetrics.library

/**
 * A tag as it stood when an analysis was taken.
 *
 * The category name is copied alongside its id so a stored analysis stays readable after the
 * category is renamed or deleted.
 */
data class AnalysisSnapshotTag(
    val tagName: String,
    val categoryId: Long,
    val categoryName: String
)

/**
 * One recording's values, copied at the moment an analysis was saved.
 *
 * These are what a stored analysis is computed from, which is why it does not change when the
 * library does — and why it still displays correctly after its recordings are deleted.
 */
data class AnalysisSnapshotRecord(
    val recordId: Long,
    val title: String,
    val date: Long,
    val minBpm: Double?,
    val avgBpm: Double?,
    val maxBpm: Double?,
    val activeDurationMs: Long,
    val tags: List<AnalysisSnapshotTag> = emptyList(),
    /** Who was wearing the watch, as frozen onto the record. */
    val wearerName: String = "",
    /** The watch it came from, by the device name it had when the analysis was taken. */
    val watchName: String = "",
    /** Who and where, by id, so a frozen analysis can colour people and rank events. */
    val personId: Long? = null,
    val personColorArgb: Int? = null,
    val eventId: Long? = null,
    val eventName: String = "",
    /**
     * Time in each heart rate band, captured because it cannot be recomputed later — a saved
     * analysis keeps no data points.
     */
    val zones: List<SnapshotZone> = emptyList()
)

/** One band's share of a recording's measured time, frozen at save. */
data class SnapshotZone(val name: String, val durationMs: Long)

/**
 * A stored analysis read back from the database.
 *
 * @property recordsStillInLibrary How many of the captured recordings still exist, so the UI can
 * say that some are gone rather than offering links that lead nowhere.
 */
data class LoadedAnalysis(
    val metadata: SavedAnalysisEntity,
    val records: List<AnalysisSnapshotRecord>,
    val recordsStillInLibrary: Int
)
