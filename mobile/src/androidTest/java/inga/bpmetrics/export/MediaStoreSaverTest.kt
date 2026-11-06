package inga.bpmetrics.export

import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Saving into the gallery, and leaving nothing behind when it can't. */
@RunWith(AndroidJUnit4::class)
class MediaStoreSaverTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver

    private fun countIncludingPending(prefix: String, collection: android.net.Uri): Int =
        resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            android.os.Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$prefix%"))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            },
            null
        )?.use { it.count } ?: 0

    @Test
    fun aPictureLandsInTheGallery() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val uri = MediaStoreSaver.saveImage(context, bitmap, "SaverTest Picture")
        assertNotNull(uri)
        try {
            assertEquals(1, countIncludingPending("SaverTest_Picture_", MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        } finally {
            resolver.delete(uri!!, null, null)
        }
    }

    @Test
    fun aVideoThatCantBeCopiedLeavesNothingBehind() {
        val missing = File(context.cacheDir, "not_there.mp4").also { it.delete() }
        val uri = MediaStoreSaver.saveVideo(context, missing, "SaverTest Missing")
        assertNull(uri)
        assertEquals(0, countIncludingPending("SaverTest_Missing_", MediaStore.Video.Media.EXTERNAL_CONTENT_URI))
    }
}
