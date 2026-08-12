package inga.bpmetrics.ui.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import inga.bpmetrics.library.CategoryEntity
import inga.bpmetrics.library.EffectiveTag
import inga.bpmetrics.library.TagSource
import inga.bpmetrics.library.TagEntity
import inga.bpmetrics.ui.record.BpmRecordViewModel
import inga.bpmetrics.ui.components.ExpandableSection
import inga.bpmetrics.ui.components.FlowRow

import kotlinx.coroutines.flow.Flow
/**
 * Choosing a recording's tags, and making new ones without leaving.
 *
 * There is no management screen any more. Creating a tag was a trip to a separate part of the app,
 * done before you could label anything — which meant labelling happened rarely, and the comparison
 * features that depend on labels went unused. A tag is made where it is applied, and its axis is
 * chosen or created in the same gesture.
 *
 * **One tag per category.** Picking a second value on an axis replaces the first rather than adding
 * to it, matching what `LibraryRepository.addTagToRecord` does on write. A dialog that let you tick
 * both and then silently dropped one would be worse than the constraint.
 *
 * @param onCreateTag Makes a tag on an axis, creating the axis if it is new, and hands back its id.
 */
@Composable
fun TagSelectionDialog(
    onDismiss: () -> Unit,
    onSave: (List<Long>) -> Unit,
    categories: List<CategoryEntity>,
    getTagsByCategoryFlow: (Long) -> Flow<List<TagEntity>>,
    initialSelectedTagIds: List<Long>,
    onCreateTag: (categoryName: String, tagName: String, onMade: (Long) -> Unit) -> Unit =
        { _, _, _ -> }
) {
    var selectedTagIds by remember { mutableStateOf(initialSelectedTagIds.toSet()) }
    var expandedCategories by remember { mutableStateOf(emptySet<Long>()) }
    var creatingIn by remember { mutableStateOf<CategoryEntity?>(null) }
    var creatingNewAxis by remember { mutableStateOf(false) }

    // Which tags belong to which axis, gathered as the sections open. Needed because choosing a
    // value has to clear the others on its own axis, and the dialog only learns an axis's values
    // once that section has been opened.
    val tagsByCategory = remember { mutableStateMapOf<Long, List<TagEntity>>() }

    fun choose(tag: TagEntity) {
        val siblings = tagsByCategory[tag.parentCategoryId].orEmpty().map { it.tagId }.toSet()
        selectedTagIds = if (tag.tagId in selectedTagIds) {
            selectedTagIds - tag.tagId
        } else {
            (selectedTagIds - siblings) + tag.tagId
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
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
                        val tags by getTagsByCategoryFlow(category.categoryId)
                            .collectAsState(initial = emptyList())
                        tagsByCategory[category.categoryId] = tags

                        Column {
                            tags.forEach { tag ->
                                val isSelected = selectedTagIds.contains(tag.tagId)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { choose(tag) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(tag.name, style = MaterialTheme.typography.bodyMedium)
                                    // A radio rather than a checkbox: one value per axis, said by
                                    // the control instead of by a correction afterwards.
                                    RadioButton(selected = isSelected, onClick = { choose(tag) })
                                }
                            }
                            TextButton(onClick = { creatingIn = category }) {
                                Text("New ${category.name.lowercase()}…", fontSize = 14.sp)
                            }
                        }
                    }
                    HorizontalDivider()
                }
                item {
                    TextButton(onClick = { creatingNewAxis = true }) {
                        Text("New category…", fontSize = 14.sp)
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

    creatingIn?.let { category ->
        NewTagDialog(
            axisName = category.name,
            axisFixed = true,
            onDismiss = { creatingIn = null },
            onConfirm = { _, tagName ->
                onCreateTag(category.name, tagName) { newId ->
                    // Selected straight away, clearing the axis as any other choice would.
                    val siblings = tagsByCategory[category.categoryId].orEmpty()
                        .map { it.tagId }
                        .toSet()
                    selectedTagIds = (selectedTagIds - siblings) + newId
                }
                expandedCategories = expandedCategories + category.categoryId
                creatingIn = null
            }
        )
    }

    if (creatingNewAxis) {
        NewTagDialog(
            axisName = "",
            axisFixed = false,
            onDismiss = { creatingNewAxis = false },
            onConfirm = { axis, tagName ->
                onCreateTag(axis, tagName) { newId -> selectedTagIds = selectedTagIds + newId }
                creatingNewAxis = false
            }
        )
    }
}

/**
 * Making a tag, and its axis if that is new too.
 *
 * The axis is mandatory and always visible, even when it is fixed. A tag with no axis cannot be
 * compared against anything, and an app where half the tags are uncomparable has given up the
 * feature tags exist for — so the field is shown rather than tucked behind a disclosure.
 */
@Composable
private fun NewTagDialog(
    axisName: String,
    axisFixed: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (axis: String, tag: String) -> Unit
) {
    var axis by remember { mutableStateOf(axisName) }
    var tag by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (axisFixed) "New $axisName" else "New tag") },
        text = {
            Column {
                OutlinedTextField(
                    value = axis,
                    onValueChange = { axis = it },
                    label = { Text("Category") },
                    placeholder = { Text("Character, Venue, Mode…") },
                    enabled = !axisFixed,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tag,
                    onValueChange = { tag = it },
                    label = { Text("Tag") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "A category is what this tag gets compared along — Spiderman against Hulk. " +
                        "A recording carries one tag per category.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = axis.isNotBlank() && tag.isNotBlank(),
                onClick = { onConfirm(axis.trim(), tag.trim()) }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * A tag on a recording, drawn to say where it came from.
 *
 * A direct tag is filled and can be removed here. An inherited one is outlined and cannot — the
 * recording is filed under an event in a group that carries it, and "remove this tag from this one
 * recording" has no meaning. Tapping an inherited chip says where it was applied instead, which is
 * where it can be removed.
 *
 * The distinction has to be visible, or the first thing anyone tries is the thing that cannot work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectiveTagChip(
    effective: EffectiveTag,
    onRemove: (() -> Unit)? = null,
    onExplain: ((String) -> Unit)? = null
) {
    val label = effective.tag.name

    if (effective.isInherited) {
        val from = when (effective.source) {
            TagSource.EVENT -> "this event"
            TagSource.ANCESTOR -> "an event above it"
            TagSource.DIRECT -> ""
        }
        AssistChip(
            onClick = { onExplain?.invoke("$label comes from $from — remove it there") },
            label = { Text(label) },
            leadingIcon = {
                Icon(
                    // A downward arrow rather than a tag glyph: what is being said is that the
                    // tag arrived from above, not that it is a tag.
                    Icons.AutoMirrored.Filled.CallReceived,
                    contentDescription = "Inherited from $from",
                    modifier = Modifier.size(16.dp)
                )
            },
            border = AssistChipDefaults.assistChipBorder(enabled = true)
        )
    } else {
        SuggestionChip(
            onClick = { onRemove?.invoke() },
            label = { Text(label) }
        )
    }
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
