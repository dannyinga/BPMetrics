package inga.bpmetrics.ui.analysis

import inga.bpmetrics.library.AnalysisSnapshotRecord
import inga.bpmetrics.library.AnalysisSnapshotTag
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.CategoryEntity

/**
 * A tag as it applied to a record at the moment an analysis was taken.
 *
 * Carries the category name as well as its id so a saved analysis stays readable after the
 * category has been renamed or deleted. A frozen analysis is a statement about the past, and
 * should not change because the library moved on.
 */
data class AnalysisTag(
    val tagName: String,
    val categoryId: Long,
    val categoryName: String
)

/**
 * Everything an analysis needs to know about one recording.
 *
 * Deliberately not [BpmRecord]: an analysis uses only these few values, and saving them is what
 * lets a stored analysis survive its recordings being edited or deleted. Both the live path and a
 * saved snapshot produce this same shape, so the screen and the statistics do not care which they
 * are looking at.
 */
data class AnalysisRecord(
    val recordId: Long,
    val title: String,
    val date: Long,
    val minBpm: Double?,
    val avgBpm: Double?,
    val maxBpm: Double?,
    val activeDurationMs: Long,
    val tags: List<AnalysisTag> = emptyList()
) {
    companion object {
        /**
         * Reduces a library record to the values an analysis uses.
         *
         * @param categoryNames Category id to name, so tags carry a readable category.
         */
        fun from(record: BpmRecord, categoryNames: Map<Long, String>): AnalysisRecord =
            AnalysisRecord(
                recordId = record.metadata.recordId,
                title = record.metadata.title,
                date = record.metadata.date,
                minBpm = record.minDataPoint?.bpm,
                avgBpm = record.metadata.avg,
                maxBpm = record.maxDataPoint?.bpm,
                // Computed now rather than stored, because it depends on the data points and a
                // saved analysis will not have them.
                activeDurationMs = record.calculateActiveDurationMs(),
                tags = record.tags.map { tag ->
                    AnalysisTag(
                        tagName = tag.name,
                        categoryId = tag.parentCategoryId,
                        categoryName = categoryNames[tag.parentCategoryId] ?: "Uncategorized"
                    )
                }
            )

        /** Convenience for mapping a whole list against the category table. */
        fun from(records: List<BpmRecord>, categories: List<CategoryEntity>): List<AnalysisRecord> {
            val names = categories.associate { it.categoryId to it.name }
            return records.map { from(it, names) }
        }

        /** Reads back a record captured when an analysis was saved. */
        fun from(snapshot: AnalysisSnapshotRecord): AnalysisRecord = AnalysisRecord(
            recordId = snapshot.recordId,
            title = snapshot.title,
            date = snapshot.date,
            minBpm = snapshot.minBpm,
            avgBpm = snapshot.avgBpm,
            maxBpm = snapshot.maxBpm,
            activeDurationMs = snapshot.activeDurationMs,
            tags = snapshot.tags.map {
                AnalysisTag(tagName = it.tagName, categoryId = it.categoryId, categoryName = it.categoryName)
            }
        )
    }

    /** Captures this record for storage alongside a saved analysis. */
    fun toSnapshot(): AnalysisSnapshotRecord = AnalysisSnapshotRecord(
        recordId = recordId,
        title = title,
        date = date,
        minBpm = minBpm,
        avgBpm = avgBpm,
        maxBpm = maxBpm,
        activeDurationMs = activeDurationMs,
        tags = tags.map {
            AnalysisSnapshotTag(tagName = it.tagName, categoryId = it.categoryId, categoryName = it.categoryName)
        }
    )
}
