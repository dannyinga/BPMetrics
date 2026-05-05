package inga.bpmetrics.ui.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import inga.bpmetrics.library.CategoryEntity
import inga.bpmetrics.library.TagEntity
import inga.bpmetrics.ui.record.BpmRecordViewModel
import inga.bpmetrics.ui.components.ExpandableSection
import inga.bpmetrics.ui.components.FlowRow

/**
 * A dialog allowing the user to multi-select tags for a specific record.
 *
 * @param onDismiss Callback to dismiss the dialog.
 * @param onSave Callback when the user saves the selection.
 * @param onManageTags Callback to navigate to the tag management screen.
 * @param viewModel The ViewModel to fetch categories and tags from.
 * @param initialSelectedTagIds The tags already assigned to the record.
 */
@Composable
fun TagSelectionDialog(
    onDismiss: () -> Unit,
    onSave: (List<Long>) -> Unit,
    onManageTags: () -> Unit,
    viewModel: BpmRecordViewModel,
    initialSelectedTagIds: List<Long>
) {
    val categories by viewModel.getAllCategories().collectAsState(initial = emptyList())
    var selectedTagIds by remember { mutableStateOf(initialSelectedTagIds.toSet()) }
    var expandedCategories by remember { mutableStateOf(emptySet<Long>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Assign Tags")
                TextButton(onClick = onManageTags) {
                    Text("Manage", fontSize = 14.sp)
                }
            }
        },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(categories) { category ->
                    val isExpanded = expandedCategories.contains(category.categoryId)
                    
                    ExpandableSection(
                        title = category.name,
                        isExpanded = isExpanded,
                        onToggle = {
                            expandedCategories = if (isExpanded) {
                                expandedCategories - category.categoryId
                            } else {
                                expandedCategories + category.categoryId
                            }
                        },
                        titleStyle = MaterialTheme.typography.titleMedium
                    ) {
                        val tags by viewModel.getTagsByCategory(category.categoryId).collectAsState(initial = emptyList())
                        Column {
                            tags.forEach { tag ->
                                val isSelected = selectedTagIds.contains(tag.tagId)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedTagIds = if (isSelected) selectedTagIds - tag.tagId else selectedTagIds + tag.tagId
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = tag.name, style = MaterialTheme.typography.bodyMedium)
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = {
                                            selectedTagIds = if (isSelected) selectedTagIds - tag.tagId else selectedTagIds + tag.tagId
                                        }
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selectedTagIds.toList()) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * A card representing a category and its tags in the management screen.
 */
@Composable
fun CategoryCard(category: CategoryEntity, viewModel: TagManagementViewModel, isEditing: Boolean) {
    val tags by viewModel.getTagsForCategory(category.categoryId).collectAsState(initial = emptyList())
    var isExpanded by remember { mutableStateOf(false) }
    
    var showAddTagDialog by remember { mutableStateOf(false) }
    var showRenameCategoryDialog by remember { mutableStateOf(false) }
    var tagToRename by remember { mutableStateOf<TagEntity?>(null) }
    var tagToDelete by remember { mutableStateOf<TagEntity?>(null) }
    var showDeleteCategoryConfirm by remember { mutableStateOf(false) }
    
    var inputName by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            ExpandableSection(
                title = category.name,
                isExpanded = isExpanded,
                onToggle = { isExpanded = !isExpanded },
                titleStyle = MaterialTheme.typography.titleLarge
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        tags.forEach { tag ->
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (isEditing) 0.8f else 0.5f),
                                tonalElevation = if (isEditing) 2.dp else 1.dp,
                                modifier = Modifier.clickable(enabled = isEditing) {
                                    tagToRename = tag
                                    inputName = tag.name
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = tag.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    if (isEditing) {
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            modifier = Modifier
                                                .size(18.dp)
                                                .clickable { tagToDelete = tag },
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    TextButton(
                        onClick = { showAddTagDialog = true },
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Text("Add Tag", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            
            if (isEditing) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { 
                        showRenameCategoryDialog = true 
                        inputName = category.name
                    }) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                        Text("Rename Category", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { showDeleteCategoryConfirm = true }) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Text("Delete Category", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (showAddTagDialog) {
        TagActionDialog(
            title = "Add Tag to ${category.name}",
            label = "Tag Name",
            initialValue = "",
            onDismiss = { showAddTagDialog = false },
            onConfirm = { 
                viewModel.createTag(it, category.categoryId)
                showAddTagDialog = false
            }
        )
    }

    if (showRenameCategoryDialog) {
        TagActionDialog(
            title = "Rename Category",
            label = "Category Name",
            initialValue = inputName,
            onDismiss = { showRenameCategoryDialog = false },
            onConfirm = { 
                viewModel.renameCategory(category, it)
                showRenameCategoryDialog = false
            }
        )
    }

    tagToRename?.let { tag ->
        TagActionDialog(
            title = "Rename Tag",
            label = "Tag Name",
            initialValue = inputName,
            onDismiss = { tagToRename = null },
            onConfirm = { 
                viewModel.renameTag(tag, it)
                tagToRename = null
            }
        )
    }

    tagToDelete?.let { tag ->
        DeleteConfirmDialog(
            title = "Delete Tag",
            message = "Are you sure you want to delete the tag \"${tag.name}\"? This will remove it from all recordings.",
            onDismiss = { tagToDelete = null },
            onConfirm = {
                viewModel.deleteTag(tag)
                tagToDelete = null
            }
        )
    }

    if (showDeleteCategoryConfirm) {
        DeleteConfirmDialog(
            title = "Delete Category",
            message = "Are you sure you want to delete the category \"${category.name}\"? This will also delete all tags within this category and remove them from all recordings.",
            onDismiss = { showDeleteCategoryConfirm = false },
            onConfirm = {
                viewModel.deleteCategory(category)
                showDeleteCategoryConfirm = false
            }
        )
    }
}

@Composable
private fun TagActionDialog(
    title: String,
    label: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { 
            OutlinedTextField(
                value = value, 
                onValueChange = { value = it }, 
                label = { Text(label) },
                singleLine = true
            ) 
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value) }) { 
                Text("Confirm") 
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun DeleteConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
