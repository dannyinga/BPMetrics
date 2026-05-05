package inga.bpmetrics.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import inga.bpmetrics.export.ImageExporter
import inga.bpmetrics.export.VideoExporter
import inga.bpmetrics.ui.settings.SettingsRepository.PreferencesKeys.DEFAULT_NAMING_CATEGORY_ID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Repository for managing application settings using DataStore.
 */
class SettingsRepository(context: Context) {

    private val dataStore = context.dataStore

    private object PreferencesKeys {
        val DEFAULT_NAMING_CATEGORY_ID = longPreferencesKey("default_naming_category_id")

        // Image Export Defaults
        val IMG_WIDTH = stringPreferencesKey("img_width")
        val IMG_HEIGHT = stringPreferencesKey("img_height")
        val IMG_OPACITY = floatPreferencesKey("img_opacity")
        val IMG_SHOW_AXES = booleanPreferencesKey("img_show_axes")
        val IMG_SHOW_LABELS = booleanPreferencesKey("img_show_labels")
        val IMG_SHOW_GRID = booleanPreferencesKey("img_show_grid")
        val IMG_SHOW_TITLE = booleanPreferencesKey("img_show_title")

        // Video Export Defaults
        val VID_WIDTH = stringPreferencesKey("vid_width")
        val VID_HEIGHT = stringPreferencesKey("vid_height")
        val VID_WINDOW_SIZE = stringPreferencesKey("vid_window_size")
        val VID_OPACITY = floatPreferencesKey("vid_opacity")
        val VID_SHOW_AXES = booleanPreferencesKey("vid_show_axes")
        val VID_SHOW_LABELS = booleanPreferencesKey("vid_show_labels")
        val VID_SHOW_GRID = booleanPreferencesKey("vid_show_grid")
        val VID_SHOW_TITLE = booleanPreferencesKey("vid_show_title")
        val VID_SHOW_STATS = booleanPreferencesKey("vid_show_stats")
        val VID_LOCK_ASPECT = booleanPreferencesKey("vid_lock_aspect")
        val VID_SYNC_OFFSET = longPreferencesKey("vid_sync_offset")
        val VID_GRAPH_RECT = stringPreferencesKey("vid_graph_rect")
    }

    /**
     * The ID of the category currently used for auto-naming new records.
     */
    val defaultNamingCategoryId: Flow<Long?> = dataStore.data
        .map { preferences -> preferences[DEFAULT_NAMING_CATEGORY_ID] }

    suspend fun setDefaultNamingCategory(categoryId: Long) {
        dataStore.edit { it[DEFAULT_NAMING_CATEGORY_ID] = categoryId }
    }

    suspend fun clearDefaultNamingCategory() {
        dataStore.edit { preferences ->
            // This removes the key entirely, which makes the Flow emit null
            preferences.remove(DEFAULT_NAMING_CATEGORY_ID)
        }
    }

    // Image Settings
    val imgWidth = dataStore.data.map { it[PreferencesKeys.IMG_WIDTH] ?: "1920" }
    val imgHeight = dataStore.data.map { it[PreferencesKeys.IMG_HEIGHT] ?: "1080" }
    val imgOpacity = dataStore.data.map { it[PreferencesKeys.IMG_OPACITY] ?: 100f }
    val imgShowAxes = dataStore.data.map { it[PreferencesKeys.IMG_SHOW_AXES] ?: true }
    val imgShowLabels = dataStore.data.map { it[PreferencesKeys.IMG_SHOW_LABELS] ?: true }
    val imgShowGrid = dataStore.data.map { it[PreferencesKeys.IMG_SHOW_GRID] ?: true }
    val imgShowTitle = dataStore.data.map { it[PreferencesKeys.IMG_SHOW_TITLE] ?: true }

    // Refactored Image Defaults to use ImageExportConfig
    suspend fun setImageDefaults(config: ImageExporter.ImageExportConfig) {
        dataStore.edit { p ->
            p[PreferencesKeys.IMG_WIDTH] = config.width.toString()
            p[PreferencesKeys.IMG_HEIGHT] = config.height.toString()
            p[PreferencesKeys.IMG_OPACITY] = config.backgroundOpacity.toFloat()
            p[PreferencesKeys.IMG_SHOW_AXES] = config.showAxes
            p[PreferencesKeys.IMG_SHOW_LABELS] = config.showLabels
            p[PreferencesKeys.IMG_SHOW_GRID] = config.showGrid
            p[PreferencesKeys.IMG_SHOW_TITLE] = config.showTitle
        }
    }

    // Video Settings
    val vidWidth = dataStore.data.map { it[PreferencesKeys.VID_WIDTH] ?: "1280" }
    val vidHeight = dataStore.data.map { it[PreferencesKeys.VID_HEIGHT] ?: "720" }
    val vidWindowSize = dataStore.data.map { it[PreferencesKeys.VID_WINDOW_SIZE] ?: "30" }
    val vidOpacity = dataStore.data.map { it[PreferencesKeys.VID_OPACITY] ?: 40f }
    val vidShowAxes = dataStore.data.map { it[PreferencesKeys.VID_SHOW_AXES] ?: true }
    val vidShowLabels = dataStore.data.map { it[PreferencesKeys.VID_SHOW_LABELS] ?: false }
    val vidShowGrid = dataStore.data.map { it[PreferencesKeys.VID_SHOW_GRID] ?: false }
    val vidShowTitle = dataStore.data.map { it[PreferencesKeys.VID_SHOW_TITLE] ?: false }
    val vidShowStats = dataStore.data.map { it[PreferencesKeys.VID_SHOW_STATS] ?: true }
    val vidLockAspect = dataStore.data.map { it[PreferencesKeys.VID_LOCK_ASPECT] ?: true }
    val vidSyncOffset = dataStore.data.map { it[PreferencesKeys.VID_SYNC_OFFSET] ?: 0L }

    suspend fun setVideoDefaults(config: VideoExporter.VideoExportConfig) {
        dataStore.edit { p ->
            // Resolution - Accessing fields from the nested imageConfig
            p[PreferencesKeys.VID_WIDTH] = config.imageConfig.width.toString()
            p[PreferencesKeys.VID_HEIGHT] = config.imageConfig.height.toString()

            // Timing
            p[PreferencesKeys.VID_WINDOW_SIZE] = (config.windowSizeMs / 1000).toString()
            p[PreferencesKeys.VID_SYNC_OFFSET] = config.syncOffsetMs

            // Visuals - Accessing fields from the nested imageConfig
            p[PreferencesKeys.VID_OPACITY] = config.imageConfig.backgroundOpacity.toFloat()
            p[PreferencesKeys.VID_SHOW_AXES] = config.imageConfig.showAxes
            p[PreferencesKeys.VID_SHOW_LABELS] = config.imageConfig.showLabels
            p[PreferencesKeys.VID_SHOW_GRID] = config.imageConfig.showGrid
            p[PreferencesKeys.VID_SHOW_TITLE] = config.imageConfig.showTitle
            p[PreferencesKeys.VID_SHOW_STATS] = config.imageConfig.showCurrentStats
            p[PreferencesKeys.VID_LOCK_ASPECT] = config.lockAspectRatio

            // Overlay Placement (Stored as a CSV string)
            p[PreferencesKeys.VID_GRAPH_RECT] = "${config.graphRect.left},${config.graphRect.top},${config.graphRect.right},${config.graphRect.bottom}"
        }
    }

    /**
     * Helper to parse the stored CSV string back into a RectF for the UI
     */
    val vidGraphRect: Flow<android.graphics.RectF> = dataStore.data.map { preferences ->
        val csv = preferences[PreferencesKeys.VID_GRAPH_RECT] ?: "0.0,0.0,1.0,1.0"
        val parts = csv.split(",").mapNotNull { it.toFloatOrNull() }
        if (parts.size == 4) {
            android.graphics.RectF(parts[0], parts[1], parts[2], parts[3])
        } else {
            android.graphics.RectF(0f, 0f, 1f, 1f)
        }
    }

}
