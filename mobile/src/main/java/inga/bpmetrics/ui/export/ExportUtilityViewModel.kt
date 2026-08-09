package inga.bpmetrics.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.library.BpmRecord
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
 * Asked in step 1, because it changes what step 2 is *for*. A video export picks which clips to
 * draw on; an image has no clips and asks a different question — which of the recordings in scope
 * share a timeline. The four-step shape cannot express that without being told up front.
 */
enum class ExportKind(val label: String, val description: String) {
    VIDEO("Video", "Curves drawn over footage, rendered in the background"),
    IMAGE("Image", "The whole timeline in one frame, saved straight away")
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
}

/** One image, and what goes on it. */
data class ImagePlanEntry(
    val label: String,
    val records: List<inga.bpmetrics.library.BpmRecord>,
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
            records: List<inga.bpmetrics.library.BpmRecord>,
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
                        label = event.displayName,
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
    data class Group(val groupId: Long) : ExportSource

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
     * What everyone's heart rate did during this clip, for deciding whether it is worth overlaying.
     *
     * A filename and a timestamp say nothing about whether the moment was any good. A glance at the
     * shape does: a clip where three curves all climb is the one to export, and a flat one is not.
     */
    val sparks: List<ClipSpark> = emptyList(),
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
    val scrubAt: Float = 0f
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
        fun of(preset: ExportPreset): GraphPlacement {
            val stored = GraphPlacement(
                preset.graphLeft,
                preset.graphTop,
                preset.graphRight,
                preset.graphBottom
            )
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
    /** Evenly sampled across the clip, 0..1. Null where that person was not recording. */
    val points: List<Float?>,
    val peakBpm: Int
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
    val approxRenderMs: Long
)

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
    val records: StateFlow<List<BpmRecord>> = combine(
        repository.records,
        _source,
        repository.allEventsInTree
    ) { library, source, events ->
        when (source) {
            is ExportSource.None -> emptyList()
            is ExportSource.Recordings -> library.filter { it.metadata.recordId in source.recordIds }
            is ExportSource.Event -> library.filter { it.metadata.eventId == source.eventId }
            is ExportSource.Group -> {
                // The whole subtree, walked over the whole tree, and including the container
                // itself. Both halves matter, and both were wrong: walking a collections-only list
                // missed any event nested under another event, and taking only the events beneath
                // the container missed recordings filed straight onto it. Either way the export
                // came out short without saying so.
                val inScope = inga.bpmetrics.library.EventTree
                    .descendantsOf(events, source.groupId)
                library.filter { it.metadata.eventId in inScope }
            }
            // Resolved by id against the library, so a recording deleted since the analysis was
            // saved simply is not offered — an export cannot render a curve that is gone.
            is ExportSource.SavedAnalysis -> savedAnalysisRecordIds.value
                .let { ids -> library.filter { it.metadata.recordId in ids } }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val savedAnalysisRecordIds = MutableStateFlow<Set<Long>>(emptySet())

    /** What the export will be called, taken from whatever named the source. */
    val sourceLabel: StateFlow<String> = combine(
        _source,
        repository.getAllEvents(),
        repository.getAllEventGroups(),
        labelOverride
    ) { source, events, groups, override ->
        override ?: when (source) {
            is ExportSource.None -> ""
            is ExportSource.Recordings ->
                "${source.recordIds.size} recording${if (source.recordIds.size == 1) "" else "s"}"
            is ExportSource.Event ->
                events.firstOrNull { it.eventId == source.eventId }?.displayName.orEmpty()
            is ExportSource.Group ->
                groups.firstOrNull { it.eventId == source.groupId }?.displayName.orEmpty()
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
        repository.getAllEvents(),
        repository.getAllEventGroups(),
        combine(repository.getAllPeople(), labelOverride) { people, override -> people to override }
    ) { source, records, events, groups, (people, override) ->
        override?.takeIf { it.isNotBlank() } ?: when (source) {
            is ExportSource.None -> ""
            is ExportSource.Event ->
                events.firstOrNull { it.eventId == source.eventId }?.displayName.orEmpty()
            is ExportSource.Group ->
                groups.firstOrNull { it.eventId == source.groupId }?.displayName.orEmpty()
            is ExportSource.SavedAnalysis -> savedAnalysisName.value
            is ExportSource.Recordings -> {
                val single = records.singleOrNull()
                if (single != null) {
                    // The name shown everywhere else for this recording, generated one included,
                    // so the picture is captioned the way the library labels it.
                    single.displayName(
                        people.firstOrNull { it.personId == single.metadata.personId }?.displayName
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
     * Which preset the current settings came from, or null once they have drifted.
     *
     * What makes "Update to current settings" safe to offer: it only appears while the settings
     * still *are* that preset, so it cannot silently absorb changes the user never associated
     * with it.
     */
    private val _selectedPresetId = MutableStateFlow<Long?>(null)
    val selectedPresetId: StateFlow<Long?> = _selectedPresetId.asStateFlow()

    /**
     * Restores the default preset, or the settings last used.
     *
     * Distinct things: a default is a preset someone chose to be pre-selected, while last-used is
     * simply where they left off. The default wins when there is one, because it was an explicit
     * decision and the other is a side effect.
     */
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
        viewModelScope.launch {
            val default = repository.getDefaultExportPreset()
            if (default != null) {
                ExportPreset.fromJson(default.configJson)?.let {
                    _preset.value = it
                    _selectedPresetId.value = default.presetId
                }
                return@launch
            }
            settingsRepository.lastUsedExportPreset()
                ?.let { ExportPreset.fromJson(it) }
                ?.let { _preset.value = it }
        }
    }

    fun applyPreset(entity: ExportPresetEntity) {
        ExportPreset.fromJson(entity.configJson)?.let {
            _preset.value = it
            _selectedPresetId.value = entity.presetId
        }
    }

    /**
     * What to call the look a job was queued with.
     *
     * The applied preset's name, or the canvas shape when the settings have drifted off one — a
     * queue row saying "1080×1920" is still more use than one saying nothing.
     */
    fun presetLabel(): String {
        val named = _preset.value.name.takeIf { it.isNotBlank() && _selectedPresetId.value != null }
        return named ?: "${_preset.value.width}×${_preset.value.height}"
    }

    /** Records that the settings have moved away from whichever preset was applied. */
    fun setPreset(preset: ExportPreset) {
        _preset.value = preset
        _selectedPresetId.value = null
    }

    /**
     * Saves the current look, including the framing being looked at.
     *
     * The framing is passed in rather than read from the preset, because while a clip is being
     * previewed the framing on screen is that clip's override — and that is the one the user means
     * when they save.
     */
    fun savePresetAs(name: String, framing: GraphPlacement) {
        viewModelScope.launch {
            val toSave = framing.into(_preset.value).copy(name = name)
            _preset.value = toSave
            _selectedPresetId.value = repository.saveExportPreset(name, toSave.toJson())
        }
    }

    fun updatePreset(entity: ExportPresetEntity, framing: GraphPlacement) {
        viewModelScope.launch {
            val toSave = framing.into(_preset.value).copy(name = entity.name)
            _preset.value = toSave
            repository.updateExportPreset(entity.presetId, entity.name, toSave.toJson())
        }
    }

    fun setDefaultPreset(entity: ExportPresetEntity) {
        viewModelScope.launch { repository.setDefaultExportPreset(entity.presetId) }
    }

    fun deletePreset(entity: ExportPresetEntity) {
        viewModelScope.launch {
            repository.deleteExportPreset(entity.presetId)
            if (_selectedPresetId.value == entity.presetId) _selectedPresetId.value = null
        }
    }

    /** Remembers where the user left off, for the next export that has no default to fall back on. */
    fun rememberLastUsed() {
        viewModelScope.launch {
            settingsRepository.setLastUsedExportPreset(_preset.value.toJson())
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
        viewModelScope.launch {
            val name = imported.name.ifBlank { "Imported preset" }
            val id = repository.saveExportPreset(name, imported.copy(name = name).toJson())
            _preset.value = imported
            _selectedPresetId.value = id
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
     * Builds the config for one export.
     *
     * The single place a [VideoExporter.VideoExportConfig] is assembled: appearance from the
     * preset, content from the arguments. Nothing else constructs one, so a batched export and a
     * one-off cannot drift apart.
     */
    fun buildConfig(
        forRecords: List<BpmRecord>,
        overlay: android.net.Uri?,
        colours: Map<Long, Int>,
        /** Wearers' faces for the pills, already decoded and cropped. See `recordPhotos`. */
        photos: Map<Long, android.graphics.Bitmap>,
        title: String?,
        /** The clip this is drawn over, when there is one. Decides the sync and the crop. */
        clip: VideoExporter.VideoClip? = null,
        placement: GraphPlacement? = null
    ): VideoExporter.VideoExportConfig {
        // Always clock-aligned. A recording happened at a particular time, and starting it at 0:00
        // of a video only ever looked right when the two happened to begin together.
        val timeline = inga.bpmetrics.export.ImageExporter.timelineFor(forRecords, false)

        // The clip's own window on the shared timeline. Without this the export spans the whole
        // recording and the footage drifts out of sync within seconds.
        val window = clip?.windowOn(timeline, _preset.value.syncOffsetMs)
        val startMs = window?.startMs ?: 0L
        val endMs = window?.endMs ?: timeline.durationMs

        val base = VideoExporter.VideoExportConfig(
            imageConfig = inga.bpmetrics.export.ImageExporter.ImageExportConfig(
                startTimeMs = startMs,
                endTimeMs = endMs.coerceAtLeast(startMs + 1000L),
                customRecordColors = colours,
                recordPhotos = photos,
                graphTitle = title,
                alignByElapsedTime = false
            ),
            overlayVideoUri = overlay,
            // The render aligns against exactly what the picker resolved and the preview drew.
            overlayStartedAtMs = clip?.startedAtMs,
            records = forRecords
        )

        return _preset.value.applyTo(base).let { applied ->
            applied.copy(
                // The clip's own framing when it has been given one, otherwise whatever the
                // preset carries — framing is part of a look, so a preset has to bring its own.
                graphRect = (placement ?: GraphPlacement.of(_preset.value)).toRectF()
            )
        }
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
            width = preset.width,
            height = preset.height,
            title = titleOverride ?: entry.label,
            windowStartWallClockMs = windowStart,
            windowEndWallClockMs = windowEnd,
            series = series,
            sections = sections,
            showTitle = preset.showTitle,
            showGrid = preset.showGrid,
            showLabels = preset.showLabels,
            showStats = preset.showCurrentStats,
            lowBpmColor = preset.lowBpmColor,
            highBpmColor = preset.highBpmColor,
            labelsColor = preset.labelsColor,
            gridColor = preset.gridColor,
            backgroundOpacity = preset.backgroundOpacity,
            timeZoneId = preset.timeZoneId,
            showWordmark = preset.showWordmark,
            wordmarkCorner = preset.wordmarkCorner,
            wordmarkOpacity = preset.wordmarkOpacity
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
     * Roughly how large the queued exports will be, and how long they will take.
     *
     * Bitrate times duration, which is what the encoder is actually bounded by. Deliberately
     * approximate and labelled as such — the alternative is finding out after twenty minutes of
     * rendering that there was no room, which this app has already done to someone once.
     */
    fun estimate(jobs: List<ClipSelection>, fallbackDurationMs: Long): ExportEstimate {
        val preset = _preset.value
        val totalMs = if (jobs.isEmpty()) {
            fallbackDurationMs
        } else {
            jobs.sumOf { it.clip.durationMs }
        }
        val bitsPerSecond = if (jobs.isEmpty()) preset.regularBitRate else preset.overlayBitRate
        val bytes = (totalMs / 1000.0) * (bitsPerSecond / 8.0)

        return ExportEstimate(
            jobCount = jobs.size.coerceAtLeast(1),
            totalDurationMs = totalMs,
            approxBytes = bytes.toLong(),
            // Encoding runs slower than real time on a phone, and more so with an overlay. A
            // deliberately pessimistic multiplier: an estimate that undershoots is the one that
            // makes people think it has hung.
            approxRenderMs = (totalMs * if (jobs.isEmpty()) 1.5 else 3.0).toLong()
        )
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

        // The rows appear immediately with everything except the curves, which need every data
        // point in range walked. Waiting for that before showing anything would leave the step
        // blank for a second on a group with forty clips.
        _clips.value = found.map { clip ->
            ClipSelection(
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
        }
        _loadingClips.value = false

        viewModelScope.launch {
            val people = repository.getAllPeople().first()
            val watches = repository.getAllWatches().first()
            val withSparks = withContext(Dispatchers.Default) {
                _clips.value.map { selection ->
                    selection.copy(
                        sparks = sparkFor(selection, sourceRecords, watches, people)
                    )
                }
            }
            // Re-read rather than overwrite blindly: the user may have unticked something while
            // this was running, and their taps outrank a background computation.
            _clips.value = _clips.value.map { current ->
                withSparks.firstOrNull { it.clip.uri == current.clip.uri }
                    ?.let { current.copy(sparks = it.sparks) }
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
        allRecords: List<BpmRecord>,
        watches: List<inga.bpmetrics.library.WatchEntity>,
        people: List<inga.bpmetrics.library.PersonEntity>
    ): List<ClipSpark> {
        val theirs = allRecords.filter { it.metadata.recordId in selection.recordIds }
        if (theirs.isEmpty()) return emptyList()

        val analysis = EventAnalysis.from(
            records = theirs,
            watches = watches,
            people = people,
            window = selection.clip.startedAtMs..selection.clip.endedAtMs
        )
        if (analysis.isEmpty) return emptyList()

        // One range for everyone, so the lines keep their relative heights.
        val low = analysis.series.minOf { it.minBpm }
        val high = analysis.series.maxOf { it.maxBpm }
        val span = (high - low).coerceAtLeast(1.0)

        val start = selection.clip.startedAtMs
        val step = (selection.clip.durationMs.toDouble() / (SPARK_SAMPLES - 1)).coerceAtLeast(1.0)

        return analysis.series.map { series ->
            ClipSpark(
                label = series.label,
                colorArgb = series.colorArgb,
                points = (0 until SPARK_SAMPLES).map { i ->
                    // Null inside a dropout rather than a floor value, so a break in the line
                    // reads as "not measured" instead of "heart rate fell off a cliff".
                    series.bpmAt(start + (i * step).toLong())
                        ?.let { (((it - low) / span).toFloat()).coerceIn(0f, 1f) }
                },
                peakBpm = series.maxBpm.toInt()
            )
        }
    }

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
            it.copy(selected = selected && it.recordIds.isNotEmpty())
        }
    }

    fun toggleClip(uri: android.net.Uri) {
        _clips.value = _clips.value.map {
            if (it.clip.uri == uri) it.copy(selected = !it.selected) else it
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

    /**
     * True when the source has no video behind it at all.
     *
     * Not an error. `VideoExporter` renders against a solid background when no overlay is given,
     * which is the right output for a session nobody filmed — so the step says so and moves on
     * rather than blocking.
     */
    val hasNoClips: StateFlow<Boolean> = combine(_clips, _loadingClips) { clips, loading ->
        !loading && clips.isEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Whether the current step's question has been answered well enough to move on. */
    val canAdvance: StateFlow<Boolean> = combine(_step, records) { step, records ->
        when (step) {
            ExportStep.SOURCE -> records.isNotEmpty()
            // Contents and Look both have workable defaults, so neither can block the flow.
            else -> true
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun goTo(step: ExportStep) {
        // Only backwards, or forwards into ground already covered. Jumping to step 3 without a
        // source would ask how something should look before knowing what it is.
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
            _manualOverlay.value = null
            _clips.value = emptyList()
            _previewAt.value = 0f
            savedAnalysisRecordIds.value = emptySet()
            savedAnalysisName.value = ""
        }
        _source.value = source
        if (source is ExportSource.SavedAnalysis) loadSavedAnalysis(source.analysisId)
    }

    private val _kind = MutableStateFlow(ExportKind.VIDEO)
    val kind: StateFlow<ExportKind> = _kind.asStateFlow()

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
        repository.getAllEvents(),
        scopeTitle
    ) { records, source, grouping, events, title ->
        ImagePlanEntry.plan(
            records = records,
            events = events,
            // Only a group can become more than one image; everything else is one timeline.
            splitByEvent = source is ExportSource.Group && grouping == ImageGrouping.PER_EVENT,
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
        label: String? = null,
        /** Video or image. A caller that knows which button was pressed knows this too. */
        kind: ExportKind = ExportKind.VIDEO
    ) {
        setSource(source)
        labelOverride.value = label
        _kind.value = kind
        _step.value = step
        if (step.ordinal > _furthestStep.value.ordinal) _furthestStep.value = step
    }


    private fun loadSavedAnalysis(analysisId: Long) {
        viewModelScope.launch {
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
