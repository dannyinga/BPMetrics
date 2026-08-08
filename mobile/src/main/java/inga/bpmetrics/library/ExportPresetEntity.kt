package inga.bpmetrics.library

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A named, saved set of export appearance settings.
 *
 * Room rather than DataStore: presets are a list that grows, gets edited and deleted, and modelling
 * a collection in DataStore is how you end up hand-parsing JSON out of a string preference.
 *
 * [configJson] holds appearance only — never record ids, time ranges or an overlay video. A preset
 * that carried those would stop being reusable the moment the recordings it named were gone, which
 * defeats the point of saving one. See `ExportPreset` for what is stripped and why.
 */
@Entity(tableName = "export_presets")
data class ExportPresetEntity(
    @PrimaryKey(autoGenerate = true) val presetId: Long = 0,
    val name: String,
    val configJson: String,
    /** Pre-selected for a new export. At most one, enforced by the repository rather than SQL. */
    @ColumnInfo(defaultValue = "0") val isDefault: Boolean = false,
    /** Shipped with the app. Cannot be deleted, only overridden by saving a new one. */
    @ColumnInfo(defaultValue = "0") val isBuiltIn: Boolean = false,
    val createdAt: Long = 0L
)

@Dao
interface ExportPresetDao {

    /** Built-ins last, so a user's own presets are the ones in reach. */
    @Query("SELECT * FROM export_presets ORDER BY isBuiltIn ASC, name ASC")
    fun getAllFlow(): Flow<List<ExportPresetEntity>>

    @Query("SELECT * FROM export_presets WHERE presetId = :presetId")
    suspend fun getPreset(presetId: Long): ExportPresetEntity?

    @Query("SELECT * FROM export_presets WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): ExportPresetEntity?

    @Query("SELECT COUNT(*) FROM export_presets")
    suspend fun count(): Int

    /** Every stored preset, for maintenance that has to look at all of them at once. */
    @Query("SELECT * FROM export_presets")
    suspend fun getAll(): List<ExportPresetEntity>

    @Query("UPDATE export_presets SET configJson = :configJson WHERE presetId = :presetId")
    suspend fun updateConfig(presetId: Long, configJson: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(preset: ExportPresetEntity): Long

    @Query("UPDATE export_presets SET name = :name, configJson = :configJson WHERE presetId = :presetId")
    suspend fun update(presetId: Long, name: String, configJson: String)

    @Query("UPDATE export_presets SET isDefault = 0")
    suspend fun clearDefault()

    @Query("UPDATE export_presets SET isDefault = 1 WHERE presetId = :presetId")
    suspend fun markDefault(presetId: Long)

    /** Built-ins are protected here rather than only in the UI, so nothing can delete one. */
    @Query("DELETE FROM export_presets WHERE presetId = :presetId AND isBuiltIn = 0")
    suspend fun delete(presetId: Long)
}
