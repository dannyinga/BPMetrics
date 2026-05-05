package inga.bpmetrics.ui.settings

import android.graphics.RectF
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.export.ImageExporter
import inga.bpmetrics.export.VideoExporter
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

    val allCategories: Flow<List<CategoryEntity>> = repository.getAllCategories()

    // Image Settings
    val imgWidth = settingsRepository.imgWidth.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1920")
    val imgHeight = settingsRepository.imgHeight.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1080")
    val imgOpacity = settingsRepository.imgOpacity.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 100f)
    val imgShowAxes = settingsRepository.imgShowAxes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val imgShowLabels = settingsRepository.imgShowLabels.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val imgShowGrid = settingsRepository.imgShowGrid.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val imgShowTitle = settingsRepository.imgShowTitle.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // Video Settings
    val vidWidth = settingsRepository.vidWidth.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1280")
    val vidHeight = settingsRepository.vidHeight.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "720")
    val vidWindowSize = settingsRepository.vidWindowSize.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "30")
    val vidOpacity = settingsRepository.vidOpacity.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 40f)
    val vidShowAxes = settingsRepository.vidShowAxes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val vidShowLabels = settingsRepository.vidShowLabels.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val vidShowGrid = settingsRepository.vidShowGrid.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val vidShowTitle = settingsRepository.vidShowTitle.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val vidShowStats = settingsRepository.vidShowStats.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val vidLockAspect = settingsRepository.vidLockAspect.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val vidSyncOffset = settingsRepository.vidSyncOffset.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val vidGraphRect = settingsRepository.vidGraphRect.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RectF(0f, 0f, 1f, 1f))

    // Refactored Save Methods using Config objects
    fun setImageDefaults(config: ImageExporter.ImageExportConfig) {
        viewModelScope.launch {
            settingsRepository.setImageDefaults(config)
        }
    }

    fun setVideoDefaults(config: VideoExporter.VideoExportConfig) {
        viewModelScope.launch {
            settingsRepository.setVideoDefaults(config)
        }
    }

    fun setDefaultNamingCategory(categoryId: Long) {
        viewModelScope.launch { settingsRepository.setDefaultNamingCategory(categoryId) }
    }

    fun clearDefaultNamingCategory() {
        viewModelScope.launch { settingsRepository.clearDefaultNamingCategory() }
    }

    class Factory(
        private val repository: LibraryRepository,
        private val settingsRepository: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                return SettingsViewModel(repository, settingsRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
