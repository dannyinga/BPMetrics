package inga.bpmetrics.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a specific tag within a category (e.g., "Spiderman" under "Character").
 * 
 * @property tagId Unique identifier for the tag.
 * @property name The name of the tag.
 * @property parentCategoryId The ID of the category this tag belongs to.
 */
@Entity(
    tableName = "tags",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["categoryId"],
            childColumns = ["parentCategoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["parentCategoryId"])]
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val tagId: Long = 0,
    val name: String,
    val parentCategoryId: Long
)
