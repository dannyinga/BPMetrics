package inga.bpmetrics.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.PersonColors
import inga.bpmetrics.library.PersonEntity

/**
 * A filled circle in someone's colour, for putting a face to a name in a list.
 */
@Composable
fun PersonSwatch(colorArgb: Int, size: Int = 16, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size.dp)
            .background(Color(colorArgb), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
    )
}

/**
 * Picks who is wearing a watch, from the people already known.
 *
 * A dropdown rather than a text field on purpose: typing a name meant the same person could be
 * entered three different ways and become three different entries in every filter and grouping.
 *
 * @param people Everyone available to choose from.
 * @param selectedId Who is currently chosen, or null for nobody.
 * @param onSelect Reports the choice. Null means nobody.
 * @param onAddPerson Offered inline so assigning a watch to someone new does not mean abandoning
 *   whatever dialog this sits in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonPicker(
    people: List<PersonEntity>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Person",
    onAddPerson: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = people.firstOrNull { it.personId == selectedId }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.displayName ?: "Nobody",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            leadingIcon = selected?.let { person ->
                { PersonSwatch(person.colorArgb) }
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
        )

        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Nobody") },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            people.forEach { person ->
                DropdownMenuItem(
                    text = { Text(person.displayName) },
                    leadingIcon = { PersonSwatch(person.colorArgb) },
                    onClick = {
                        onSelect(person.personId)
                        expanded = false
                    }
                )
            }
            onAddPerson?.let { add ->
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Add someone new…") },
                    onClick = {
                        expanded = false
                        add()
                    }
                )
            }
        }
    }
}

/**
 * Chooses a colour: the palette for speed, sliders for anything else.
 *
 * The palette alone would not do — several friends at one event need telling apart at a glance, and
 * eight fixed choices runs out. The sliders alone would be tedious for the common case of "just
 * give me a different one".
 */
@Composable
fun ColorPicker(
    colorArgb: Int,
    onColorChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val color = Color(colorArgb)
    var showSliders by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color, MaterialTheme.shapes.small)
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Colour", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(
                    text = "#%06X".format(colorArgb and 0xFFFFFF),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PersonColors.PALETTE.forEach { option ->
                val isSelected = option == colorArgb
                Box(
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .size(28.dp)
                        .background(Color(option), CircleShape)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape
                        )
                        .clickable { onColorChange(option) }
                )
            }
        }

        Text(
            text = if (showSliders) "Hide custom colour" else "Custom colour…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { showSliders = !showSliders }
                .padding(vertical = 4.dp)
        )

        if (showSliders) {
            // Alpha is held at full rather than exposed. A translucent curve on a chart or a
            // half-faded stripe in the library reads as a rendering fault, not a choice.
            ChannelSlider("R", (colorArgb shr 16) and 0xFF) { onColorChange(withChannel(colorArgb, 16, it)) }
            ChannelSlider("G", (colorArgb shr 8) and 0xFF) { onColorChange(withChannel(colorArgb, 8, it)) }
            ChannelSlider("B", colorArgb and 0xFF) { onColorChange(withChannel(colorArgb, 0, it)) }
        }
    }
}

@Composable
private fun ChannelSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(20.dp)
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(32.dp)
        )
    }
}

/** Replaces one 8-bit channel, keeping the colour fully opaque. */
private fun withChannel(argb: Int, shift: Int, value: Int): Int {
    val cleared = argb and (0xFF shl shift).inv()
    return (cleared or (value.coerceIn(0, 255) shl shift)) or 0xFF000000.toInt()
}

/** The colour as Compose sees it, for callers holding a raw ARGB int. */
fun Int.asComposeColor(): Color = Color(this)

/** The raw ARGB int, for callers holding a Compose colour. */
fun Color.asArgbInt(): Int = toArgb()
