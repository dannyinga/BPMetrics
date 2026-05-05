package inga.bpmetrics.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import inga.bpmetrics.export.ImageExporter
import inga.bpmetrics.export.VideoExporter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val categories by viewModel.allCategories.collectAsStateWithLifecycle(initialValue = emptyList())
    val defaultNamingCategoryId by viewModel.defaultNamingCategoryId.collectAsStateWithLifecycle()

    // Remote states from ViewModel
    val savedImgW by viewModel.imgWidth.collectAsStateWithLifecycle()
    val savedImgH by viewModel.imgHeight.collectAsStateWithLifecycle()
    val savedImgO by viewModel.imgOpacity.collectAsStateWithLifecycle()
    val savedImgAxes by viewModel.imgShowAxes.collectAsStateWithLifecycle()
    val savedImgLabels by viewModel.imgShowLabels.collectAsStateWithLifecycle()
    val savedImgGrid by viewModel.imgShowGrid.collectAsStateWithLifecycle()
    val savedImgTitle by viewModel.imgShowTitle.collectAsStateWithLifecycle()

    val savedVidW by viewModel.vidWidth.collectAsStateWithLifecycle()
    val savedVidH by viewModel.vidHeight.collectAsStateWithLifecycle()
    val savedVidWin by viewModel.vidWindowSize.collectAsStateWithLifecycle()
    val savedVidO by viewModel.vidOpacity.collectAsStateWithLifecycle()
    val savedVidAxes by viewModel.vidShowAxes.collectAsStateWithLifecycle()
    val savedVidLabels by viewModel.vidShowLabels.collectAsStateWithLifecycle()
    val savedVidGrid by viewModel.vidShowGrid.collectAsStateWithLifecycle()
    val savedVidTitle by viewModel.vidShowTitle.collectAsStateWithLifecycle()
    val savedVidStats by viewModel.vidShowStats.collectAsStateWithLifecycle()
    val savedVidLock by viewModel.vidLockAspect.collectAsStateWithLifecycle()
    val savedVidOffset by viewModel.vidSyncOffset.collectAsStateWithLifecycle()

    // Local states
    var imgW by remember(savedImgW) { mutableStateOf(savedImgW) }
    var imgH by remember(savedImgH) { mutableStateOf(savedImgH) }
    var imgO by remember(savedImgO) { mutableFloatStateOf(savedImgO) }
    var imgAxes by remember(savedImgAxes) { mutableStateOf(savedImgAxes) }
    var imgLabels by remember(savedImgLabels) { mutableStateOf(savedImgLabels) }
    var imgGrid by remember(savedImgGrid) { mutableStateOf(savedImgGrid) }
    var imgTitle by remember(savedImgTitle) { mutableStateOf(savedImgTitle) }

    var vidW by remember(savedVidW) { mutableStateOf(savedVidW) }
    var vidH by remember(savedVidH) { mutableStateOf(savedVidH) }
    var vidWin by remember(savedVidWin) { mutableStateOf(savedVidWin) }
    var vidO by remember(savedVidO) { mutableFloatStateOf(savedVidO) }
    var vidAxes by remember(savedVidAxes) { mutableStateOf(savedVidAxes) }
    var vidLabels by remember(savedVidLabels) { mutableStateOf(savedVidLabels) }
    var vidGrid by remember(savedVidGrid) { mutableStateOf(savedVidGrid) }
    var vidTitle by remember(savedVidTitle) { mutableStateOf(savedVidTitle) }
    var vidStats by remember(savedVidStats) { mutableStateOf(savedVidStats) }
    var vidLock by remember(savedVidLock) { mutableStateOf(savedVidLock) }
    var vidOffset by remember(savedVidOffset) { mutableStateOf(savedVidOffset.toString()) }

    var showCategoryDropdown by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    val hasUnsavedChanges = imgW != savedImgW || imgH != savedImgH || imgO != savedImgO ||
            imgAxes != savedImgAxes || imgLabels != savedImgLabels || imgGrid != savedImgGrid ||
            imgTitle != savedImgTitle || vidW != savedVidW || vidH != savedVidH ||
            vidWin != savedVidWin || vidO != savedVidO || vidAxes != savedVidAxes ||
            vidLabels != savedVidLabels || vidGrid != savedVidGrid || vidTitle != savedVidTitle ||
            vidStats != savedVidStats || vidLock != savedVidLock || vidOffset != savedVidOffset.toString()

    val saveSettings = {
        val imageConfig = ImageExporter.ImageExportConfig(
            width = imgW.toIntOrNull() ?: 1920,
            height = imgH.toIntOrNull() ?: 1080,
            backgroundOpacity = imgO.toInt(),
            showAxes = imgAxes,
            showGrid = imgGrid,
            showLabels = imgLabels,
            showTitle = imgTitle,
        )
        val videoConfig = VideoExporter.VideoExportConfig(
            windowSizeMs = (vidWin.toLongOrNull() ?: 30L) * 1000L,
            syncOffsetMs = vidOffset.toLongOrNull() ?: 0L,
            imageConfig = imageConfig.copy(
                width = vidW.toIntOrNull() ?: 1280,
                height = vidH.toIntOrNull() ?: 720,
                backgroundOpacity = vidO.toInt(),
                showAxes = vidAxes,
                showLabels = vidLabels,
                showGrid = vidGrid,
                showTitle = vidTitle,
                showCurrentStats = vidStats
            ),
            lockAspectRatio = vidLock
        )
        viewModel.setImageDefaults(imageConfig)
        viewModel.setVideoDefaults(videoConfig)
        Toast.makeText(context, "Settings Saved", Toast.LENGTH_SHORT).show()
    }

    BackHandler(enabled = hasUnsavedChanges) { showUnsavedDialog = true }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("Unsaved Changes") },
            text = { Text("Would you like to save your changes before leaving?") },
            confirmButton = {
                TextButton(onClick = {
                    saveSettings()
                    showUnsavedDialog = false
                    onBack()
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        onBack()
                    }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { showUnsavedDialog = false }) { Text("Cancel") }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { if (hasUnsavedChanges) showUnsavedDialog = true else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    IconButton(onClick = { saveSettings() }) {
                        Icon(Icons.Default.Save, contentDescription = "Save Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).padding(16.dp).verticalScroll(rememberScrollState())) {
            SettingsSectionTitle("Auto-Naming")
            val selectedCategoryName = categories.find { it.categoryId == defaultNamingCategoryId }?.name ?: "None (Untitled)"
            OutlinedCard(modifier = Modifier.fillMaxWidth().clickable { showCategoryDropdown = true }) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Naming Category", style = MaterialTheme.typography.labelLarge)
                        Text(selectedCategoryName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
            DropdownMenu(expanded = showCategoryDropdown, onDismissRequest = { showCategoryDropdown = false }) {
                DropdownMenuItem(text = { Text("None (Untitled)") }, onClick = { viewModel.clearDefaultNamingCategory(); showCategoryDropdown = false })
                categories.forEach { category ->
                    DropdownMenuItem(text = { Text(category.name) }, onClick = { viewModel.setDefaultNamingCategory(category.categoryId); showCategoryDropdown = false })
                }
            }
            SettingsDivider()
            SettingsSectionTitle("Image Export Defaults")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = imgW, onValueChange = { imgW = it }, label = { Text("Width") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = imgH, onValueChange = { imgH = it }, label = { Text("Height") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            SettingsToggle("Show Title", imgTitle) { imgTitle = it }
            SettingsToggle("Show Axes", imgAxes) { imgAxes = it }
            SettingsToggle("Show Labels", imgLabels) { imgLabels = it }
            SettingsToggle("Show Grid", imgGrid) { imgGrid = it }
            Text("Background Opacity: ${imgO.toInt()}%", modifier = Modifier.padding(top = 8.dp))
            Slider(value = imgO, onValueChange = { imgO = it }, valueRange = 0f..100f)
            SettingsDivider()
            SettingsSectionTitle("Video Export Defaults")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = vidW, onValueChange = { vidW = it }, label = { Text("Width") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = vidH, onValueChange = { vidH = it }, label = { Text("Height") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            OutlinedTextField(value = vidWin, onValueChange = { vidWin = it }, label = { Text("Window Size (Sec)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(value = vidOffset, onValueChange = { vidOffset = it }, label = { Text("Global Sync Offset (ms)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            SettingsToggle("Lock Aspect Ratio", vidLock) { vidLock = it }
            SettingsToggle("Show Title", vidTitle) { vidTitle = it }
            SettingsToggle("Show Stats", vidStats) { vidStats = it }
            SettingsToggle("Show Axes", vidAxes) { vidAxes = it }
            SettingsToggle("Show Labels", vidLabels) { vidLabels = it }
            SettingsToggle("Show Grid", vidGrid) { vidGrid = it }
            Text("Background Opacity: ${vidO.toInt()}%", modifier = Modifier.padding(top = 8.dp))
            Slider(value = vidO, onValueChange = { vidO = it }, valueRange = 0f..100f)
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun SettingsToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}