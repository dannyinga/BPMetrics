package inga.bpmetrics.export

import android.graphics.Paint
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.BpmRecordWithPoints
import inga.bpmetrics.library.PersonColors
import inga.bpmetrics.library.RecordNameFormatter
import java.time.ZoneId

/**
 * What both renderers have to agree on (Model and Export Consolidation §6).
 *
 * A video frame ([VideoFrameRenderer]) and a picture ([ImageRenderer]) of the same recordings
 * should share a vertical scale, gridlines, colours, names and clock. Each had its own: three
 * different BPM ranges (one of them for the single-recording video alone, which is why a render
 * didn't match its preview, Audit #27), two ellipsizers, two gap constants, two ways to read a
 * zone. The curve style, the axes and the header stay with each renderer, because those differ on
 * purpose: one is a moment in a video, the other a whole stretch at once.
 */
object ChartKit {

    /** Longer than this between readings and a curve breaks, or dashes, rather than bridging it. */
    const val GAP_MS: Long = BpmRecord.GAP_THRESHOLD_MS

    /**
     * The vertical scale for a set of readings.
     *
     * Padded so the curve doesn't touch the frame, at least 20 bpm tall so a flat recording still
     * reads as one, kept within 30–220, then widened out to whole gridline steps so the top and
     * bottom of the plot are gridlines. Widened, never narrowed: a graph that crops the highest
     * reading is worse than one with an untidy edge.
     */
    fun bpmRange(readings: Collection<Double>): ClosedFloatingPointRange<Double> {
        if (readings.isEmpty()) return 60.0..180.0
        var low = readings.min()
        var high = readings.max()
        if (high - low < MIN_SPAN) {
            val centre = (high + low) / 2.0
            low = centre - MIN_SPAN / 2.0
            high = centre + MIN_SPAN / 2.0
        }
        val pad = ((high - low) * 0.08).coerceAtLeast(2.0)
        return GraphAxes.snapBpmRange(
            (low - pad).coerceAtLeast(30.0),
            (high + pad).coerceAtMost(220.0)
        )
    }

    /** The gridline values inside [range], on round numbers. */
    fun gridLines(range: ClosedFloatingPointRange<Double>): List<Double> = GraphAxes.bpmGridLines(range)

    /**
     * The colour a recording is drawn in: its wearer's, as resolved by the caller, so a person looks
     * the same in an export as everywhere else in the app. The palette by position for a recording
     * nobody is assigned to.
     */
    fun colourFor(recordId: Long, index: Int, colours: Map<Long, Int>): Int =
        colours[recordId] ?: PersonColors.defaultFor(index)

    /**
     * Whose curve this is: the wearer, else the watch, else the recording's title when it says
     * something. "Untitled 4" over someone's heart rate is worse than "Unknown".
     */
    fun wearerLabel(record: BpmRecordWithPoints): String =
        record.metadata.wearerName.takeIf { it.isNotBlank() }
            ?: record.metadata.deviceId.takeIf { it.isNotBlank() }
            ?: record.metadata.title.takeIf { !RecordNameFormatter.isPlaceholder(it) }
            ?: "Unknown"

    /**
     * Each recording's label, told apart where two would read the same.
     *
     * Two recordings by one person (two watches on, or a split recording) got two identical labels,
     * and nothing said which curve was which (Audit §6 Export). A repeat adds what differs: the
     * watch if it does, else the recording's own title, else a number.
     */
    fun distinctLabels(records: List<BpmRecordWithPoints>): List<String> {
        val base = records.map(::wearerLabel)
        return records.mapIndexed { i, record ->
            val same = base.indices.filter { base[it] == base[i] }
            if (same.size < 2) return@mapIndexed base[i]
            val device = record.metadata.deviceId.takeIf { it.isNotBlank() }
            val title = record.metadata.title.takeIf { !RecordNameFormatter.isPlaceholder(it) }
            val detail = when {
                device != null && same.map { records[it].metadata.deviceId }.distinct().size == same.size &&
                    device != base[i] -> device
                title != null && same.map { records[it].metadata.title }.distinct().size == same.size -> title
                else -> "${same.indexOf(i) + 1}"
            }
            "${base[i]} · $detail"
        }
    }

    /** [text] cut with an ellipsis until it fits [maxWidth] at the paint's settings. */
    fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (maxWidth <= 0f) return ""
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.take(end) + "…") > maxWidth) end--
        return if (end <= 0) "" else text.take(end) + "…"
    }

    /** A paint for chart drawing: antialiased, with the app's tabular numerals. */
    fun paint(): Paint = Paint().apply {
        isAntiAlias = true
        fontFeatureSettings = inga.bpmetrics.ui.theme.MetricNumerals
    }

    /**
     * How much bigger than the 1080-pixel design everything is drawn: 1 on a 1080p canvas either
     * way round, and on a square one, which used to come out at half the size of the others.
     */
    fun scale(width: Float, height: Float): Float = minOf(width, height) / 1080f

    /** The zone [zoneId] names, or the phone's for one that doesn't parse. */
    fun zone(zoneId: String): ZoneId = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())

    /** The smallest vertical span a plot gets, in bpm. */
    private const val MIN_SPAN = 20.0
}
