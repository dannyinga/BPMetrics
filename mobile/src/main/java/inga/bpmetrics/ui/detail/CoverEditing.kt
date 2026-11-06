package inga.bpmetrics.ui.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import inga.bpmetrics.library.Cover
import inga.bpmetrics.ui.components.CoverCropDialog
import inga.bpmetrics.ui.components.rememberCoverPicker
import inga.bpmetrics.ui.feedback.Feedback

/**
 * Picking, framing and clearing a detail page's cover: the one flow for all three pages (S15.2).
 *
 * Each page had its own copy of the picker, the crop dialog and the clear, differing only in which
 * item they named and which view model they went through. Opened from the corner of the cover on
 * the header (see [SubjectHeader]); [framing] is the page's, so that corner can open it.
 *
 * @param ownCover the item's own picture, not one it inherits: framing changes this one.
 * @param previewName drawn over the crop preview, so the crop is judged against the words that
 *   will sit on it. Null for a recording, whose header leads with its own title line.
 */
@Composable
fun CoverEditing(
    viewModel: ItemDetailViewModel,
    ownCover: Cover?,
    title: String,
    previewName: String?,
    framing: Boolean,
    onFraming: (Boolean) -> Unit
) {
    val context = LocalContext.current
    // Registered for the page rather than the dialog: a launcher outlives what starts it.
    val pick = rememberCoverPicker { uri ->
        viewModel.setCover(context, uri) { stored ->
            // Straight into framing once it lands, while the choice is fresh.
            if (stored) onFraming(true) else Feedback.failed("That image couldn't be read.")
        }
    }

    if (!framing) return
    CoverCropDialog(
        cover = ownCover,
        onPick = pick,
        title = title,
        previewContent = {
            previewName?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
            }
        },
        onDismiss = { onFraming(false) },
        onConfirm = {
            viewModel.cropCover(it)
            onFraming(false)
        },
        // Stays open: removing is usually the first half of replacing.
        onRemove = { viewModel.clearCover(context) }
    )
}
