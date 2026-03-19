package inga.bpmetrics.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.library.CategoryEntity
import inga.bpmetrics.library.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for managing application settings, including the default naming category.
 */
class SettingsViewModel(
    private val repository: LibraryRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    /**
     * The ID of the category currently used for auto-naming new records.
     */
    val defaultNamingCategoryId: StateFlow<Long?> = settingsRepository.defaultNamingCategoryId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Flow of all available categories for the naming scheme selection.
     */
    val allCategories: Flow<List<CategoryEntity>> = repository.getAllCategories()

    /**
     * Updates the category used for auto-naming records.
     */
    fun setDefaultNamingCategory(categoryId: Long) {
        viewModelScope.launch {
            settingsRepository.setDefaultNamingCategory(categoryId)
        }
    }

    /**
     * Clears the default naming category, reverting to "Untitled".
     */
    fun clearDefaultNamingCategory() {
        viewModelScope.launch {
            settingsRepository.clearDefaultNamingCategory()
        }
    }

    /**
     * Factory class for creating instances of [SettingsViewModel].
     */
    class Factory(
        private val repository: LibraryRepository,
        private val settingsRepository: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(repository, settingsRepository) as T
        }
    }
}
