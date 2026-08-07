package inga.bpmetrics.ui.export

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import inga.bpmetrics.ui.components.FlowRow

/**
 * Step 2, when the export is an image.
 *
 * A video export picks clips; an image has none to pick. What it asks instead is how the scope
 * becomes pictures — which only has a real answer for a group, where separate images are what gets
 * posted per set and one long timeline is what shows a whole day.
 */
@Composable
fun ImageContentsStep(
    plan: List<ImagePlanEntry>,
    grouping: ImageGrouping,
    onGroupingChange: (ImageGrouping) -> Unit,
    showGroupingChoice: Boolean,
    crop: ImageCrop,
    onCropChange: (ImageCrop) -> Unit,
    naturalSpan: Pair<Long, Long>?,
    timeZoneId: String,
    title: String?,
    onTitleChange: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (showGroupingChoice) {
            item {
                Column {
                    Text(
                        "How should this group become images?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ImageGrouping.entries.forEach { option ->
                            FilterChip(
                                selected = grouping == option,
                                onClick = { onGroupingChange(option) },
                                label = { Text(option.label) }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }

        item {
            if (plan.size == 1) {
                androidx.compose.material3.OutlinedTextField(
                    value = title.orEmpty(),
                    onValueChange = { onTitleChange(it.takeIf { typed -> typed.isNotBlank() }) },
                    label = { Text("Title") },
                    placeholder = { Text(plan.firstOrNull()?.label.orEmpty()) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    // Empty means "whatever this is called", which keeps following a rename. Typing
                    // the current name would freeze it, which is a different thing to ask for.
                    "Leave blank to use the name of what you are exporting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Each image is titled after its event.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        if (naturalSpan != null) {
            item {
                ClockCropFields(
                    crop = crop,
                    onCropChange = onCropChange,
                    naturalSpan = naturalSpan,
                    timeZoneId = timeZoneId
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        item {
            Text(
                if (plan.size == 1) "One image" else "${plan.size} images",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(plan, key = { it.label + it.eventId }) { entry ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(entry.label, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        buildString {
                            append(if (entry.records.size == 1) "1 recording" else "${entry.records.size} recordings")
                            if (entry.peopleCount > 1) append(" · ${entry.peopleCount} people")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Step 4, when the export is an image.
 *
 * Rendered here rather than queued. An image takes well under a second, and a queue would be a
 * second place to go looking for something that has already finished — so what this shows is the
 * finished picture, with somewhere to put it.
 *
 * The checkerboard behind the preview is not decoration: at low opacity the image is transparent,
 * and against a plain background there is no way to tell transparent from white.
 */
@Composable
fun ImageMakeStep(
    images: List<RenderedImage>?,
    onSaveAll: () -> Unit,
    onShareAll: () -> Unit,
    saved: Boolean,
    modifier: Modifier = Modifier
) {
    if (images == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (images.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Nothing to draw — the recordings in scope have no data points.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(32.dp)
            )
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(images, key = { it.label }) { rendered ->
                Column {
                    Text(
                        rendered.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    TransparencyBacked(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(
                                (rendered.bitmap.width.toFloat() /
                                    rendered.bitmap.height.toFloat()).coerceIn(0.4f, 3f)
                            )
                    ) {
                        Image(
                            bitmap = rendered.bitmap.asImageBitmap(),
                            contentDescription = rendered.label,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(onClick = onSaveAll, modifier = Modifier.weight(1f)) {
                Text(
                    when {
                        saved -> "Saved"
                        images.size == 1 -> "Save"
                        else -> "Save all ${images.size}"
                    }
                )
            }
            OutlinedButton(onClick = onShareAll, modifier = Modifier.weight(1f)) {
                Text("Share")
            }
        }
    }
}

/** A finished image, and what it is of. */
data class RenderedImage(val label: String, val bitmap: Bitmap)

/**
 * Step 3's preview, when the export is an image.
 *
 * There is no clip to scrub and no playhead to choose — an image is the whole timeline at once — so
 * this is simply the thing itself, redrawn whenever a setting changes. On the same checkerboard as
 * step 4, because opacity is one of the settings being judged and it cannot be judged against a
 * flat background.
 */
@Composable
fun ImageLookPreview(
    preset: inga.bpmetrics.export.ExportPreset,
    /** Drawn through exactly the call step 4 makes, so the preview cannot show a different picture. */
    render: () -> Bitmap?,
    revision: Any,
    modifier: Modifier = Modifier
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, preset, revision) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { render() }.getOrNull()
        }
    }

    val aspect = (preset.width.toFloat() / preset.height.toFloat().coerceAtLeast(1f))
        .coerceIn(0.4f, 2.5f)

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        // Capped like the video preview, so a 9:16 canvas does not push every setting off screen.
        val height = minOf(maxWidth / aspect, 240.dp)

        TransparencyBacked(
            modifier = Modifier
                .height(height)
                .width(height * aspect)
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Image preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}


/**
 * A checkerboard, so transparency reads as transparency.
 *
 * Every image tool does this and for the same reason: over a flat background, a fully transparent
 * PNG and a white one look identical, and the whole point of the opacity setting is the difference
 * between them.
 */
@Composable
private fun TransparencyBacked(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val light = MaterialTheme.colorScheme.surfaceVariant
    val dark = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(light)
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val square = 14.dp.toPx()
            var row = 0
            var y = 0f
            while (y < size.height) {
                var col = 0
                var x = 0f
                while (x < size.width) {
                    if ((row + col) % 2 == 0) {
                        drawRect(
                            color = dark,
                            topLeft = androidx.compose.ui.geometry.Offset(x, y),
                            size = androidx.compose.ui.geometry.Size(
                                minOf(square, size.width - x),
                                minOf(square, size.height - y)
                            )
                        )
                    }
                    x += square
                    col++
                }
                y += square
                row++
            }
        }
        content()
    }
}

/**
 * The clock window to export.
 *
 * Typed as wall-clock times, because that is how the question actually gets asked — "from when the
 * set started to when it finished", not "from fourteen minutes in". A group spanning two days is
 * exactly the case that needs a date beside the time, so both fields take one.
 *
 * Empty means the natural span, which is a different thing from typing the natural span: the
 * recordings underneath can change, and "I did not narrow this" should follow them.
 */
@Composable
private fun ClockCropFields(
    crop: ImageCrop,
    onCropChange: (ImageCrop) -> Unit,
    naturalSpan: Pair<Long, Long>,
    timeZoneId: String
) {
    val zone = remember(timeZoneId) {
        runCatching { java.time.ZoneId.of(timeZoneId) }.getOrDefault(java.time.ZoneId.systemDefault())
    }
    val multiDay = remember(naturalSpan, zone) {
        val from = java.time.Instant.ofEpochMilli(naturalSpan.first).atZone(zone).toLocalDate()
        val to = java.time.Instant.ofEpochMilli(naturalSpan.second).atZone(zone).toLocalDate()
        from != to
    }

    Column {
        Text(
            "Time range",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            if (multiDay) {
                "This spans more than one day, so include the date."
            } else {
                "Leave blank for the whole thing."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClockField(
                label = "From",
                value = crop.startWallClockMs,
                placeholder = naturalSpan.first,
                zone = zone,
                withDate = multiDay,
                modifier = Modifier.weight(1f),
                onValue = { onCropChange(crop.copy(startWallClockMs = it)) }
            )
            ClockField(
                label = "To",
                value = crop.endWallClockMs,
                placeholder = naturalSpan.second,
                zone = zone,
                withDate = multiDay,
                modifier = Modifier.weight(1f),
                onValue = { onCropChange(crop.copy(endWallClockMs = it)) }
            )
        }
        if (crop.isNarrowed) {
            androidx.compose.material3.TextButton(onClick = { onCropChange(ImageCrop()) }) {
                Text("Use the whole range")
            }
        }
    }
}

/**
 * One clock time, parsed as it is typed.
 *
 * Holds its own text: parsing on every keystroke and writing the parsed value back would fight the
 * user halfway through "09:1". What is typed stays typed, and only a complete time is committed.
 */
@Composable
private fun ClockField(
    label: String,
    value: Long?,
    placeholder: Long,
    zone: java.time.ZoneId,
    withDate: Boolean,
    modifier: Modifier = Modifier,
    onValue: (Long?) -> Unit
) {
    val pattern = if (withDate) "yyyy-MM-dd HH:mm" else "HH:mm"
    val formatter = remember(pattern) { java.time.format.DateTimeFormatter.ofPattern(pattern) }
    val shownDefault = remember(value, placeholder, pattern) {
        value?.let { formatter.format(java.time.Instant.ofEpochMilli(it).atZone(zone)) } ?: ""
    }
    var text by remember(shownDefault) { mutableStateOf(shownDefault) }

    androidx.compose.material3.OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            if (typed.isBlank()) {
                onValue(null)
                return@OutlinedTextField
            }
            // The day is taken from the placeholder when only a time was typed, so a single-day
            // export does not need the date spelled out to know which day it means.
            val parsed = runCatching {
                if (withDate) {
                    java.time.LocalDateTime.parse(typed.trim().replace(' ', 'T'))
                        .atZone(zone).toInstant().toEpochMilli()
                } else {
                    val day = java.time.Instant.ofEpochMilli(placeholder).atZone(zone).toLocalDate()
                    day.atTime(java.time.LocalTime.parse(typed.trim()))
                        .atZone(zone).toInstant().toEpochMilli()
                }
            }.getOrNull()
            if (parsed != null) onValue(parsed)
        },
        label = { Text(label) },
        placeholder = {
            Text(
                formatter.format(java.time.Instant.ofEpochMilli(placeholder).atZone(zone)),
                style = MaterialTheme.typography.bodySmall
            )
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = modifier
    )
}
