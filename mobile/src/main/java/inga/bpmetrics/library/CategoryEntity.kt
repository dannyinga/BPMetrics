package inga.bpmetrics.library

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a group for organizing tags (e.g., "Character", "Activity").
 * 
 * @property categoryId Unique identifier for the category.
 * @property name The display name of the category.
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val categoryId: Long = 0,
    val name: String
)
