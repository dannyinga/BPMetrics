package inga.bpmetrics.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.export.VideoExporter
import inga.bpmetrics.ui.analysis.EventAnalysis
import inga.bpmetrics.library.LibraryRepository
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
    val sparks: List<ClipSpark> = emptyList()
) {
    /** The highest anyone reached during the clip. Null when nobody was recording. */
    val peakBpm: Int? get() = sparks.maxOfOrNull { it.peakBpm }
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
 * Drives the export utility.
 *
 * Holds the step and the source rather than the export settings themselves — those still live in
 * [VideoExportViewModel] for now, and moving them here is EXP-4.1's job, once step 3 is
 * reorganised into sections and the settings have somewhere to go.
 */
class ExportUtilityViewModel(
    private val repository: LibraryRepository
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
        repository.getAllEvents()
    ) { library, source, events ->
        when (source) {
            is ExportSource.None -> emptyList()
            is ExportSource.Recordings -> library.filter { it.metadata.recordId in source.recordIds }
            is ExportSource.Event -> library.filter { it.metadata.eventId == source.eventId }
            is ExportSource.Group -> {
                val eventIds = events.filter { it.groupId == source.groupId }
                    .map { it.eventId }
                    .toSet()
                library.filter { it.metadata.eventId in eventIds }
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
                groups.firstOrNull { it.groupId == source.groupId }?.displayName.orEmpty()
            is ExportSource.SavedAnalysis -> savedAnalysisName.value
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val savedAnalysisName = MutableStateFlow("")


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
                // Ticked by default, because having found the clips the likely answer is "all of
                // them" and unticking is cheaper than ticking six.
                selected = true
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

    fun setSource(source: ExportSource) {
        _source.value = source
        if (source is ExportSource.SavedAnalysis) loadSavedAnalysis(source.analysisId)
    }

    /**
     * Opens the utility part-answered.
     *
     * The existing entry points — a tile's Export video, an event page — already know their source,
     * and making them walk through step 1 to say something they have just said would be a
     * regression on a flow that is currently two taps.
     */
    fun startAt(
        source: ExportSource,
        step: ExportStep = ExportStep.LOOK,
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

    class Factory(private val repository: LibraryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ExportUtilityViewModel(repository) as T
    }
}
