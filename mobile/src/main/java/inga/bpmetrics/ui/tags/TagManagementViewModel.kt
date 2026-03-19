package inga.bpmetrics.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.library.CategoryEntity
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.TagEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for managing categories and tags.
 *
 * This ViewModel handles the logic for creating and deleting categories and tags,
 * and provides the current state of categories and their associated tags to the UI.
 *
 * @property repository The [LibraryRepository] used to interact with the database.
 */
class TagManagementViewModel(private val repository: LibraryRepository) : ViewModel() {

    /**
     * UI state representing the categories and their associated tags.
     */
    val uiState: StateFlow<TagManagementUIState> = repository.getAllCategories()
        .combine(repository.records) { categories, _ ->
            // In a real app, we might want to fetch tags for each category more efficiently,
            // but for now, we'll provide the categories. The UI will handle fetching tags per category.
            TagManagementUIState(categories = categories)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TagManagementUIState()
        )

    /**
     * Creates a new category.
     *
     * @param name The name of the new category.
     */
    fun createCategory(name: String) {
        viewModelScope.launch {
            repository.createCategory(name)
        }
    }

    /**
     * Deletes an existing category.
     *
     * @param category The category to delete.
     */
    fun deleteCategory(category: CategoryEntity) {
        viewModelScope.launch {
            repository.deleteCategory(category)
        }
    }

    /**
     * Creates a new tag within a category.
     *
     * @param name The name of the new tag.
     * @param categoryId The ID of the parent category.
     */
    fun createTag(name: String, categoryId: Long) {
        viewModelScope.launch {
            repository.createTag(name, categoryId)
        }
    }

    /**
     * Deletes an existing tag.
     *
     * @param tag The tag to delete.
     */
    fun deleteTag(tag: TagEntity) {
        viewModelScope.launch {
            repository.deleteTag(tag)
        }
    }
    
    /**
     * Returns a flow of tags for a specific category.
     * 
     * @param categoryId The ID of the category.
     */
    fun getTagsForCategory(categoryId: Long) = repository.getTagsByCategory(categoryId)

    /**
     * Factory for creating [TagManagementViewModel] with a [LibraryRepository].
     */
    class Factory(
        private val repository: LibraryRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(TagManagementViewModel::class.java)) {
                return TagManagementViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

/**
 * Data class representing the UI state of the tag management screen.
 *
 * @property categories The list of all available categories.
 * @property isLoading Whether the data is currently being loaded.
 */
data class TagManagementUIState(
    val categories: List<CategoryEntity> = emptyList(),
    val isLoading: Boolean = false
)
