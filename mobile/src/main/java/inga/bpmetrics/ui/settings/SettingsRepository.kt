package inga.bpmetrics.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import inga.bpmetrics.export.ImageExporter
import inga.bpmetrics.export.VideoExporter
import inga.bpmetrics.ui.settings.SettingsRepository.PreferencesKeys.DEFAULT_NAMING_CATEGORY_ID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        val IMG_SHOW_LABELS = booleanPreferencesKey("img_show_labels")
        val IMG_SHOW_GRID = booleanPreferencesKey("img_show_grid")
        val IMG_SHOW_TITLE = booleanPreferencesKey("img_show_title")

        // Video Export Defaults
        val VID_WIDTH = stringPreferencesKey("vid_width")
        val VID_HEIGHT = stringPreferencesKey("vid_height")
        val VID_WINDOW_SIZE = stringPreferencesKey("vid_window_size")
        val VID_FRAME_RATE = stringPreferencesKey("vid_frame_rate")
        val VID_OPACITY = floatPreferencesKey("vid_opacity")
        val VID_SHOW_LABELS = booleanPreferencesKey("vid_show_labels")
        val VID_SHOW_GRID = booleanPreferencesKey("vid_show_grid")
        val VID_SHOW_TITLE = booleanPreferencesKey("vid_show_title")
        val VID_SHOW_STATS = booleanPreferencesKey("vid_show_stats")
        val VID_LOCK_ASPECT = booleanPreferencesKey("vid_lock_aspect")
        val VID_SYNC_OFFSET = longPreferencesKey("vid_sync_offset")
        val VID_GRAPH_RECT = stringPreferencesKey("vid_graph_rect")
        val DEFAULT_TIME_ZONE = stringPreferencesKey("default_timezone")

        /** Which of the library's three views was last open. */
        val LIBRARY_VIEW_MODE = stringPreferencesKey("library_view_mode")

        /** Whether saved same-time analyses have already been turned into events. */
        val CONCURRENT_ANALYSES_CONVERTED = booleanPreferencesKey("concurrent_analyses_converted")

        /** Recordings the user has said, permanently, not to suggest an event for. */
        val DISMISSED_SUGGESTION_RECORDS = stringSetPreferencesKey("dismissed_suggestion_records")

        /** The export appearance last used, for when no preset has been made default. */
        val LAST_USED_EXPORT_PRESET = stringPreferencesKey("last_used_export_preset")

        // --- Appearance ---

        /** Whether to take colours from the wallpaper, where the platform offers them. */
        val DYNAMIC_COLOUR = booleanPreferencesKey("dynamic_colour")

        val USE_24_HOUR = booleanPreferencesKey("use_24_hour")

        /** A `DateTimeFormatter` pattern. Stored rather than an enum so a new format is one line. */
        val DATE_FORMAT = stringPreferencesKey("date_format")

        // --- Library ---

        val DEFAULT_SORT = stringPreferencesKey("default_sort")

        // --- Heart rate ---

        val RESTING_BPM = intPreferencesKey("resting_bpm")
        val MAX_BPM = intPreferencesKey("max_bpm")

        // --- Sync ---

        val SYNC_RETRY_MINUTES = intPreferencesKey("sync_retry_minutes")

        /** Whether the old per-screen export defaults have been folded into a preset. */
        val EXPORT_DEFAULTS_MIGRATED = booleanPreferencesKey("export_defaults_migrated")

        /**
         * Which revision of the shipped preset list this install has been offered.
         *
         * Seeding only runs against an empty table, so every install that already has presets
         * would never see a built-in added later. Held as a revision rather than by comparing
         * names, because a name check cannot tell "never had it" from "deleted it on purpose" —
         * and resurrecting a preset someone deleted on every launch makes it undeletable.
         */
        val BUILT_IN_PRESET_REVISION = androidx.datastore.preferences.core.intPreferencesKey(
            "built_in_preset_revision"
        )
    }

    /**
     * The library view last used, so the app reopens where it was left.
     *
     * Stored as the enum's name rather than its ordinal: reordering or inserting a mode later would
     * silently reassign everyone's saved choice to a different view.
     */
    val libraryViewMode: Flow<String> = dataStore.data
        .map { it[PreferencesKeys.LIBRARY_VIEW_MODE] ?: "RECORDINGS" }

    suspend fun setLibraryViewMode(mode: String) {
        dataStore.edit { it[PreferencesKeys.LIBRARY_VIEW_MODE] = mode }
    }

    /**
     * Whether the one-time conversion of saved same-time analyses into events has run.
     *
     * A preference rather than a schema version because the conversion is not a schema change and
     * must be allowed to fail and retry. A Room migration gets one attempt, and failing it means an
     * app that will not open.
     */
    suspend fun hasConvertedConcurrentAnalyses(): Boolean =
        dataStore.data.first()[PreferencesKeys.CONCURRENT_ANALYSES_CONVERTED] ?: false

    suspend fun setConvertedConcurrentAnalyses() {
        dataStore.edit { it[PreferencesKeys.CONCURRENT_ANALYSES_CONVERTED] = true }
    }

    /**
     * Recordings that should never be suggested as an event again.
     *
     * Stored by record id rather than by cluster, because a cluster has no identity — one more
     * recording arriving from a watch changes its membership and it would come back as a new
     * suggestion the user has to dismiss all over again. The recordings are the thing the user
     * actually said no about.
     */
    val dismissedSuggestionRecords: Flow<Set<Long>> = dataStore.data
        .map { prefs ->
            prefs[PreferencesKeys.DISMISSED_SUGGESTION_RECORDS]
                .orEmpty()
                .mapNotNull { it.toLongOrNull() }
                .toSet()
        }

    /**
     * The export appearance last used, as a serialized preset.
     *
     * Distinct from a default preset: a default is something someone chose to pre-select, this is
     * simply where they left off. The default wins when there is one, because it was a decision
     * and this is a side effect.
     */
    suspend fun lastUsedExportPreset(): String? =
        dataStore.data.first()[PreferencesKeys.LAST_USED_EXPORT_PRESET]

    suspend fun setLastUsedExportPreset(json: String) {
        dataStore.edit { it[PreferencesKeys.LAST_USED_EXPORT_PRESET] = json }
    }

    suspend fun dismissSuggestionRecords(recordIds: Set<Long>) {
        dataStore.edit { prefs ->
            val existing = prefs[PreferencesKeys.DISMISSED_SUGGESTION_RECORDS].orEmpty()
            prefs[PreferencesKeys.DISMISSED_SUGGESTION_RECORDS] =
                existing + recordIds.map { it.toString() }
        }
    }

    /**
     * The ID of the category currently used for auto-naming new records.
     */
    /**
     * Every stored preference, as key/type/value triples.
     *
     * Walks the DataStore rather than listing the keys by hand, so a setting added later is carried
     * by a backup without anyone remembering to add it here — the failure mode of an explicit list
     * is that it silently stops being complete.
     *
     * The type travels with the value because DataStore keys are typed and JSON is not: a float
     * `100f` and a long `100` are indistinguishable once serialized, and restoring one as the other
     * throws at read time.
     */
    suspend fun exportPreferences(): List<PreferenceSnapshot> =
        dataStore.data.first().asMap().mapNotNull { (key, value) ->
            val type = when (value) {
                is String -> "string"
                is Boolean -> "boolean"
                is Float -> "float"
                is Long -> "long"
                is Int -> "int"
                is Double -> "double"
                // Sets and anything else are skipped rather than guessed at.
                else -> return@mapNotNull null
            }
            PreferenceSnapshot(key.name, type, value.toString())
        }

    /**
     * Restores preferences captured by [exportPreferences].
     *
     * An unrecognised type or unparseable value is skipped, not fatal — a backup written by a newer
     * build should restore everything it can rather than nothing.
     *
     * @return how many were applied.
     */
    suspend fun importPreferences(snapshots: List<PreferenceSnapshot>): Int {
        var applied = 0
        dataStore.edit { prefs ->
            snapshots.forEach { snapshot ->
                val ok = runCatching {
                    when (snapshot.type) {
                        "string" -> prefs[stringPreferencesKey(snapshot.key)] = snapshot.value
                        "boolean" -> prefs[booleanPreferencesKey(snapshot.key)] = snapshot.value.toBooleanStrict()
                        "float" -> prefs[floatPreferencesKey(snapshot.key)] = snapshot.value.toFloat()
                        "long" -> prefs[longPreferencesKey(snapshot.key)] = snapshot.value.toLong()
                        "int" -> prefs[intPreferencesKey(snapshot.key)] = snapshot.value.toInt()
                        "double" -> prefs[doublePreferencesKey(snapshot.key)] = snapshot.value.toDouble()
                        else -> throw IllegalArgumentException("unknown type ${snapshot.type}")
                    }
                }.isSuccess
                if (ok) applied++
            }
        }
        return applied
    }

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

    // The per-screen export defaults that used to live here are gone: presets replaced them, and
    // the settings screen no longer offers them. Their *keys* survive in PreferencesKeys because
    // `legacyExportDefaultsAsPreset` still reads them once, to rescue anything a user had set
    // before rather than dropping it silently.

    val defaultTimeZone: Flow<String> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.DEFAULT_TIME_ZONE] ?: java.time.ZoneId.systemDefault().id
    }

    suspend fun setDefaultTimeZone(timeZoneId: String) {
        dataStore.edit { it[PreferencesKeys.DEFAULT_TIME_ZONE] = timeZoneId }
    }

    // --- Appearance ---

    /**
     * Whether to take colours from the wallpaper.
     *
     * Off by default. It used to be on, unconditionally and with no way to change it, which meant
     * the app had no appearance of its own on any phone new enough to support wallpaper colours.
     */
    val dynamicColour: Flow<Boolean> =
        dataStore.data.map { it[PreferencesKeys.DYNAMIC_COLOUR] ?: false }

    suspend fun setDynamicColour(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.DYNAMIC_COLOUR] = enabled }
    }

    val use24Hour: Flow<Boolean> =
        dataStore.data.map { it[PreferencesKeys.USE_24_HOUR] ?: false }

    suspend fun setUse24Hour(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.USE_24_HOUR] = enabled }
    }

    val dateFormat: Flow<String> =
        dataStore.data.map { it[PreferencesKeys.DATE_FORMAT] ?: DateFormats.DEFAULT }

    suspend fun setDateFormat(pattern: String) {
        dataStore.edit { it[PreferencesKeys.DATE_FORMAT] = pattern }
    }

    // --- Library ---

    val defaultSort: Flow<String?> = dataStore.data.map { it[PreferencesKeys.DEFAULT_SORT] }

    suspend fun setDefaultSort(sort: String) {
        dataStore.edit { it[PreferencesKeys.DEFAULT_SORT] = sort }
    }

    // --- Heart rate ---

    /**
     * The rate to treat as resting when nothing more specific is known.
     *
     * A fallback, not a fact: it belongs on the person, and this is what a person with no figure of
     * their own is measured against. See `PersonEntity.restingBpm`.
     */
    val restingBpm: Flow<Int> =
        dataStore.data.map { it[PreferencesKeys.RESTING_BPM] ?: DEFAULT_RESTING_BPM }

    suspend fun setRestingBpm(bpm: Int) {
        dataStore.edit { it[PreferencesKeys.RESTING_BPM] = bpm.coerceIn(30, 120) }
    }

    val maxBpm: Flow<Int> =
        dataStore.data.map { it[PreferencesKeys.MAX_BPM] ?: DEFAULT_MAX_BPM }

    suspend fun setMaxBpm(bpm: Int) {
        dataStore.edit { it[PreferencesKeys.MAX_BPM] = bpm.coerceIn(120, 230) }
    }

    // --- Sync ---

    val syncRetryMinutes: Flow<Int> =
        dataStore.data.map { it[PreferencesKeys.SYNC_RETRY_MINUTES] ?: 15 }

    suspend fun setSyncRetryMinutes(minutes: Int) {
        dataStore.edit { it[PreferencesKeys.SYNC_RETRY_MINUTES] = minutes.coerceIn(1, 240) }
    }

    // --- Retiring the old export defaults ---

    /**
     * The export defaults the settings screen used to hold, as an [ExportPreset].
     *
     * Presets replaced these, but a user who had spent time on them should not simply find them
     * gone — so they are folded into a preset once, named so it is obvious where it came from, and
     * only then are the keys abandoned.
     *
     * @return the preset to save, or null when there is nothing worth carrying: either it has been
     *   done already, or the user never changed a default in the first place.
     */
    suspend fun legacyExportDefaultsAsPreset(): inga.bpmetrics.export.ExportPreset? {
        val prefs = dataStore.data.first()
        if (prefs[PreferencesKeys.EXPORT_DEFAULTS_MIGRATED] == true) return null

        // Only the keys that were actually written. Untouched defaults would produce a preset
        // identical to the shipped one, which is clutter rather than rescue.
        val touched = listOf(
            PreferencesKeys.VID_WIDTH, PreferencesKeys.VID_HEIGHT,
            PreferencesKeys.VID_WINDOW_SIZE, PreferencesKeys.VID_FRAME_RATE,
            PreferencesKeys.VID_OPACITY, PreferencesKeys.VID_SHOW_LABELS,
            PreferencesKeys.VID_SHOW_GRID, PreferencesKeys.VID_SHOW_TITLE,
            PreferencesKeys.VID_SHOW_STATS, PreferencesKeys.VID_LOCK_ASPECT
        ).any { it in prefs }
        if (!touched) {
            markExportDefaultsMigrated()
            return null
        }

        val shipped = inga.bpmetrics.export.ExportPreset()
        return shipped.copy(
            name = "Previous defaults",
            width = prefs[PreferencesKeys.VID_WIDTH]?.toIntOrNull() ?: shipped.width,
            height = prefs[PreferencesKeys.VID_HEIGHT]?.toIntOrNull() ?: shipped.height,
            windowSizeMs = prefs[PreferencesKeys.VID_WINDOW_SIZE]?.toLongOrNull()?.times(1000L)
                ?: shipped.windowSizeMs,
            frameRate = prefs[PreferencesKeys.VID_FRAME_RATE]?.toIntOrNull() ?: shipped.frameRate,
            backgroundOpacity = prefs[PreferencesKeys.VID_OPACITY]?.toInt()
                ?: shipped.backgroundOpacity,
            showLabels = prefs[PreferencesKeys.VID_SHOW_LABELS] ?: shipped.showLabels,
            showGrid = prefs[PreferencesKeys.VID_SHOW_GRID] ?: shipped.showGrid,
            showTitle = prefs[PreferencesKeys.VID_SHOW_TITLE] ?: shipped.showTitle,
            showCurrentStats = prefs[PreferencesKeys.VID_SHOW_STATS] ?: shipped.showCurrentStats,
            lockAspectRatio = prefs[PreferencesKeys.VID_LOCK_ASPECT] ?: shipped.lockAspectRatio,
            timeZoneId = prefs[PreferencesKeys.DEFAULT_TIME_ZONE] ?: shipped.timeZoneId
        )
    }

    suspend fun markExportDefaultsMigrated() {
        dataStore.edit { it[PreferencesKeys.EXPORT_DEFAULTS_MIGRATED] = true }
    }

    /** The newest revision of the shipped preset list this install has been offered. */
    suspend fun builtInPresetRevision(): Int =
        dataStore.data.first()[PreferencesKeys.BUILT_IN_PRESET_REVISION] ?: 0

    suspend fun setBuiltInPresetRevision(revision: Int) {
        dataStore.edit { it[PreferencesKeys.BUILT_IN_PRESET_REVISION] = revision }
    }

    companion object {
        const val DEFAULT_RESTING_BPM = 60
        const val DEFAULT_MAX_BPM = 190
    }
}

/** The date formats on offer, as patterns so adding one is a single line. */
object DateFormats {
    const val DEFAULT = "MM/dd/yyyy"

    val ALL: List<Pair<String, String>> = listOf(
        "MM/dd/yyyy" to "03/14/2026",
        "dd/MM/yyyy" to "14/03/2026",
        "yyyy-MM-dd" to "2026-03-14",
        "d MMM yyyy" to "14 Mar 2026",
        "EEE d MMM" to "Sat 14 Mar"
    )
}

/**
 * One stored preference, with enough type information to be restored as what it was.
 *
 * Lives outside [SettingsRepository] so the backup format can name it without importing the whole
 * settings layer.
 */
data class PreferenceSnapshot(
    val key: String,
    val type: String,
    val value: String
)
