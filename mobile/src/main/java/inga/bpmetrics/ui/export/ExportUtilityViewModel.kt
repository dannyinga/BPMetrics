package inga.bpmetrics.ui.export

import inga.bpmetrics.util.launchGuarded

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.BpmRecordWithPoints
import inga.bpmetrics.export.ExportPreset
import inga.bpmetrics.export.TimelineImageExporter
import inga.bpmetrics.export.VideoExporter
import inga.bpmetrics.ui.analysis.EventAnalysis
import inga.bpmetrics.library.ExportPresetEntity
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.displayName
import inga.bpmetrics.ui.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The four questions an export has to answer, in order.
 *
 * Steps rather than one screen of options because the questions are genuinely sequential: which
 * clips are on offer depends on the source, and what the settings are previewed against depends on
 * the clips. A single form cannot express that, which is why the current dialog asks everything at
 * once and answers nothing about what it is about to produce.
 */
enum class ExportStep(val title: String, val question: String) {
    SOURCE("Source", "What am I exporting?"),
    CONTENTS("Contents", "Which curves go on it?"),
    LOOK("Look", "How should it look?"),
    MAKE("Make", "Queue it");

    val number: Int get() = ordinal + 1
}

/**
 * Whether this export is a video or an image.
 *
 * Asked on **Contents**, at the top of the step it decides. It used to be asked on Source, on the
 * reasoning that step 2 needs to know before it can frame its question — true, but it made step 1
 * ask two unrelated things, and the source is the same set of recordings either way. What changes
 * between a video and an image is precisely the contents: a video picks which clips to draw on, an
 * image asks which recordings share a timeline.
 *
 * It also cost an extra dialog. Every entry point outside the utility had to ask "video or image?"
 * before it could navigate, which is a modal in the way of a question the flow was about to ask
 * anyway.
 */
enum class ExportKind(val label: String) {
    VIDEO("Video"),
    IMAGE("Image")
}

/**
 * How a group becomes images.
 *
 * The only source with a real choice in it, and both answers are wanted: separate images are what
 * gets posted per set, and one long timeline is what shows a whole festival day. So it is asked
 * rather than decided.
 */
enum class ImageGrouping(val label: String) {
    PER_EVENT("One image per event"),
    ONE_TIMELINE("Everything on one timeline")
}

/**
 * A clock window to export, or nothing for everything.
 *
 * Null means "wherever the recordings begin and end". Storing the absence rather than pre-filling
 * the real span keeps "I have not narrowed this" distinguishable from "I typed exactly the full
 * span", which matters when the recordings underneath change.
 */
data class ImageCrop(
    val startWallClockMs: Long? = null,
    val endWallClockMs: Long? = null
) {
    val isNarrowed: Boolean get() = startWallClockMs != null || endWallClockMs != null

    /** What's wrong with this window, or null. A window that ends before it starts draws nothing. */
    val problem: String?
        get() = if (startWallClockMs != null && endWallClockMs != null && startWallClockMs >= endWallClockMs) {
            "From has to be before To."
        } else null
}

/** The look an export starts with, and the saved preset it came from. */
internal data class StartingLook(val preset: ExportPreset, val presetId: Long?)

/**
 * Where a new export's look comes from: the last one used, and the default preset only when
 * there's no last one (Audit §3 Export).
 *
 * @param lastUsedFrom the saved preset the last-used look started from, if it still exists.
 */
internal fun startingLook(
    lastUsedJson: String?,
    lastUsedFrom: ExportPresetEntity?,
    default: ExportPresetEntity?
): StartingLook? {
    lastUsedJson?.let { ExportPreset.fromJson(it) }?.let {
        return StartingLook(it, lastUsedFrom?.presetId)
    }
    val fromDefault = default?.let { ExportPreset.fromJson(it.configJson) } ?: return null
    return StartingLook(fromDefault, default.presetId)
}

/**
 * Whether the look holds changes no preset has: it differs from the preset it started from, or,
 * with none, it has been changed by hand.
 */
internal fun isPresetEdited(selected: ExportPresetEntity?, current: ExportPreset, touched: Boolean): Boolean {
    val stored = selected?.let { ExportPreset.fromJson(it.configJson) } ?: return touched
    return stored != current
}

/** One image, and what goes on it. */
data class ImagePlanEntry(
    val label: String,
    val records: List<inga.bpmetrics.library.BpmRecordWithPoints>,
    val eventId: Long? = null
) {
    val peopleCount: Int get() = records.mapNotNull { it.metadata.personId }.distinct().size
        .takeIf { it > 0 } ?: records.size

    companion object {
        /**
         * Works out which images to draw, and what goes on each.
         *
         * Pure, and separate from the flow that feeds it, because the only interesting part is the
         * splitting: everything except a group split per event is one image, and getting *that* one
         * case wrong means either an image nobody asked for or a person silently missing from one.
         */
        fun plan(
            records: List<inga.bpmetrics.library.BpmRecordWithPoints>,
            events: List<inga.bpmetrics.library.EventEntity>,
            splitByEvent: Boolean,
            /** What the scope is called: the recording, the event, or the group. */
            scopeTitle: String
        ): List<ImagePlanEntry> {
            if (records.isEmpty()) return emptyList()
            // Named after what it is of. "Whole timeline" was a placeholder that described the
            // shape of the picture rather than its subject, which is no use as a caption.
            val whole = listOf(
                ImagePlanEntry(
                    label = scopeTitle.takeIf { it.isNotBlank() } ?: "Timeline",
                    records = records
                )
            )
            if (!splitByEvent) return whole

            val byEvent = records.groupBy { it.metadata.eventId }
            val named = events
                .filter { it.eventId in byEvent.keys }
                .map { event ->
                    ImagePlanEntry(
                        // The same rule as every other title — see [ExportTitle]. Six images out
                        // of one festival all called "Day 1" … "Day 6" say nothing about which
                        // festival once they are out of the app. No venue: it would be the same
                        // words on all six, and the scope they came from already carries it.
                        label = inga.bpmetrics.library.ExportTitle.of(
                            eventId = event.eventId,
                            events = events
                        ),
                        records = byEvent[event.eventId].orEmpty(),
                        eventId = event.eventId
                    )
                }
                // By when each event actually happened. An event has no start time of its own — it
                // is derived from what it holds — so the recordings are what order the evening.
                .sortedBy { entry ->
                    entry.records.minOfOrNull { it.metadata.startTime } ?: Long.MAX_VALUE
                }

            // Recordings belonging to no event are gathered rather than dropped: an image that
            // silently omits someone is worse than one more image.
            val unfiled = byEvent[null].orEmpty()
            val all = if (unfiled.isEmpty()) named else named + ImagePlanEntry("Unfiled", unfiled)
            return all.ifEmpty { whole }
        }
    }
}

/**
 * What the export is being taken from.
 *
 * A source is not the same as a set of recordings: it decides which video clips are on offer, and
 * it is what the finished export is named after. Recordings are what it resolves to.
 */
sealed interface ExportSource {
    data object None : ExportSource
    data class Recordings(val recordIds: Set<Long>) : ExportSource
    data class Event(val eventId: Long) : ExportSource
    data class Collection(val collectionId: Long) : ExportSource

    /** A frozen analysis, which names the export and pins which recordings it covers. */
    data class SavedAnalysis(val analysisId: Long) : ExportSource
}

/**
 * One clip and the curves that will go on it.
 *
 * The unit of a batch export. Selecting a group lists every clip filmed during any of its events;
 * each ticked one becomes its own job, with its own overlay. "One export per event" cannot say
 * that — an event is a concert, and during it you filmed six things.
 */
data class ClipSelection(
    val clip: VideoExporter.VideoClip,
    val recordIds: Set<Long>,
    val selected: Boolean,
    /**
     * Unticked by a time correction that put it where nobody was recording, rather than by hand.
     * Correcting it back ticks it again; it used to stay unticked while the preview still showed
     * it, so the render quietly left out a clip that looked chosen (Audit #19).
     */
    val untickedByTime: Boolean = false,
    /**
     * What everyone's heart rate did during this clip, for deciding whether it is worth overlaying.
     *
     * A filename and a timestamp say nothing about whether the moment was any good. A glance at the
     * shape does: a clip where three curves all climb is the one to export, and a flat one is not.
     */
    val sparks: List<ClipSpark> = emptyList(),
    /**
     * What the sparkline's floor and ceiling stand for, in bpm.
     *
     * Carried rather than left implicit because the curve is normalised against it. A plot drawn
     * floor-to-ceiling fills its box whatever the heart did, so without this the same mountain is
     * drawn for a clip spanning four beats and one spanning ninety — and "is this curve steep" is
     * exactly the question the sparkline exists to answer. Null until the curves have been walked.
     */
    val bpmScale: IntRange? = null,
    /**
     * Where the graph sits on this clip, as fractions of the canvas.
     *
     * Per clip rather than per batch: a graph that works over a wide shot covers someone's face in
     * a close-up. Null means it has not been placed yet and the shared default applies, which is
     * what makes "apply to all" meaningful — it fills these in rather than erasing a distinction
     * that was never made.
     */
    val graph: GraphPlacement? = null,
    /** Where this clip's preview is scrubbed to, 0..1. Its own, because each clip is its own shot. */
    val scrubAt: Float = 0f,
    /**
     * The clip as the file described it, kept while its filming time has been corrected by hand.
     *
     * [clip] carries the corrected time, so every consumer — the window on the timeline, who was
     * recording during it, the preview, the render — follows it without knowing a correction
     * happened. This is what lets "use the file's time" undo that. Null while nothing is corrected.
     */
    val originalClip: VideoExporter.VideoClip? = null
) {
    /** The highest anyone reached during the clip. Null when nobody was recording. */
    val peakBpm: Int? get() = sparks.maxOfOrNull { it.peakBpm }
}

/**
 * Which part of the graph frame a drag is holding.
 *
 * Edges as well as corners: pulling a frame narrower without also changing its height is the common
 * adjustment, and corner-only meant doing it twice and accepting whatever the second drag did to
 * the first.
 */
enum class GraphHandle {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    LEFT, RIGHT, TOP, BOTTOM
}

/** Where the graph sits on the canvas, in fractions so it survives an aspect change. */
data class GraphPlacement(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun toRectF(): android.graphics.RectF = android.graphics.RectF(left, top, right, bottom)

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** Writes this framing into a preset, so saving one carries what is on screen. */
    fun into(preset: ExportPreset): ExportPreset = preset.withFraming(left, top, right, bottom)

    /**
     * The same shape, resized to [newWidth] of the canvas, held on its own centre.
     *
     * One control for the whole rectangle. Getting a graph to the right shape by dragging four
     * corners and then wanting it smaller meant redoing all four, in step, without letting the
     * proportions drift — which is not a thing fingers are good at. This keeps the aspect exactly
     * and moves only the size.
     *
     * About the centre rather than the top-left, because a graph placed as a lower third is *there*
     * on purpose: shrinking it from a corner would walk it out of the band it was put in.
     */
    fun scaledTo(newWidth: Float): GraphPlacement {
        val aspect = (height / width.coerceAtLeast(0.0001f)).coerceAtLeast(0.0001f)

        // Bounded by both edges. A tall graph on a wide canvas runs out of room at the top and
        // bottom while its width still has plenty, so the height has to be able to veto the width —
        // otherwise the shape silently changes at the very end of the slider's travel, which is
        // precisely what this control exists not to do.
        val w = minOf(newWidth, 1f / aspect).coerceIn(MIN_SIZE, 1f)
        val h = (w * aspect).coerceIn(MIN_SIZE, 1f)

        val centreX = (left + right) / 2f
        val centreY = (top + bottom) / 2f
        val x = (centreX - w / 2f).coerceIn(0f, 1f - w)
        val y = (centreY - h / 2f).coerceIn(0f, 1f - h)
        return GraphPlacement(x, y, x + w, y + h)
    }

    /** Resizes from the top-left, sliding back into frame if the new size would overflow. */
    fun withSize(newWidth: Float, newHeight: Float): GraphPlacement {
        val w = newWidth.coerceIn(MIN_SIZE, 1f)
        val h = newHeight.coerceIn(MIN_SIZE, 1f)
        val x = left.coerceIn(0f, 1f - w)
        val y = top.coerceIn(0f, 1f - h)
        return GraphPlacement(x, y, x + w, y + h)
    }

    /** Centres left-to-right, keeping the height and vertical position. */
    fun centredHorizontally(): GraphPlacement = movedTo((1f - width) / 2f, top)

    /** Centres top-to-bottom, keeping the width and horizontal position. */
    fun centredVertically(): GraphPlacement = movedTo(left, (1f - height) / 2f)

    /** Whether a point, in the same 0..1 space, falls inside the frame. */
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    /** Whether this is the same framing, allowing for a drag that landed a hair off. */
    fun matches(other: GraphPlacement): Boolean =
        kotlin.math.abs(left - other.left) < 0.005f &&
            kotlin.math.abs(top - other.top) < 0.005f &&
            kotlin.math.abs(right - other.right) < 0.005f &&
            kotlin.math.abs(bottom - other.bottom) < 0.005f

    /**
     * Drags one handle, keeping the opposite side fixed.
     *
     * Stops at the minimum size rather than letting a side cross the one opposite it, which would
     * invert the rectangle and draw the graph inside out.
     */
    fun resizedBy(handle: GraphHandle, dx: Float, dy: Float): GraphPlacement {
        val movesLeft = handle in setOf(GraphHandle.TOP_LEFT, GraphHandle.BOTTOM_LEFT, GraphHandle.LEFT)
        val movesRight = handle in setOf(GraphHandle.TOP_RIGHT, GraphHandle.BOTTOM_RIGHT, GraphHandle.RIGHT)
        val movesTop = handle in setOf(GraphHandle.TOP_LEFT, GraphHandle.TOP_RIGHT, GraphHandle.TOP)
        val movesBottom = handle in setOf(GraphHandle.BOTTOM_LEFT, GraphHandle.BOTTOM_RIGHT, GraphHandle.BOTTOM)

        return copy(
            left = if (movesLeft) (left + dx).coerceIn(0f, right - MIN_SIZE) else left,
            right = if (movesRight) (right + dx).coerceIn(left + MIN_SIZE, 1f) else right,
            top = if (movesTop) (top + dy).coerceIn(0f, bottom - MIN_SIZE) else top,
            bottom = if (movesBottom) (bottom + dy).coerceIn(top + MIN_SIZE, 1f) else bottom
        )
    }

    /** Moves without resizing. The graph keeps its size and stops at the edge of the canvas. */
    fun movedTo(x: Float, y: Float): GraphPlacement {
        val w = width
        val h = height
        val newX = x.coerceIn(0f, 1f - w)
        val newY = y.coerceIn(0f, 1f - h)
        return GraphPlacement(newX, newY, newX + w, newY + h)
    }

    companion object {
        fun of(rect: android.graphics.RectF) =
            GraphPlacement(rect.left, rect.top, rect.right, rect.bottom)

        /**
         * The default placement for a clip: half the width, a third of the height, centred across
         * and sitting low.
         *
         * A graph covering the entire video hides the thing it is annotating — the footage is the
         * subject and the curve is the caption. Inset from every edge on purpose, so its handles
         * are grabbable from the moment it appears rather than pinned against the frame border.
         */
        val DEFAULT = GraphPlacement(0.25f, 0.58f, 0.75f, 0.91f)

        /**
         * Framings worth one tap.
         *
         * Dragging to a clean lower third is fiddly and the result is never quite square with the
         * frame. These are the arrangements people actually want, stated exactly.
         */
        val PRESETS: List<Pair<String, GraphPlacement>> = listOf(
            "Lower band" to DEFAULT,
            "Lower third" to GraphPlacement(0.05f, 0.62f, 0.95f, 0.95f),
            "Bottom left" to GraphPlacement(0.04f, 0.60f, 0.52f, 0.94f),
            "Bottom right" to GraphPlacement(0.48f, 0.60f, 0.96f, 0.94f),
            "Upper band" to GraphPlacement(0.25f, 0.06f, 0.75f, 0.39f),
            "Centred" to GraphPlacement(0.18f, 0.33f, 0.82f, 0.67f),
            "Full frame" to GraphPlacement(0f, 0f, 1f, 1f)
        )

        /**
         * The framing a preset carries, which is what a clip falls back to before it is framed.
         *
         * Falls back to [DEFAULT] when the stored rectangle is one nothing could have chosen on
         * purpose. Two ways that happens, and both leave a preset that cannot be dragged back into
         * shape: a preset saved before framing existed deserializes with zeroes, because Gson fills
         * absent fields with zero rather than running Kotlin's defaults; and `RectF(0, 0, 1, 1)` is
         * the untouched default of a config, meaning "nobody set this" rather than "full frame".
         * A frame the user really did drag to the edges differs from that sentinel on some side.
         */
        fun of(preset: ExportPreset, laneCount: Int = 0): GraphPlacement {
            // The frame for this many people where the preset pins one, and the preset's own frame
            // where it does not — see [ExportPreset.frameFor]. `laneCount` defaults to zero for
            // the call sites that genuinely have no clip in hand, such as the preset editor, which
            // is framing the look rather than any particular shot.
            val frame = preset.frameFor(laneCount)
            val stored = GraphPlacement(frame.left, frame.top, frame.right, frame.bottom)
            val degenerate = stored.width < MIN_SIZE || stored.height < MIN_SIZE
            val unset = stored.left == 0f && stored.top == 0f &&
                stored.right == 1f && stored.bottom == 1f
            return if (degenerate || unset) DEFAULT else stored
        }

        /** Below this the graph is unreadable, so a drag stops rather than allowing it. */
        const val MIN_SIZE = 0.1f
    }
}

/**
 * One person's curve across a clip, small enough to draw in a list row.
 *
 * Normalised against the *clip's* own range rather than each person's, so the lines keep their
 * relative heights — the point is seeing that everyone went up together, which per-person
 * normalisation would flatten into three identical shapes.
 */
data class ClipSpark(
    val label: String,
    val colorArgb: Int,
    /** Evenly sampled across the clip, 0..1 of [ClipSelection.bpmScale]. Null inside a dropout. */
    val points: List<Float?>,
    val peakBpm: Int
)

/**
 * Everyone's curve across one clip, and the axis they are drawn against.
 *
 * The two travel together because neither is meaningful alone: the points are fractions, and the
 * fractions are fractions *of* the scale. Returning only the curves is how the scale came to be
 * computed and discarded, leaving a plot that always filled its box.
 */
data class ClipCurves(
    val sparks: List<ClipSpark> = emptyList(),
    val scale: IntRange? = null
)

/**
 * What a batch will cost, before starting it.
 *
 * Approximate on purpose. The point is catching "there is no room for this" before twenty minutes
 * of rendering, not predicting the file size to the byte.
 */
data class ExportEstimate(
    val jobCount: Int,
    val totalDurationMs: Long,
    val approxBytes: Long,
    val approxRenderMs: Long,
    /** Each video the batch makes, for the review list on step 4. */
    val items: List<EstimateItem> = emptyList(),
    /**
     * Free space the batch needs: every finished file, plus room for the biggest render's working
     * files. The same factor the render checks against, so a batch that passes here doesn't fail
     * there.
     */
    val requiredFreeBytes: Long = 0L,
    /** Free space now, or null when it couldn't be read. */
    val freeBytes: Long? = null
) {
    /** Whether it fits. Unknown free space is let through: the render checks again. */
    val fits: Boolean get() = freeBytes == null || freeBytes >= requiredFreeBytes
}

/** One video in a batch, as step 4 lists it. */
data class EstimateItem(val title: String, val durationMs: Long, val bytes: Long)

/**
 * Drives the export utility.
 *
 * Holds the step and the source rather than the export settings themselves — those still live in
 * the settings repository for now, and moving them here is EXP-4.1's job, once step 3 is
 * reorganised into sections and the settings have somewhere to go.
 */
class ExportUtilityViewModel(
    private val repository: LibraryRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _step = MutableStateFlow(ExportStep.SOURCE)
    val step: StateFlow<ExportStep> = _step.asStateFlow()

    /**
     * How far the user has got.
     *
     * Steps behind this are freely revisitable and steps beyond it are not — the flow has to be
     * walkable backwards without losing anything, and forwards only once its questions have been
     * answered. Tracked as a high-water mark rather than recomputed, so stepping back does not
     * lock the steps ahead that were already reached.
     */
    private val _furthestStep = MutableStateFlow(ExportStep.SOURCE)
    val furthestStep: StateFlow<ExportStep> = _furthestStep.asStateFlow()

    private val _source = MutableStateFlow<ExportSource>(ExportSource.None)
    val source: StateFlow<ExportSource> = _source.asStateFlow()

    /**
     * A name the caller supplied, overriding whatever the source would be called.
     *
     * Declared here because [sourceLabel] reads it, and a property cannot be read before it is
     * initialised.
     */
    private val labelOverride = MutableStateFlow<String?>(null)

    /**
     * The recordings the chosen source resolves to.
     *
     * Live rather than captured, so filing one more recording into the chosen event includes it
     * without the user starting again.
     */
    /**
     * The recordings the chosen source resolves to, without their readings.
     *
     * Scope resolution is a question about rows — which recordings — and answering it does not need
     * a single reading. [records] loads those, once, for whatever this settles on.
     */
    private val scopeSummaries: StateFlow<List<BpmRecord>> = combine(
        combine(
            repository.records,
            _source,
            repository.allEventsInTree,
            repository.allCollectionEventLinks(),
            repository.allCollectionRecordLinks()
        ) { library, source, events, eventLinks, recordLinks ->
            listOf(library, source, events, eventLinks, recordLinks)
        },
        // The sets themselves, so a smart collection resolves its rule rather than exporting
        // nothing. Membership comes from Scope here as it does everywhere else — and so do the
        // registries a rule needs to look itself up, without which a rule naming an event type or
        // a venue exports nothing at all. See [filterContextOf].
        combine(
            repository.getAllCollections(),
            repository.getAllLocations(),
            repository.effectiveTags,
            repository.allTags
        ) { sets, places, tagsByRecord, allTags ->
            listOf(sets, places, tagsByRecord, allTags)
        }
    ) { parts, registries ->
        @Suppress("UNCHECKED_CAST")
        val library = parts[0] as List<BpmRecord>
        val source = parts[1] as ExportSource
        @Suppress("UNCHECKED_CAST")
        val events = parts[2] as List<inga.bpmetrics.library.EventEntity>
        @Suppress("UNCHECKED_CAST")
        val eventLinks = parts[3] as List<inga.bpmetrics.library.CollectionEventCrossRef>
        @Suppress("UNCHECKED_CAST")
        val recordLinks = parts[4] as List<inga.bpmetrics.library.CollectionRecordCrossRef>
        @Suppress("UNCHECKED_CAST")
        val collections = registries[0] as List<inga.bpmetrics.library.CollectionEntity>
        @Suppress("UNCHECKED_CAST")
        val places = registries[1] as List<inga.bpmetrics.library.LocationEntity>
        @Suppress("UNCHECKED_CAST")
        val tagsByRecord =
            registries[2] as Map<Long, List<inga.bpmetrics.library.Inherited<inga.bpmetrics.library.TagEntity>>>
        @Suppress("UNCHECKED_CAST")
        val allTags = registries[3] as List<inga.bpmetrics.library.TagEntity>

        // One snapshot for every branch below, with the whole context. Each branch used to build
        // its own or none at all.
        val snapshot = inga.bpmetrics.library.Library(
            records = library,
            events = events,
            collections = collections,
            collectionEvents = eventLinks,
            collectionRecords = recordLinks,
            filterContext = inga.bpmetrics.library.filterContextOf(
                events = events,
                places = places,
                effectiveTags = tagsByRecord,
                tags = allTags
            )
        )

        fun through(ref: inga.bpmetrics.library.ScopeRef): List<BpmRecord> {
            val ids = inga.bpmetrics.library.Scope.recordsIn(ref, snapshot)
                .mapTo(mutableSetOf()) { it.metadata.recordId }
            return library.filter { it.metadata.recordId in ids }
        }

        when (source) {
            is ExportSource.None -> emptyList()
            is ExportSource.Recordings -> library.filter { it.metadata.recordId in source.recordIds }
            // The whole subtree, through the resolver. This matched the recordings filed *directly*
            // on the event — and a festival almost never holds any itself, its days do — so
            // choosing a top-level event resolved to nothing, the picker said seven, and Next was
            // dead with no explanation. The sixth time this codebase has counted one level.
            is ExportSource.Event -> through(inga.bpmetrics.library.ScopeRef.Event(source.eventId))
            // A collection, resolved through the same walk its card counts with — so a set
            // reporting 47 recordings exports those 47. References are followed now rather than
            // frozen when they were made, so a recording filed into one of its events last night
            // is in this export without anyone re-adding anything.
            is ExportSource.Collection ->
                through(inga.bpmetrics.library.ScopeRef.Collection(source.collectionId))
            // Resolved by id against the library, so a recording deleted since the analysis was
            // saved simply is not offered — an export cannot render a curve that is gone.
            is ExportSource.SavedAnalysis -> savedAnalysisRecordIds.value
                .let { ids -> library.filter { it.metadata.recordId in ids } }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The recordings the chosen source resolves to, with their readings.
     *
     * The readings are loaded here and only here on this screen, for the scope that was actually
     * chosen — an export draws curves, so it is one of the four places that genuinely needs them.
     * Reading the whole library to render three recordings is what §9 of the product doc is about.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val records: StateFlow<List<BpmRecordWithPoints>> = scopeSummaries
        .mapLatest { chosen ->
            repository.recordsWithPoints(chosen.map { it.metadata.recordId })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val savedAnalysisRecordIds = MutableStateFlow<Set<Long>>(emptySet())

    /** What the export will be called, taken from whatever named the source. */
    val sourceLabel: StateFlow<String> = combine(
        _source,
        repository.getAllEvents(),
        repository.getAllCollections(),
        labelOverride
    ) { source, events, collections, override ->
        override ?: when (source) {
            is ExportSource.None -> ""
            is ExportSource.Recordings ->
                "${source.recordIds.size} recording${if (source.recordIds.size == 1) "" else "s"}"
            // The short name here. This labels the step — "what am I exporting" — where the full
            // path would wrap; [scopeTitle] is the one that ends up drawn on the picture.
            is ExportSource.Event ->
                events.firstOrNull { it.eventId == source.eventId }?.displayName.orEmpty()
            is ExportSource.Collection ->
                collections.firstOrNull { it.collectionId == source.collectionId }?.displayName.orEmpty()
            is ExportSource.SavedAnalysis -> savedAnalysisName.value
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val savedAnalysisName = MutableStateFlow("")

    /**
     * What to *call* the thing being exported, as opposed to how much of it there is.
     *
     * Distinct from [sourceLabel], which answers "how many recordings" and is right for a step
     * header. A picture needs the name: the recording's, the event's, or the group's. Falling back
     * to a count — or worse to a placeholder like "Whole timeline" — puts a caption on the image
     * that describes nothing anyone would recognise.
     */
    val scopeTitle: StateFlow<String> = combine(
        _source,
        records,
        // The whole tree, not [getAllEvents]: a title is the path down to the thing, and a walk that
        // cannot see the containers stops at the first one it does not know about.
        repository.allEventsInTree,
        repository.getAllCollections(),
        combine(
            repository.getAllPeople(),
            labelOverride,
            repository.getAllLocations()
        ) { people, override, places -> Triple(people, override, places) }
    ) { source, records, events, collections, (people, override, places) ->
        val placesById = places.associateBy { it.locationId }

        override?.takeIf { it.isNotBlank() } ?: when (source) {
            is ExportSource.None -> ""
            // Through [ExportTitle] like everything else. This branch answered with the event's
            // bare `displayName`, which is how a Tape B export came out captioned "Tape B" with no
            // sign of the Levitape it sits inside — and the ancestry matters most in exactly this
            // case, because an event's own name is usually its least distinctive part.
            is ExportSource.Event -> inga.bpmetrics.library.ExportTitle.of(
                eventId = source.eventId,
                events = events,
                places = placesById
            )
            is ExportSource.Collection ->
                collections.firstOrNull { it.collectionId == source.collectionId }?.displayName.orEmpty()
            is ExportSource.SavedAnalysis -> savedAnalysisName.value
            is ExportSource.Recordings -> {
                val single = records.singleOrNull()
                if (single != null) {
                    // The name shown everywhere else for this recording, generated one included,
                    // so the picture is captioned the way the library labels it.
                    val name = single.displayName(
                        people.firstOrNull { it.personId == single.metadata.personId }?.displayName
                    )
                    // Where it was recorded, and its own name only if it was filed nowhere. A
                    // recording filed into a set is as much "that night" as the set is.
                    inga.bpmetrics.library.ExportTitle.of(
                        recordingName = name,
                        eventId = single.metadata.eventId,
                        events = events,
                        places = placesById
                    )
                } else {
                    // Several loose recordings genuinely have no shared name. A count is the
                    // honest answer, and the title field is there for anyone who disagrees.
                    "${records.size} recording${if (records.size == 1) "" else "s"}"
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")


    // --- Step 3: presets ---

    val presets: StateFlow<List<ExportPresetEntity>> = repository.getExportPresets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The appearance the next export will use.
     *
     * Held here rather than in the settings dialog so it survives walking back to step 1 and
     * forward again — the look is the part worth keeping, and losing it on a source change would
     * make the flow hostile to changing your mind.
     */
    private val _preset = MutableStateFlow(ExportPreset())
    val preset: StateFlow<ExportPreset> = _preset.asStateFlow()

    /**
     * Which preset the current settings started from. Kept through edits; [presetEdited] says
     * whether they still match it.
     *
     * Editing used to clear this, which took "Update to current settings" away at exactly the
     * moment there was something to update with (Audit #21).
     */
    private val _selectedPresetId = MutableStateFlow<Long?>(null)
    val selectedPresetId: StateFlow<Long?> = _selectedPresetId.asStateFlow()

    /** Whether the look has been changed this session when no preset is selected. */
    private val _presetTouched = MutableStateFlow(false)

    /**
     * Whether the look has changes no preset holds: it differs from the selected preset, or, with
     * none selected, it's been changed here. Choosing a preset asks first while this is true.
     */
    val presetEdited: StateFlow<Boolean> =
        combine(presets, _selectedPresetId, _preset, _presetTouched) { saved, id, current, touched ->
            isPresetEdited(saved.firstOrNull { it.presetId == id }, current, touched)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Whether the stored preset has already been loaded into this session.
     *
     * The caller is a `LaunchedEffect(Unit)`, and `Unit` does not mean "once" — it means once per
     * *composition*, and rotating the phone throws the composition away and builds a new one. So
     * every rotation re-ran this and overwrote the working preset with what was on disk: switch
     * names on, turn the phone, and the setting is back off with nothing to say why.
     *
     * Held here rather than fixed at the call site because this view model outliving the screen is
     * exactly what makes the edits worth keeping, and it is the only thing that knows they exist.
     */
    private var presetRestored = false

    fun restorePreset() {
        if (presetRestored) return
        presetRestored = true
        launchGuarded(failure = null) {
            val lastUsedId = settingsRepository.lastUsedExportPresetId()
            val start = startingLook(
                lastUsedJson = settingsRepository.lastUsedExportPreset(),
                lastUsedFrom = lastUsedId?.let { repository.getExportPreset(it) },
                default = repository.getDefaultExportPreset()
            ) ?: return@launchGuarded
            _preset.value = start.preset
            _selectedPresetId.value = start.presetId
            _presetTouched.value = false
        }
    }

    fun applyPreset(entity: ExportPresetEntity) {
        ExportPreset.fromJson(entity.configJson)?.let {
            _preset.value = it
            _selectedPresetId.value = entity.presetId
            _presetTouched.value = false
        }
    }

    /**
     * What to call the look a job was queued with.
     *
     * The applied preset's name, marked when it's been changed, or the canvas shape when there's
     * no preset: a queue row saying "1080×1920" is still more use than one saying nothing.
     */
    fun presetLabel(): String {
        val named = _preset.value.name.takeIf { it.isNotBlank() && _selectedPresetId.value != null }
            ?: return "${_preset.value.width}×${_preset.value.height}"
        return if (presetEdited.value) "$named (edited)" else named
    }

    /** A change to the look. The preset it started from stays selected; see [presetEdited]. */
    fun setPreset(preset: ExportPreset) {
        if (preset == _preset.value) return
        _preset.value = preset
        _presetTouched.value = true
    }

    /**
     * Saves the current look, including the framing being looked at.
     *
     * The framing is passed in rather than read from the preset, because while a clip is being
     * previewed the framing on screen is that clip's override — and that is the one the user means
     * when they save.
     */
    fun savePresetAs(name: String, framing: GraphPlacement) {
        launchGuarded {
            val toSave = framing.into(_preset.value).copy(name = name)
            _preset.value = toSave
            _selectedPresetId.value = repository.saveExportPreset(name, toSave.toJson())
            _presetTouched.value = false
        }
    }

    fun updatePreset(entity: ExportPresetEntity, framing: GraphPlacement) {
        launchGuarded {
            val toSave = framing.into(_preset.value).copy(name = entity.name)
            _preset.value = toSave
            repository.updateExportPreset(entity.presetId, entity.name, toSave.toJson())
            _selectedPresetId.value = entity.presetId
            _presetTouched.value = false
            inga.bpmetrics.ui.feedback.Feedback.say("Updated ${entity.name}.")
        }
    }

    fun setDefaultPreset(entity: ExportPresetEntity) {
        launchGuarded { repository.setDefaultExportPreset(entity.presetId) }
    }

    fun deletePreset(entity: ExportPresetEntity) {
        launchGuarded {
            repository.deleteExportPreset(entity.presetId)
            if (_selectedPresetId.value == entity.presetId) _selectedPresetId.value = null
        }
    }

    /** Remembers where the user left off. The next export starts here, ahead of the default. */
    fun rememberLastUsed() {
        launchGuarded {
            settingsRepository.setLastUsedExportPreset(_preset.value.toJson(), _selectedPresetId.value)
        }
    }

    /**
     * Applies a preset read from a file.
     *
     * @return false when the payload was malformed or written by a newer build, so the caller can
     *   say so. A half-applied preset would produce an export looking nothing like the one it was
     *   shared from, which is worse than a refusal.
     */
    fun importPreset(json: String, onDone: (Boolean) -> Unit) {
        val imported = ExportPreset.fromJson(json)
        if (imported == null) {
            onDone(false)
            return
        }
        launchGuarded {
            val name = imported.name.ifBlank { "Imported preset" }
            val id = repository.saveExportPreset(name, imported.copy(name = name).toJson())
            _preset.value = imported.copy(name = name)
            _selectedPresetId.value = id
            _presetTouched.value = false
            onDone(true)
        }
    }

    /**
     * The background chosen for a source with no clips of its own.
     *
     * Batched exports take their background from the clip. This is for the other case — a session
     * nobody filmed, or one where the user wants a video the overlap search did not turn up.
     */
    private val _manualOverlay = MutableStateFlow<android.net.Uri?>(null)
    val manualOverlay: StateFlow<android.net.Uri?> = _manualOverlay.asStateFlow()

    fun setManualOverlay(uri: android.net.Uri?) {
        _manualOverlay.value = uri
    }

    /**
     * Where the preview is scrubbed to, as a fraction of the export.
     *
     * A fraction rather than a timestamp so it survives changing which clip is being previewed —
     * the interesting instant is usually "about a third in", not a particular millisecond.
     */
    private val _previewAt = MutableStateFlow(0f)
    val previewAt: StateFlow<Float> = _previewAt.asStateFlow()

    fun scrubPreview(fraction: Float) {
        _previewAt.value = fraction.coerceIn(0f, 1f)
    }

    /**
     * The request for one video: content from the arguments, the look from the preset.
     *
     * The preset goes in fully resolved for this video, with the framing it uses baked in and the
     * per-cast pins cleared, so a job restored from the queue frames exactly as this did.
     */
    fun buildRequest(
        forRecords: List<BpmRecordWithPoints>,
        overlay: android.net.Uri?,
        colours: Map<Long, Int>,
        /** Wearers' faces for the pills, already decoded and cropped. See `recordPhotos`. */
        photos: Map<Long, android.graphics.Bitmap>,
        title: String?,
        /** The clip this is drawn over, when there is one. Decides the sync and the crop. */
        clip: VideoExporter.VideoClip? = null,
        placement: GraphPlacement? = null
    ): inga.bpmetrics.export.ExportRequest.Video {
        // Always clock-aligned. A recording happened at a particular time, and starting it at 0:00
        // of a video only ever looked right when the two happened to begin together.
        val timeline = inga.bpmetrics.export.ImageExporter.timelineFor(forRecords)

        // The clip's own window on the shared timeline. Without this the export spans the whole
        // recording and the footage drifts out of sync within seconds.
        val window = clip?.windowOn(timeline, _preset.value.syncOffsetMs)
        val startMs = window?.startMs ?: 0L
        val endMs = window?.endMs ?: timeline.durationMs

        val content = inga.bpmetrics.export.ExportContent(
            records = forRecords,
            startMs = startMs,
            endMs = endMs.coerceAtLeast(startMs + 1000L),
            title = title,
            colours = colours,
            photos = photos,
            // The footage's own clock, for the preview and the render alike.
            clipZoneId = clip?.zoneId
        )

        // The clip's own framing when it has been given one, otherwise whatever the preset gives
        // for this many curves (one curve and one readout per record). A clip framed by hand still
        // wins: `placement` is that override, and a pin is only ever a better default.
        val framing = placement ?: GraphPlacement.of(_preset.value, forRecords.size)
        val look = framing.into(_preset.value).copy(framesByLaneCount = emptyMap())

        return inga.bpmetrics.export.ExportRequest.Video(
            content = content,
            preset = look,
            clip = overlay,
            // The render aligns against exactly what the picker resolved and the preview drew.
            clipStartedAtMs = clip?.startedAtMs
        )
    }

    /**
     * Draws every image the plan calls for.
     *
     * The preset supplies the look, exactly as it does for a video — one definition of what a graph
     * looks like rather than a second one only images understand. The video-only fields it also
     * carries (frame rate, bit rates, the visible window, the sync offset) simply have nothing to
     * act on here.
     *
     * Graph framing is deliberately *not* applied. A video's graph is a caption in a corner of
     * someone else's footage; an image is the graph, so insetting it would leave a border of
     * nothing around the only thing in the frame.
     */
    fun renderImages(
        plan: List<ImagePlanEntry>,
        colours: Map<Long, Int>,
        names: Map<Long, String>,
        eventNames: Map<Long, String> = emptyMap()
    ): List<RenderedImage> {
        val preset = _preset.value
        val crop = _imageCrop.value
        // A typed title names *the* picture. With a set of them, each already carries the name of
        // the event it is of, and one title across all of them would be worse than none — so the
        // override only applies where there is a single thing to name.
        val override = _imageTitle.value.takeIf { plan.size == 1 }

        return plan.mapNotNull { entry ->
            val spec = buildTimelineSpec(entry, preset, colours, names, eventNames, crop, override)
                ?: return@mapNotNull null
            val bitmap = runCatching { TimelineImageExporter.render(spec) }.getOrNull()
                ?: return@mapNotNull null
            // Named by what the picture is titled, not by what the plan called it, so a typed
            // title reaches the caption in step 4 and the filename on disk as well as the graph.
            RenderedImage(label = spec.title ?: entry.label, bitmap = bitmap)
        }
    }

    /**
     * Turns the recordings for one image into something the timeline renderer can draw.
     *
     * The awkward part is coordinates. Records carry timestamps relative to their own start, the
     * renderer wants them relative to the window, and the crop the user typed is a wall clock. All
     * three are reconciled here, once, rather than at three call sites that would drift.
     */
    private fun buildTimelineSpec(
        entry: ImagePlanEntry,
        preset: ExportPreset,
        colours: Map<Long, Int>,
        names: Map<Long, String>,
        eventNames: Map<Long, String>,
        crop: ImageCrop,
        titleOverride: String?
    ): TimelineImageExporter.Spec? {
        val records = entry.records.filter { it.dataPoints.isNotEmpty() }
        if (records.isEmpty()) return null

        val naturalStart = records.minOf { it.metadata.startTime }
        val naturalEnd = records.maxOf { rec ->
            rec.metadata.startTime + (rec.dataPoints.lastOrNull()?.timestamp ?: rec.metadata.durationMs)
        }
        val windowStart = (crop.startWallClockMs ?: naturalStart).coerceAtMost(naturalEnd - 1_000L)
        val windowEnd = (crop.endWallClockMs ?: naturalEnd).coerceAtLeast(windowStart + 1_000L)

        // One curve per person, not per recording: someone whose watch dropped out mid-evening has
        // two recordings and is still one line on the graph and one row in the summary.
        val byPerson = records.groupBy { it.metadata.personId ?: -it.metadata.recordId }
        val series = byPerson.mapNotNull { (_, group) ->
            val first = group.first()
            val points = group
                .flatMap { rec ->
                    rec.dataPoints.map { point ->
                        (rec.metadata.startTime + point.timestamp) to point.bpm
                    }
                }
                .filter { (at, _) -> at in windowStart..windowEnd }
                .sortedBy { it.first }
                .map { (at, bpm) ->
                    TimelineImageExporter.Series.Point(at - windowStart, bpm)
                }
            if (points.isEmpty()) return@mapNotNull null

            TimelineImageExporter.Series(
                label = names[first.metadata.personId]
                    ?: first.metadata.wearerName.takeIf { it.isNotBlank() }
                    ?: first.metadata.title,
                colorArgb = colours[first.metadata.recordId] ?: preset.lowBpmColor,
                points = points
            )
        }
        if (series.isEmpty()) return null

        // Sections only when one image covers several events — a single event marked out as one
        // band spanning the whole width says nothing the title has not already said.
        val sections = records
            .groupBy { it.metadata.eventId }
            .mapNotNull { (eventId, group) ->
                if (eventId == null) return@mapNotNull null
                val from = group.minOf { it.metadata.startTime }.coerceAtLeast(windowStart)
                val to = group
                    .maxOf { it.metadata.startTime + it.metadata.durationMs }
                    .coerceAtMost(windowEnd)
                if (to <= from) return@mapNotNull null
                TimelineImageExporter.Section(
                    label = eventNames[eventId] ?: "Event",
                    startMs = from - windowStart,
                    endMs = to - windowStart
                )
            }
            .sortedBy { it.startMs }

        return TimelineImageExporter.Spec(
            title = titleOverride ?: entry.label,
            windowStartWallClockMs = windowStart,
            windowEndWallClockMs = windowEnd,
            series = series,
            sections = sections,
            // Where these recordings happened (see `exportZoneOf`): an image's time axis and its
            // date line are read in this zone.
            timeZoneId = inga.bpmetrics.library.exportZoneOf(records.map { it.metadata }),
            look = preset
        )
    }

    /**
     * The clock window an image covers.
     *
     * Absolute instants rather than offsets, because that is how the question is asked: "from when
     * the set started to when it finished", not "from 14 minutes in".
     */
    private val _imageCrop = MutableStateFlow(ImageCrop())
    val imageCrop: StateFlow<ImageCrop> = _imageCrop.asStateFlow()

    fun setImageCrop(crop: ImageCrop) {
        _imageCrop.value = crop
    }

    /**
     * A time typed into a crop field that can't be read, or null. Held so Next waits for it: the
     * window would otherwise be exported with the last time that could be read, not the one shown.
     */
    private val _cropTypingProblem = MutableStateFlow<String?>(null)

    fun setCropTypingProblem(problem: String?) {
        _cropTypingProblem.value = problem
    }

    /**
     * A title typed in place of the one the scope supplies.
     *
     * Null rather than pre-filled with the default, so "I have not renamed this" stays distinct
     * from "I typed the same thing". A group renamed later should still title its own image, and
     * pre-filling would quietly freeze the old name into every export after it.
     */
    private val _imageTitle = MutableStateFlow<String?>(null)
    val imageTitle: StateFlow<String?> = _imageTitle.asStateFlow()

    fun setImageTitle(title: String?) {
        _imageTitle.value = title?.takeIf { it.isNotBlank() }
    }

    /** The natural full span of what is in scope, for seeding the crop fields. */
    val imageNaturalSpan: StateFlow<Pair<Long, Long>?> = records.map { records ->
        val withData = records.filter { it.dataPoints.isNotEmpty() }
        if (withData.isEmpty()) return@map null
        withData.minOf { it.metadata.startTime } to withData.maxOf {
            it.metadata.startTime + it.metadata.durationMs
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Says when a clip was really filmed.
     *
     * One number, and everything else follows — see [ClipRetiming] for why it is not three.
     */
    fun setClipFilmedAt(uri: android.net.Uri, startedAtMs: Long) {
        _clips.value = _clips.value.map {
            if (it.clip.uri == uri) ClipRetiming.retime(it, startedAtMs, records.value) else it
        }
        refreshSparks(uri)
    }

    /** Back to the time the file gave. */
    fun resetClipFilmedAt(uri: android.net.Uri) {
        _clips.value = _clips.value.map {
            if (it.clip.uri == uri) ClipRetiming.reset(it, records.value) else it
        }
        refreshSparks(uri)
    }

    private val _pickFailed = MutableStateFlow(false)

    /** True after a picked file turned out not to be a playable video, until the next pick. */
    val pickFailed: StateFlow<Boolean> = _pickFailed.asStateFlow()

    /**
     * Adds a video chosen by hand, as a clip like any other.
     *
     * Ticked, because it was chosen on purpose — unlike found clips, which start unticked so a
     * stray Next does not queue renders nobody asked for. Placed on the first recording where its
     * own time lands on nothing, so there is a graph to preview while the time is corrected.
     *
     * Picking a video already in the list does nothing but select it: two copies of one clip would
     * be two renders of the same footage.
     */
    fun addPickedVideo(context: android.content.Context, uri: android.net.Uri, onAdded: (android.net.Uri) -> Unit = {}) {
        launchGuarded {
            _pickFailed.value = false
            if (_clips.value.any { it.clip.uri == uri }) {
                onAdded(uri)
                return@launchGuarded
            }
            val fileClip = withContext(Dispatchers.IO) {
                VideoExporter.clipFromUri(context, uri)
            }
            if (fileClip == null) {
                _pickFailed.value = true
                return@launchGuarded
            }

            val inScope = records.value
            val start = ClipRetiming.startingPoint(fileClip, inScope)
            val found = ClipSelection(
                clip = fileClip,
                recordIds = ClipRetiming.castFor(fileClip, inScope, emptySet()),
                selected = false
            )
            val placed = if (start == fileClip.startedAtMs) found
                else ClipRetiming.retime(found, start, inScope)

            _clips.value = _clips.value + placed.copy(selected = placed.recordIds.isNotEmpty())
            onAdded(uri)
            refreshSparks(uri)
        }
    }

    /**
     * Redraws one clip's sparkline for wherever it now sits.
     *
     * The result is written back only if the clip is still at the time it was drawn for. Correcting
     * a time twice in quick succession starts two of these, and the slower first one would otherwise
     * land last and put the previous stretch of the recording back under the new time.
     */
    private fun refreshSparks(uri: android.net.Uri) {
        launchGuarded(failure = null) {
            val target = _clips.value.firstOrNull { it.clip.uri == uri } ?: return@launchGuarded
            val people = repository.getAllPeople().first()
            val watches = repository.getAllWatches().first()
            val curves = withContext(Dispatchers.Default) {
                sparkFor(target, records.value, watches, people)
            }
            _clips.value = _clips.value.map { current ->
                if (current.clip.uri == uri && current.clip.startedAtMs == target.clip.startedAtMs) {
                    current.copy(sparks = curves.sparks, bpmScale = curves.scale)
                } else current
            }
        }
    }

    /** Moves or resizes the graph on one clip. */
    fun setClipGraph(uri: android.net.Uri, placement: GraphPlacement) {
        _clips.value = _clips.value.map {
            if (it.clip.uri == uri) it.copy(graph = placement) else it
        }
    }

    fun scrubClip(uri: android.net.Uri, at: Float) {
        _clips.value = _clips.value.map {
            if (it.clip.uri == uri) it.copy(scrubAt = at.coerceIn(0f, 1f)) else it
        }
    }

    /**
     * Gives every clip the placement one of them has.
     *
     * The scrub position is deliberately not copied — it points at a moment inside one particular
     * clip, and the same fraction of a different shot is a different moment.
     */
    fun applyGraphToAll(from: android.net.Uri) {
        val source = _clips.value.firstOrNull { it.clip.uri == from }?.graph ?: return
        _clips.value = _clips.value.map { it.copy(graph = source) }
    }

    /**
     * Remembers this framing as the one for clips with this many people on them.
     *
     * The gesture bulk export actually wanted. A frame drawn around two readouts is not the frame
     * for four — the renderer divides the readout column by the cast, so four in a two-person frame
     * squeeze to the minimum and one leaves it half empty. Pinning writes it onto the *preset*, so
     * it applies to every clip with that cast in this batch **and** in every batch after it.
     *
     * The clips with that cast have their own overrides cleared rather than set, so they fall
     * through to the pin. Otherwise the pin would be shadowed by copies of itself, and editing it
     * later would appear to do nothing.
     */
    fun pinGraphForCast(from: android.net.Uri) {
        val source = _clips.value.firstOrNull { it.clip.uri == from } ?: return
        val framing = source.graph ?: GraphPlacement.of(_preset.value, source.recordIds.size)
        val cast = source.recordIds.size.coerceAtLeast(1)

        _preset.value = _preset.value.copy(
            framesByLaneCount = _preset.value.framesByLaneCount + (
                cast to inga.bpmetrics.export.GraphFrame(
                    left = framing.left,
                    top = framing.top,
                    right = framing.right,
                    bottom = framing.bottom
                )
            )
        )
        // A change to the look like any other: the preset stays selected and shows as edited.
        _presetTouched.value = true

        _clips.value = _clips.value.map {
            if (it.recordIds.size.coerceAtLeast(1) == cast) it.copy(graph = null) else it
        }
    }

    /**
     * Roughly how large the queued exports will be, and how long they will take.
     *
     * Bitrate times duration, which is what the encoder is actually bounded by. Deliberately
     * approximate and labelled as such — the alternative is finding out after twenty minutes of
     * rendering that there was no room, which this app has already done to someone once.
     */
    fun estimate(
        jobs: List<ClipSelection>,
        fallbackDurationMs: Long,
        /** What each clip will be called, by its uri; [fallbackTitle] for the plain-background one. */
        titles: Map<String, String> = emptyMap(),
        fallbackTitle: String = "",
        freeBytes: Long? = null
    ): ExportEstimate {
        val preset = _preset.value
        // Each video sized by the render's own sum. A clip that takes its frame rate from the
        // footage is sized at 60, which no phone clip exceeds in practice: an estimate that's too
        // high only asks for room it may not need, one that's too low fails a render.
        fun sized(hasOverlay: Boolean, durationMs: Long) = inga.bpmetrics.export.VideoExporter.sizing(
            preset.width, preset.height,
            if (hasOverlay && preset.matchSourceFrameRate) 60 else preset.frameRate,
            hasOverlay, durationMs
        )
        val items = if (jobs.isEmpty()) {
            listOf(EstimateItem(fallbackTitle, fallbackDurationMs, sized(false, fallbackDurationMs).bytes))
        } else {
            jobs.map {
                EstimateItem(
                    titles[it.clip.uri.toString()] ?: it.clip.displayName,
                    it.clip.durationMs,
                    sized(true, it.clip.durationMs).bytes
                )
            }
        }
        val totalMs = items.sumOf { it.durationMs }
        val totalBytes = items.sumOf { it.bytes }
        val biggest = items.maxOfOrNull { it.bytes } ?: 0L

        return ExportEstimate(
            jobCount = items.size,
            totalDurationMs = totalMs,
            approxBytes = totalBytes,
            // Encoding runs slower than real time on a phone, and more so with an overlay. A
            // deliberately pessimistic multiplier: an estimate that undershoots is the one that
            // makes people think it has hung.
            approxRenderMs = (totalMs * if (jobs.isEmpty()) 1.5 else 3.0).toLong(),
            items = items,
            requiredFreeBytes = totalBytes +
                (biggest * (inga.bpmetrics.export.VideoExporter.SPACE_SAFETY_FACTOR - 1)).toLong(),
            freeBytes = freeBytes
        )
    }

    /**
     * What step 4 last queued, so coming back to it shows "Queued" instead of a second Start.
     * Anything that changes the batch (the source, a clip, the look) makes it a new one.
     */
    private val _queuedBatch = MutableStateFlow<Any?>(null)
    val queuedBatch: StateFlow<Any?> = _queuedBatch.asStateFlow()

    fun markQueued(batch: Any) {
        _queuedBatch.value = batch
    }

    // --- Step 2: which clips, and whose curves go on each ---

    private val _clips = MutableStateFlow<List<ClipSelection>>(emptyList())

    /**
     * Oldest first by default.
     *
     * The MediaStore query returns newest first, which is right for a gallery and wrong here: the
     * clips are moments in one evening, and reading them out of order makes the run of them hard
     * to follow. Reversible for the case where the end of the night is what you are after.
     */
    private val _clipsOldestFirst = MutableStateFlow(true)
    val clipsOldestFirst: StateFlow<Boolean> = _clipsOldestFirst.asStateFlow()

    fun toggleClipOrder() {
        _clipsOldestFirst.value = !_clipsOldestFirst.value
    }

    val clips: StateFlow<List<ClipSelection>> = combine(
        _clips,
        _clipsOldestFirst
    ) { list, oldestFirst ->
        if (oldestFirst) list.sortedBy { it.clip.startedAtMs }
        else list.sortedByDescending { it.clip.startedAtMs }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * What each clip's video will be called, keyed by its uri.
     *
     * Shown on the card in step 2, because the export is one video per clip and the name is the
     * only thing telling four of them filmed twenty minutes apart apart. Built through
     * [inga.bpmetrics.library.ExportTitle], so the caption on the card and the caption on the file
     * are the same string from the same rule.
     *
     * Named after the occasion, not after the recordings on it — see [ExportTitle]. The recording's
     * own name appears only where a clip covers exactly one and it was filed nowhere, which is the
     * only case with nothing better to call it.
     */
    val clipTitles: StateFlow<Map<String, String>> = combine(
        _clips,
        scopeSummaries,
        repository.allEventsInTree,
        repository.getAllLocations(),
        repository.getAllPeople()
    ) { list, inScope, events, places, people ->
        val placesById = places.associateBy { it.locationId }
        val byId = inScope.associateBy { it.metadata.recordId }

        list.associate { selection ->
            val covered = selection.recordIds.mapNotNull { byId[it] }
            val single = covered.singleOrNull()
            selection.clip.uri.toString() to inga.bpmetrics.library.ExportTitle.of(
                recordingName = single?.displayName(
                    people.firstOrNull { it.personId == single.metadata.personId }?.displayName
                ),
                // The event they *all* sit in, not the first one's. A clip that ran across the end
                // of one set and the start of the next belongs to the day that holds both, and
                // `firstOrNull` would have captioned it with whichever record sorted first.
                eventId = inga.bpmetrics.library.EventTree.commonAncestorOf(
                    events,
                    covered.map { it.metadata.eventId }
                ),
                events = events,
                places = placesById
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The recordings the clip list was found for. Contents with the same ones keeps the list. */
    private var clipsFoundFor: Set<Long>? = null

    /** Whether the clips are already found for these recordings, so the step needn't look again. */
    fun hasClipsFor(recordIds: Set<Long>): Boolean = clipsFoundFor == recordIds

    /** Look again next time Contents is shown, keeping what's listed. For when more footage can be seen. */
    fun forgetClips() {
        clipsFoundFor = null
    }

    private val _loadingClips = MutableStateFlow(false)
    val loadingClips: StateFlow<Boolean> = _loadingClips.asStateFlow()

    /**
     * Finds the clips filmed while the source was being recorded.
     *
     * Called when step 2 is reached rather than when the source changes: it hits the MediaStore,
     * and doing that on every tap in step 1 would query for sources the user is only browsing past.
     */
    fun loadClips(found: List<VideoExporter.VideoClip>) {
        val sourceRecords = records.value
        clipsFoundFor = sourceRecords.map { it.metadata.recordId }.toSet()
        // What's already listed wins over what was just found: its ticks, framing, "Filmed at"
        // correction and anything added by hand. Every visit to this step used to rebuild the list
        // unticked, so Back from Look, a step tap or a rotation wiped all of it (Audit #2).
        val kept = _clips.value.associateBy { it.clip.uri }

        // The rows appear immediately with everything except the curves, which need every data
        // point in range walked. Waiting for that before showing anything would leave the step
        // blank for a second on a group with forty clips.
        _clips.value = found.map { clip ->
            kept[clip.uri] ?: ClipSelection(
                clip = clip,
                // Whoever was recording while *this clip* was filming. Defaulting to the event's
                // whole cast would offer a curve for someone whose watch had already stopped.
                recordIds = sourceRecords.filter { clip.overlaps(it) }
                    .map { it.metadata.recordId }
                    .toSet(),
                // Nothing is ticked to begin with. Choosing what to export is the question this
                // step asks, and pre-answering it means a stray tap on Next queues renders nobody
                // asked for — expensive, and on a phone that has already been filled once.
                selected = false
            )
        } + kept.values.filter { kept -> found.none { it.uri == kept.clip.uri } }
        _loadingClips.value = false

        launchGuarded {
            val people = repository.getAllPeople().first()
            val watches = repository.getAllWatches().first()
            val withSparks = withContext(Dispatchers.Default) {
                _clips.value.map { selection ->
                    val curves = sparkFor(selection, sourceRecords, watches, people)
                    selection.copy(sparks = curves.sparks, bpmScale = curves.scale)
                }
            }
            // Re-read rather than overwrite blindly: the user may have unticked something while
            // this was running, and their taps outrank a background computation.
            _clips.value = _clips.value.map { current ->
                withSparks.firstOrNull { it.clip.uri == current.clip.uri }
                    ?.let { current.copy(sparks = it.sparks, bpmScale = it.bpmScale) }
                    ?: current
            }
        }
    }

    /**
     * Samples each person's curve across one clip.
     *
     * Built on [EventAnalysis] with the clip as its window, so the lanes, the colours and the gap
     * handling are the same ones the chart and the export use — a sparkline that disagreed with
     * the video it is previewing would be worse than none.
     */
    private fun sparkFor(
        selection: ClipSelection,
        allRecords: List<BpmRecordWithPoints>,
        watches: List<inga.bpmetrics.library.WatchEntity>,
        people: List<inga.bpmetrics.library.PersonEntity>
    ): ClipCurves {
        val theirs = allRecords.filter { it.metadata.recordId in selection.recordIds }
        if (theirs.isEmpty()) return ClipCurves()

        val analysis = EventAnalysis.from(
            records = theirs,
            watches = watches,
            people = people,
            window = selection.clip.startedAtMs..selection.clip.endedAtMs
        )
        if (analysis.isEmpty) return ClipCurves()

        // One range for everyone, so the lines keep their relative heights.
        val scale = scaleFor(
            low = analysis.series.minOf { it.minBpm },
            high = analysis.series.maxOf { it.maxBpm }
        )
        val base = scale.first.toDouble()
        val span = (scale.last - scale.first).toDouble()

        val start = selection.clip.startedAtMs
        val step = (selection.clip.durationMs.toDouble() / (SPARK_SAMPLES - 1)).coerceAtLeast(1.0)

        return ClipCurves(
            scale = scale,
            sparks = analysis.series.map { series ->
                ClipSpark(
                    label = series.label,
                    colorArgb = series.colorArgb,
                    points = (0 until SPARK_SAMPLES).map { i ->
                        // Null inside a dropout rather than a floor value, so a break in the line
                        // reads as "not measured" instead of "heart rate fell off a cliff".
                        series.bpmAt(start + (i * step).toLong())
                            ?.let { (((it - base) / span).toFloat()).coerceIn(0f, 1f) }
                    },
                    peakBpm = series.maxBpm.toInt()
                )
            }
        )
    }

    private fun scaleFor(low: Double, high: Double): IntRange = SparkScale.of(low, high)

    fun setLoadingClips() {
        _loadingClips.value = true
    }

    /**
     * Ticks or unticks everything at once.
     *
     * Only clips with somebody recording during them are ticked — selecting all should not select
     * a clip that would produce an export with an empty graph over it.
     */
    fun setAllClipsSelected(selected: Boolean) {
        _clips.value = _clips.value.map {
            it.copy(selected = selected && it.recordIds.isNotEmpty(), untickedByTime = false)
        }
    }

    fun toggleClip(uri: android.net.Uri) {
        _clips.value = _clips.value.map {
            if (it.clip.uri == uri) it.copy(selected = !it.selected, untickedByTime = false) else it
        }
    }

    /** Adds or removes one person's recording from a single clip's overlay. */
    fun toggleRecordOnClip(uri: android.net.Uri, recordId: Long) {
        _clips.value = _clips.value.map { selection ->
            if (selection.clip.uri != uri) return@map selection
            selection.copy(
                recordIds = if (recordId in selection.recordIds) {
                    selection.recordIds - recordId
                } else {
                    selection.recordIds + recordId
                }
            )
        }
    }

    /**
     * The jobs this export will queue.
     *
     * One per ticked clip, each carrying its own clip and its own recordings — the unit is the
     * video, not the event. A clip with nobody on it is dropped: it would render the background
     * back out with an empty graph over it.
     */
    val pendingJobs: StateFlow<List<ClipSelection>> = clips
        // Derived from the sorted list rather than the raw one, so jobs enter the queue in the
        // order they were shown in — a queue that disagrees with the list above it is confusing
        // for no benefit.
        .map { list -> list.filter { it.selected && it.recordIds.isNotEmpty() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _mediaAccessRefused = MutableStateFlow(false)

    /**
     * True when the gallery cannot be read, as opposed to holding nothing.
     *
     * The distinction the contents step could not previously draw. A MediaStore query made without
     * permission returns an empty cursor rather than failing, so a refused grant and an evening
     * nobody filmed produced identical screens — and the screen said the reassuring one. A fresh
     * install has no runtime grants whatever the manifest declares, so this is the state every
     * reinstall lands in until someone is asked.
     */
    val mediaAccessRefused: StateFlow<Boolean> = _mediaAccessRefused.asStateFlow()

    /** @param refused true once the request has been answered with a no. */
    fun setMediaAccessRefused(refused: Boolean) {
        _mediaAccessRefused.value = refused
        // Nothing is loading any more either way — a refusal ends the lookup as surely as a result
        // does, and leaving the spinner up would make a decision look like a hang.
        if (refused) _loadingClips.value = false
    }

    /**
     * True when the source has no video behind it at all.
     *
     * Not an error. `VideoExporter` renders against a solid background when no overlay is given,
     * which is the right output for a session nobody filmed — so the step says so and moves on
     * rather than blocking. Distinct from [mediaAccessRefused], which *is* worth acting on: it
     * takes precedence in the step, because "you have no footage" is a false statement to make to
     * someone whose gallery has simply not been looked at.
     */
    val hasNoClips: StateFlow<Boolean> = combine(
        _clips,
        _loadingClips,
        _mediaAccessRefused
    ) { clips, loading, refused ->
        !loading && !refused && clips.isEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _kind = MutableStateFlow(ExportKind.VIDEO)
    val kind: StateFlow<ExportKind> = _kind.asStateFlow()

    /**
     * Why Next can't be pressed yet, or null when it can.
     *
     * A video with footage found but none ticked used to go on to step 4 and offer the whole span
     * over a plain background instead: hours of video, for a collection (Audit #20). With no
     * footage at all, the plain background is the export, and nothing blocks it.
     */
    val nextBlockedReason: StateFlow<String?> = combine(
        _step, records, _kind, _clips,
        combine(_imageCrop, _cropTypingProblem) { crop, typed -> typed ?: crop.problem }
    ) { step, records, kind, clips, cropProblem ->
        when {
            step == ExportStep.SOURCE && records.isEmpty() -> "Choose what to export."
            step == ExportStep.CONTENTS && kind == ExportKind.IMAGE && cropProblem != null -> cropProblem
            step == ExportStep.CONTENTS && kind == ExportKind.VIDEO &&
                clips.isNotEmpty() && clips.none { it.selected } ->
                "Tick at least one clip to export."
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether the current step's question has been answered well enough to move on. */
    val canAdvance: StateFlow<Boolean> = nextBlockedReason.map { it == null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun goTo(step: ExportStep) {
        // Only backwards, or forwards into ground already covered. Jumping to step 3 without a
        // source would ask how something should look before knowing what it is.
        // Forward only past a step that would let Next through, so the step bar isn't a way
        // around "tick at least one clip".
        val forward = step.ordinal > _step.value.ordinal
        if (forward && nextBlockedReason.value != null) return
        if (step.ordinal <= _furthestStep.value.ordinal) _step.value = step
    }

    fun next() {
        val next = ExportStep.entries.getOrNull(_step.value.ordinal + 1) ?: return
        _step.value = next
        if (next.ordinal > _furthestStep.value.ordinal) _furthestStep.value = next
    }

    fun back() {
        ExportStep.entries.getOrNull(_step.value.ordinal - 1)?.let { _step.value = it }
    }

    /**
     * Points the utility at something else, and forgets everything that described the last thing.
     *
     * This ViewModel is hoisted above the nav host so an entry point can prime it before
     * navigating, which means it outlives any one export. Anything content-specific left behind
     * therefore lands on the *next* export: a title typed for one event captioned every image after
     * it, a clock window narrowed for one set silently cropped the next, and a video picked as a
     * backdrop reattached itself to an unrelated recording.
     *
     * The look — the preset, the framing, the canvas — is deliberately *not* reset. That is the
     * part worth keeping between exports, and keeping it is the point of the whole feature.
     */
    fun setSource(source: ExportSource) {
        if (source != _source.value) {
            labelOverride.value = null
            _imageTitle.value = null
            _imageCrop.value = ImageCrop()
            _cropTypingProblem.value = null
            _manualOverlay.value = null
            _clips.value = emptyList()
            clipsFoundFor = null
            _previewAt.value = 0f
            savedAnalysisRecordIds.value = emptySet()
            savedAnalysisName.value = ""
        }
        _source.value = source
        if (source is ExportSource.SavedAnalysis) loadSavedAnalysis(source.analysisId)
    }

    fun setKind(kind: ExportKind) {
        _kind.value = kind
    }

    private val _imageGrouping = MutableStateFlow(ImageGrouping.PER_EVENT)
    val imageGrouping: StateFlow<ImageGrouping> = _imageGrouping.asStateFlow()

    fun setImageGrouping(grouping: ImageGrouping) {
        _imageGrouping.value = grouping
    }

    /**
     * Which recordings each image will draw.
     *
     * One list per image. A group split per event gives one entry per event; everything else gives
     * a single entry, because one timeline is one image however many people are on it.
     */
    val imagePlan: StateFlow<List<ImagePlanEntry>> = combine(
        records,
        _source,
        _imageGrouping,
        // The whole tree: an image's label is the path down to its event, and a walk that cannot
        // see the containers stops at the first one it does not know about.
        repository.allEventsInTree,
        scopeTitle
    ) { records, source, grouping, events, title ->
        ImagePlanEntry.plan(
            records = records,
            events = events,
            // Only a group can become more than one image; everything else is one timeline.
            splitByEvent = source is ExportSource.Collection && grouping == ImageGrouping.PER_EVENT,
            scopeTitle = title
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Opens the utility part-answered.
     *
     * The existing entry points — a tile's Export video, an event page — already know their source,
     * and making them walk through step 1 to say something they have just said would be a
     * regression on a flow that is currently two taps.
     */
    fun startAt(
        source: ExportSource,
        // Contents, not Look: an entry point answers step 1 and nothing more. Landing on step 3
        // would accept defaults for a question the user was never asked.
        step: ExportStep = ExportStep.CONTENTS,
        /**
         * What to call it, when the caller knows better than the source does.
         *
         * A saved analysis exported from its own screen arrives as a set of recordings, which
         * would otherwise be labelled "4 recordings" and lose the name that was the point of
         * saving it.
         */
        label: String? = null
    ) {
        setSource(source)
        labelOverride.value = label
        _step.value = step
        if (step.ordinal > _furthestStep.value.ordinal) _furthestStep.value = step
    }


    private fun loadSavedAnalysis(analysisId: Long) {
        launchGuarded(failure = "Couldn't open that saved analysis.") {
            val loaded = repository.loadSavedAnalysis(analysisId)
            savedAnalysisRecordIds.value = loaded?.records?.map { it.recordId }?.toSet().orEmpty()
            savedAnalysisName.value = loaded?.metadata?.name.orEmpty()
        }
    }

    private companion object {
        /** Enough to read the shape of a clip in a list row, cheap enough for forty of them. */
        const val SPARK_SAMPLES = 48
    }

    class Factory(
        private val repository: LibraryRepository,
        private val settingsRepository: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ExportUtilityViewModel(repository, settingsRepository) as T
    }
}
