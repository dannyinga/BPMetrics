package inga.bpmetrics.library

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A filter someone kept.
 *
 * The thing that makes the filter bar worth building. A filter that has to be rebuilt every time is
 * a form; a filter that can be pinned is a view — "Kyle's festivals", "anything over 180" — and the
 * difference is whether you ask the question once or every time.
 *
 * Distinct from a saved *analysis*, which freezes numbers. A view stores the question and re-asks it
 * against the library as it is now, so a recording added tomorrow appears in it. Freezing would make
 * it an analysis; not freezing is the entire point.
 *
 * @property filterJson The filter, serialised. Stored as text rather than as a column per dimension
 * because the dimensions change — location was added this sprint — and a table that has to grow a
 * column each time is a migration each time. The cost is that it cannot be queried in SQL, which
 * nothing needs: views are read as a list and applied in memory.
 */
@Entity(tableName = "saved_views")
data class SavedViewEntity(
    @PrimaryKey(autoGenerate = true) val viewId: Long = 0,
    val name: String,
    val filterJson: String,
    val createdAt: Long = 0L,
    /**
     * Whether it sits on the library rather than behind a menu.
     *
     * Pinning is the feature. A view you have to go and find is only marginally better than
     * rebuilding the filter.
     */
    @ColumnInfo(defaultValue = "1") val isPinned: Boolean = true
) {
    val displayName: String get() = name.takeIf { it.isNotBlank() } ?: "Untitled view"
}

@Dao
interface SavedViewDao {

    @Query("SELECT * FROM saved_views ORDER BY createdAt ASC")
    fun getAllFlow(): Flow<List<SavedViewEntity>>

    @Insert
    suspend fun insert(view: SavedViewEntity): Long

    @Query("UPDATE saved_views SET name = :name WHERE viewId = :viewId")
    suspend fun rename(viewId: Long, name: String)

    /** Replaces what a view asks, for "I meant this, plus Ben". */
    @Query("UPDATE saved_views SET filterJson = :filterJson WHERE viewId = :viewId")
    suspend fun updateFilter(viewId: Long, filterJson: String)

    @Query("UPDATE saved_views SET isPinned = :pinned WHERE viewId = :viewId")
    suspend fun setPinned(viewId: Long, pinned: Boolean)

    @Query("DELETE FROM saved_views WHERE viewId = :viewId")
    suspend fun delete(viewId: Long)
}
