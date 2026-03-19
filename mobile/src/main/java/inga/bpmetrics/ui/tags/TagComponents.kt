package inga.bpmetrics.ui.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import inga.bpmetrics.library.CategoryEntity
import inga.bpmetrics.ui.detail.BpmRecordViewModel

/**
 * A dialog allowing the user to multi-select tags for a specific record.
 *
 * @param onDismiss Callback to dismiss the dialog.
 * @param onSave Callback when the user saves the selection.
 * @param viewModel The ViewModel to fetch categories and tags from.
 * @param initialSelectedTagIds The tags already assigned to the record.
 */
@Composable
fun TagSelectionDialog(
    onDismiss: () -> Unit,
    onSave: (List<Long>) -> Unit,
    viewModel: BpmRecordViewModel,
    initialSelectedTagIds: List<Long>
) {
    val categories by viewModel.getAllCategories().collectAsState(initial = emptyList())
    var selectedTagIds by remember { mutableStateOf(initialSelectedTagIds.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign Tags") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(categories) { category ->
                    Text(category.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    HorizontalDivider()

                    val tags by viewModel.getTagsByCategory(category.categoryId).collectAsState(initial = emptyList())
                    tags.forEach { tag ->
                        val isSelected = selectedTagIds.contains(tag.tagId)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedTagIds = if (isSelected) selectedTagIds - tag.tagId else selectedTagIds + tag.tagId
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = tag.name)
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
fun CategoryCard(category: CategoryEntity, viewModel: TagManagementViewModel) {
    val tags by viewModel.getTagsForCategory(category.categoryId).collectAsState(initial = emptyList())
    var showAddTagDialog by remember { mutableStateOf(false) }
    var tagName by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(category.name, style = MaterialTheme.typography.titleLarge)
                Row {
                    IconButton(onClick = { showAddTagDialog = true }) { Icon(Icons.Default.Add, null) }
                    IconButton(onClick = { viewModel.deleteCategory(category) }) { Icon(Icons.Default.Delete, null) }
                }
            }
            tags.forEach { tag ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(tag.name)
                    IconButton(onClick = { viewModel.deleteTag(tag) }) { Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }

    if (showAddTagDialog) {
        AlertDialog(
            onDismissRequest = { showAddTagDialog = false },
            title = { Text("Add Tag to ${category.name}") },
            text = { OutlinedTextField(value = tagName, onValueChange = { tagName = it }, label = { Text("Tag Name") }) },
            confirmButton = {
                TextButton(onClick = {
                    if (tagName.isNotBlank()) {
                        viewModel.createTag(tagName, category.categoryId)
                        tagName = ""
                        showAddTagDialog = false
                    }
                }) { Text("Add") }
            }
        )
    }
}
