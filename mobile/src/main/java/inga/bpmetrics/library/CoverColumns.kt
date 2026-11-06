package inga.bpmetrics.library

import androidx.room.ColumnInfo

/**
 * A cover as it's stored: the seven columns recordings, events and collections each carry.
 *
 * Embedded in all three entities (Model and Export Consolidation §1), where the same seven fields
 * used to be written out three times. The column names are spelled out so the tables don't change:
 * Room would otherwise prefix nothing and name them after these properties, and the exported schema
 * has to stay byte-for-byte the same.
 *
 * Every column is nullable, as it always was. [Cover] is the domain value, with defaults and a
 * required path; embedding it directly would have made the crop columns `NOT NULL` and needed a
 * migration. [toCover] is the one door between the two.
 */
data class CoverColumns(
    @ColumnInfo(name = "coverPath", defaultValue = "NULL") val path: String? = null,
    @ColumnInfo(name = "coverCropLeft", defaultValue = "NULL") val cropLeft: Float? = null,
    @ColumnInfo(name = "coverCropTop", defaultValue = "NULL") val cropTop: Float? = null,
    @ColumnInfo(name = "coverCropRight", defaultValue = "NULL") val cropRight: Float? = null,
    @ColumnInfo(name = "coverCropBottom", defaultValue = "NULL") val cropBottom: Float? = null,
    /** See [Cover.blur]. For covers that are themselves made of type, like an event flyer. */
    @ColumnInfo(name = "coverBlur", defaultValue = "NULL") val blur: Float? = null,
    /** How far to darken it. See [Cover.dim]. */
    @ColumnInfo(name = "coverDim", defaultValue = "NULL") val dim: Float? = null
) {
    /** The cover these columns hold, or null when there's no picture. */
    fun toCover(): Cover? = Cover.of(path, cropLeft, cropTop, cropRight, cropBottom, blur, dim)

    companion object {
        /** Columns for [cover], or empty ones for none. */
        fun of(cover: Cover?): CoverColumns = cover?.let {
            CoverColumns(it.path, it.cropLeft, it.cropTop, it.cropRight, it.cropBottom, it.blur, it.dim)
        } ?: CoverColumns()
    }
}
