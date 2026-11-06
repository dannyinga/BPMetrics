package inga.bpmetrics.ui.detail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.library.CollectionEntity
import inga.bpmetrics.library.Cover
import inga.bpmetrics.library.CoverResolver
import inga.bpmetrics.library.EffectiveTagsResolver
import inga.bpmetrics.library.EventEntity
import inga.bpmetrics.library.EventTree
import inga.bpmetrics.library.Inherited
import inga.bpmetrics.library.ItemRef
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.LocationResolver
import inga.bpmetrics.library.TagEntity
import inga.bpmetrics.library.TimeSpan
import inga.bpmetrics.library.displayName
import inga.bpmetrics.library.location
import inga.bpmetrics.ui.components.clearCoverOn
import inga.bpmetrics.ui.components.cropCoverOn
import inga.bpmetrics.ui.components.setCoverOn
import inga.bpmetrics.ui.util.ReaderClock
import inga.bpmetrics.util.launchGuarded
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * What a detail page's header says about its subject, whatever kind of thing it is.
 *
 * The figures are not here: they belong to the scope, and come from the page's analysis so they
 * respect the person filter and anything left out (S15.4).
 */
data class DetailSubject(
    val title: String = "",
    /** The picture shown: its own, or the one it inherits. */
    val cover: Cover? = null,
    /** Its own picture, which is what framing and clearing change. */
    val ownCover: Cover? = null,
    /** The way out: an event's ancestors, outermost first, or the events a collection names. */
    val trail: List<Pair<Long, String>> = emptyList(),
    /** The clock its times read in: its venue's, else the reader's. */
    val clock: java.time.ZoneId = ReaderClock,
    val placeName: String? = null,
    val event: EventEntity? = null,
    val collection: CollectionEntity? = null,
    /** Whether anything is nested inside, which decides whether there is an Events tab. */
    val hasChildEvents: Boolean = false,
    /** What its contents cover, unfiltered, for the editor's suggested window. */
    val span: TimeSpan? = null,
    /** Everything in it, unfiltered, for the editor. The header counts come from the analysis. */
    val recordCount: Int = 0,
    val isLoading: Boolean = true,
    val exists: Boolean = false
) {
    /** Deleted out from under the page, so it can leave rather than sit empty. */
    val missing: Boolean get() = !isLoading && !exists
}

/**
 * One view model for the recording, event and collection pages (S15.1).
 *
 * What every subject has in common: its header, its tags, and its cover. The event page had a view
 * model of its own, the collection page borrowed the library's and searched its whole list of
 * collections for one, and each page wrapped the same cover calls its own way. The recording's
 * view model builds on this one, adding what only a recording has: its readings, its editor and
 * splitting.
 *
 * Editing an event or a collection still goes through the library's editor until S16 gives each
 * kind one editor.
 */
open class ItemDetailViewModel(
    protected val repository: LibraryRepository,
    val ref: ItemRef
) : ViewModel() {

    val subject: StateFlow<DetailSubject> = when (ref) {
        is ItemRef.Event -> eventSubject(ref.eventId)
        is ItemRef.Collection -> collectionSubject(ref.collectionId)
        // A recording's header is built by the recording's own view model, from its readings.
        is ItemRef.Recording -> flowOf(DetailSubject(isLoading = false, exists = true))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailSubject())

    private fun eventSubject(eventId: Long): Flow<DetailSubject> = combine(
        repository.allEventsInTree,
        repository.records,
        repository.getAllLocations()
    ) { tree, rows, places ->
        val event = tree.firstOrNull { it.eventId == eventId }
            ?: return@combine DetailSubject(isLoading = false, exists = false)
        // The subtree: a festival's page covers the recordings inside its days.
        val within = EventTree.descendantsOf(tree, eventId)
        val mine = rows.filter { it.metadata.eventId in within }
        val venue = LocationResolver.forEvent(eventId, tree, places.associateBy { it.locationId })
        DetailSubject(
            title = event.displayName,
            // Nearest wins up the tree, as on the library card.
            cover = CoverResolver.of(event, tree)?.value,
            ownCover = event.ownCover,
            // Outermost first, without the event itself: it is the title.
            trail = EventTree.ancestryOf(tree, eventId)
                .filter { it.eventId != eventId }
                .reversed()
                .map { it.eventId to it.displayName },
            clock = venue?.location?.timeZoneId
                ?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
                ?: ReaderClock,
            placeName = venue?.location?.displayName,
            event = event,
            hasChildEvents = tree.any { it.parentId == eventId },
            span = mine.takeIf { it.isNotEmpty() }?.let { r ->
                TimeSpan(r.minOf { it.metadata.startTime }, r.maxOf { it.metadata.endTime })
            },
            recordCount = mine.size,
            isLoading = false,
            exists = true
        )
    }

    private fun collectionSubject(collectionId: Long): Flow<DetailSubject> = combine(
        repository.getAllCollections(),
        repository.allCollectionEventLinks(),
        repository.allEventsInTree
    ) { sets, links, tree ->
        val set = sets.firstOrNull { it.collectionId == collectionId }
            ?: return@combine DetailSubject(isLoading = false, exists = false)
        val named = links.filter { it.collectionId == collectionId }.map { it.eventId }.toSet()
        DetailSubject(
            title = set.displayName,
            cover = set.ownCover,
            ownCover = set.ownCover,
            // The events it names, which is also the way into them. A collection doesn't nest, so
            // its trail points down rather than up.
            trail = tree.filter { it.eventId in named }.map { it.eventId to it.displayName },
            collection = set,
            isLoading = false,
            exists = true
        )
    }

    /**
     * Tags on an event, with the ones it inherits from above. Empty for a collection, which has
     * none; a recording's come from its own view model, which knows its siblings' rules.
     */
    open val tags: StateFlow<List<Inherited<TagEntity>>> = when (ref) {
        is ItemRef.Event -> combine(
            repository.tagsOf(ref),
            repository.allEventTags,
            repository.allEventsInTree
        ) { own, eventTags, events ->
            EffectiveTagsResolver.resolve(
                // Its own tags are direct here, the level they were applied at; the ancestry
                // includes the event itself so its parent isn't taken for it.
                directTags = own,
                ancestry = EventTree.ancestryOf(events, ref.eventId).map { it.eventId },
                eventTags = eventTags
            )
        }
        else -> flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Takes a tag off an event, from the chip on its header, with an undo. */
    open fun removeTag(tagId: Long) {
        val eventRef = ref as? ItemRef.Event ?: return
        launchGuarded(failure = "Couldn't remove that tag.") {
            val name = repository.getTag(tagId)?.name ?: "Tag"
            repository.removeTag(eventRef, tagId)
            inga.bpmetrics.ui.feedback.Feedback.undoable("Removed $name from this event.") {
                repository.addTag(eventRef, tagId)
            }
        }
    }

    // --- The cover (S15.2), the one way all three pages change it ---

    /** Gives this item the picture at [source]; [onResult] says whether it was stored. */
    fun setCover(context: Context, source: Uri, onResult: (Boolean) -> Unit) =
        setCoverOn(repository, context, ref, source, then = ::afterCoverChange, onResult = onResult)

    /** Re-frames the picture it already has. */
    fun cropCover(cover: Cover) = cropCoverOn(repository, ref, cover, then = ::afterCoverChange)

    /** Takes its own picture off, so it shows what it inherits. */
    fun clearCover(context: Context) = clearCoverOn(repository, context, ref, then = ::afterCoverChange)

    /** For a view model holding its own copy of the item, to reload it after the cover changes. */
    protected open suspend fun afterCoverChange() {}

    class Factory(
        private val repository: LibraryRepository,
        private val ref: ItemRef
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ItemDetailViewModel(repository, ref) as T
    }
}
