package inga.bpmetrics.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
    }

    /**
     * The ID of the category currently used for auto-naming new records.
     */
    val defaultNamingCategoryId: Flow<Long?> = dataStore.data
        .map { preferences -> preferences[PreferencesKeys.DEFAULT_NAMING_CATEGORY_ID] }

    /**
     * Updates the category used for auto-naming records.
     */
    suspend fun setDefaultNamingCategory(categoryId: Long) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEFAULT_NAMING_CATEGORY_ID] = categoryId
        }
    }

    /**
     * Clears the default naming category.
     */
    suspend fun clearDefaultNamingCategory() {
        dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.DEFAULT_NAMING_CATEGORY_ID)
        }
    }
}
