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
    val tags: List<AnalysisSnapshotTag> = emptyList()
)

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
