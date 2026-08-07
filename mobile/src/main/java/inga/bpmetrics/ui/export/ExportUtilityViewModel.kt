package inga.bpmetrics.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
        repository.getAllEventGroups()
    ) { source, events, groups ->
        when (source) {
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
    fun startAt(source: ExportSource, step: ExportStep = ExportStep.LOOK) {
        setSource(source)
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

    class Factory(private val repository: LibraryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ExportUtilityViewModel(repository) as T
    }
}
