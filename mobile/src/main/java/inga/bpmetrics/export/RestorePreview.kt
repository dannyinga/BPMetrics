package inga.bpmetrics.export

import inga.bpmetrics.export.JsonExporter.toWatchRecords
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.ui.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * What to do with one thing in a file.
 *
 * [SKIP] on something already here means "keep mine": it isn't changed, and anything in the file
 * that refers to it still links to it. [SKIP] on something new means it isn't brought in.
 */
enum class RestoreChoice { ADD, REPLACE, SKIP }

/** The kinds of thing a file carries, in the order the preview lists them. */
enum class RestoreKind(val one: String, val many: String) {
    RECORDING("recording", "recordings"),
    EVENT("event", "events"),
    COLLECTION("collection", "collections"),
    PERSON("person", "people"),
    WATCH("watch", "watches"),
    VENUE("venue", "venues"),
    TAG("tag", "tags"),
    PRESET("export preset", "export presets"),
    SETTING("setting", "settings");

    /**
     * What may be chosen for an item of this kind. Adding a second copy of something already here
     * is offered only where two of them can both make sense: two events or collections can share a
     * name, but a second Danny or a second copy of one recording only splits what's there.
     * A setting is either kept or taken from the file.
     */
    fun options(matched: Boolean): List<RestoreChoice> = when {
        this == SETTING -> listOf(RestoreChoice.SKIP, RestoreChoice.REPLACE)
        !matched -> listOf(RestoreChoice.ADD, RestoreChoice.SKIP)
        this == EVENT || this == COLLECTION || this == PRESET ->
            listOf(RestoreChoice.SKIP, RestoreChoice.REPLACE, RestoreChoice.ADD)
        this == TAG -> listOf(RestoreChoice.SKIP)
        else -> listOf(RestoreChoice.SKIP, RestoreChoice.REPLACE)
    }
}

/**
 * One thing in the file, and whether the library already has it.
 *
 * @property key what identifies it in this file, as [RestoreKeys] makes it. The preview and the
 *   restore both key by it, which is what ties a choice to the item it was made on.
 * @property at when it happened, for recordings and events, so the list can say which "Day 1".
 */
data class RestoreItem(
    val kind: RestoreKind,
    val key: String,
    val label: String,
    val detail: String? = null,
    val at: Long? = null,
    val matched: Boolean,
    /** For a setting: this phone's stored value (null if never set) and the file's. */
    val from: String? = null,
    val to: String? = null
) {
    val options: List<RestoreChoice> get() = kind.options(matched)
    val default: RestoreChoice get() = RestoreChoices.defaultFor(kind, matched)
}

/** The choices made on a preview. What isn't in [overrides] takes the default. */
data class RestoreChoices(val overrides: Map<String, RestoreChoice> = emptyMap()) {

    fun of(kind: RestoreKind, key: String, matched: Boolean): RestoreChoice =
        overrides[id(kind, key)]?.takeIf { it in kind.options(matched) } ?: defaultFor(kind, matched)

    companion object {
        fun id(kind: RestoreKind, key: String) = "${kind.name}:$key"

        /** New things come in; what's here is kept as it is (Danny's call, S5.3). */
        fun defaultFor(kind: RestoreKind, matched: Boolean): RestoreChoice =
            if (matched || kind == RestoreKind.SETTING) RestoreChoice.SKIP else RestoreChoice.ADD
    }
}

/** How each kind of thing in a file is identified, shared by the preview and the restore. */
object RestoreKeys {
    fun recording(start: Long, end: Long, device: String) = "$start:$end:$device"
    fun person(name: String) = name.trim().lowercase()
    fun venue(name: String) = name.trim().lowercase()
    fun tag(qualified: String) = qualified.trim().lowercase()
    fun preset(name: String) = name.trim().lowercase()
    fun watch(id: String) = id

    /** By the id the event had where the file came from, or by name for a file older than ids. */
    fun event(dto: EventDto) = if (dto.eventId != 0L) "id:${dto.eventId}" else "name:${dto.name}"
    fun group(name: String) = "group:$name"

    /** By position: nothing else in a collection's file entry is unique. */
    fun collection(index: Int) = "set:$index"
    fun analysis(index: Int) = "analysis:$index"

    /** "Category:Tag" as restore files it; a bare name is uncategorised. */
    fun qualify(name: String): String {
        val category = name.substringBefore(':', "Uncategorized").ifBlank { "Uncategorized" }
        return "$category:${name.substringAfter(':', name).trim()}"
    }
}

/**
 * Which events in a file are ones this library already has.
 *
 * A name is not an identity: every festival has a "Day 1". An event counts as here when its name,
 * its place in the tree and the moment it was created all agree, which is what restoring a library
 * into itself looks like. A friend's "Bass Canyon" was created on their phone and stays new. Until
 * this, events were never matched, and restoring your own backup added every one again.
 */
object EventMatcher {

    data class Here(val eventId: Long, val name: String, val parentId: Long?, val createdAt: Long)

    /** File event id to the event here. Files written before ids (format 6) match nothing. */
    fun match(file: List<EventDto>, here: List<Here>): Map<Long, Long> {
        val bySignature = here.groupBy { it.name.trim().lowercase() to it.createdAt }
        val matched = mutableMapOf<Long, Long>()
        val taken = mutableSetOf<Long>()
        // Parents before children, however the file is ordered: repeat until nothing new matches.
        var changed = true
        while (changed) {
            changed = false
            file.forEach { dto ->
                if (dto.eventId == 0L || dto.createdAt <= 0L || dto.eventId in matched) return@forEach
                val parent = dto.parentEventId?.takeIf { it != 0L }
                // A child of something new can't be something here.
                val parentHere = if (parent == null) null else matched[parent] ?: return@forEach
                val candidate = bySignature[dto.name.trim().lowercase() to dto.createdAt]
                    ?.firstOrNull { it.parentId == parentHere && it.eventId !in taken }
                    ?: return@forEach
                matched[dto.eventId] = candidate.eventId
                taken += candidate.eventId
                changed = true
            }
        }
        return matched
    }
}

/**
 * What restoring a file would do, worked out before anything is written.
 *
 * A restore used to start the moment a file was picked, and say what it had done afterwards. Now
 * every item is listed, new or already here, so each can be added, replaced or skipped. Built by
 * [RestorePlanner] from the same matching rules [restoreBackup] uses, so the two agree.
 */
data class RestorePreview(
    val items: List<RestoreItem> = emptyList(),
    /** Recordings the file holds but that can't be read: no readings, or times out of order. */
    val unreadableRecordings: Int = 0
) {
    fun of(kind: RestoreKind) = items.filter { it.kind == kind }

    /** Whether these choices would change anything. */
    fun changesAnything(choices: RestoreChoices): Boolean = items.any {
        choices.of(it.kind, it.key, it.matched) != RestoreChoice.SKIP
    }
}

object RestorePlanner {

    /** What the library holds, as the restore matches against it. Names are lowercase. */
    data class Library(
        val recordIdentities: Set<Triple<Long, Long, String>> = emptySet(),
        val peopleNames: Set<String> = emptySet(),
        val watchIds: Set<String> = emptySet(),
        /** Ids merged into a watch here, and the Data Layer nodes watches here were last heard from. */
        val watchAliases: Set<String> = emptySet(),
        val watchNodes: Set<String> = emptySet(),
        val venueNames: Set<String> = emptySet(),
        /** "category:tag", lowercase. */
        val tags: Set<String> = emptySet(),
        val events: List<EventMatcher.Here> = emptyList(),
        /** Collections here, as name (lowercase) and creation time. */
        val collections: Set<Pair<String, Long>> = emptySet(),
        /** Preset name, lowercase, to its stored look. */
        val presets: Map<String, String> = emptyMap(),
        /** Stored settings by key, as [inga.bpmetrics.ui.settings.PreferenceSnapshot] writes them. */
        val settings: Map<String, String> = emptyMap()
    )

    /** Every tag here, as "category:tag", lowercase. */
    suspend fun tagsHere(repository: LibraryRepository): Set<String> {
        val categories = repository.getAllCategories().first().associate { it.categoryId to it.name }
        return repository.allTags.first()
            .map { RestoreKeys.tag("${categories[it.parentCategoryId] ?: "Uncategorized"}:${it.name}") }
            .toSet()
    }

    /** Reads what [preview] needs from the library. */
    suspend fun libraryOf(repository: LibraryRepository): Library {
        val watches = repository.getAllWatches().first()
        return Library(
            recordIdentities = repository.recordIdsByIdentity().keys,
            peopleNames = repository.getAllPeople().first().map { RestoreKeys.person(it.name) }.toSet(),
            watchIds = watches.map { it.watchId }.toSet(),
            watchAliases = repository.watchAliases().values.flatten().toSet(),
            watchNodes = watches.map { it.lastKnownNodeId }.filter { it.isNotBlank() }.toSet(),
            venueNames = repository.getAllLocations().first().map { RestoreKeys.venue(it.name) }.toSet(),
            tags = tagsHere(repository),
            events = repository.allEventsInTree.first()
                .map { EventMatcher.Here(it.eventId, it.name, it.parentId, it.createdAt) },
            collections = repository.getAllCollections().first()
                .map { it.name.trim().lowercase() to it.createdAt }.toSet(),
            presets = repository.getPresetsForBackup().associate { RestoreKeys.preset(it.name) to it.configJson },
            settings = repository.currentSettings().associate { it.key to it.value }
        )
    }

    /** Whether a collection in a file is one this library already has: same name, same creation. */
    fun collectionIsHere(dto: CollectionDto, here: Set<Pair<String, Long>>) =
        dto.createdAt > 0L && (dto.name.trim().lowercase() to dto.createdAt) in here

    fun preview(backup: LibraryBackup, library: Library): RestorePreview {
        val items = mutableListOf<RestoreItem>()

        // Recordings, as restoreBackup decides: unreadable ones are dropped, and one whose start,
        // end and watch are already here is that recording. The same one twice in a file is one.
        val seen = mutableSetOf<Triple<Long, Long, String>>()
        var readable = 0
        backup.records.forEach { dto ->
            val record = listOf(dto).toWatchRecords().firstOrNull() ?: return@forEach
            readable++
            val identity = Triple(record.startTime, record.endTime, record.deviceId)
            if (!seen.add(identity)) return@forEach
            items += RestoreItem(
                RestoreKind.RECORDING,
                RestoreKeys.recording(identity.first, identity.second, identity.third),
                label = dto.title.ifBlank { "Untitled" },
                detail = dto.eventName,
                at = dto.startTime,
                matched = identity in library.recordIdentities
            )
        }

        val eventsHere = EventMatcher.match(backup.events, library.events)
        val eventNames = backup.events.filter { it.eventId != 0L }.associate { it.eventId to it.name }
        backup.eventGroups.map { it.name }.distinct().forEach {
            items += RestoreItem(RestoreKind.EVENT, RestoreKeys.group(it), it, matched = false)
        }
        backup.events.distinctBy { RestoreKeys.event(it) }.forEach { dto ->
            items += RestoreItem(
                RestoreKind.EVENT,
                RestoreKeys.event(dto),
                label = dto.name,
                detail = dto.parentEventId?.let { eventNames[it] }?.let { "in $it" } ?: dto.parentName?.let { "in $it" },
                at = dto.windowStart,
                matched = dto.eventId in eventsHere
            )
        }

        backup.collections.forEachIndexed { i, set ->
            items += RestoreItem(
                RestoreKind.COLLECTION, RestoreKeys.collection(i), set.name,
                matched = collectionIsHere(set, library.collections)
            )
        }
        backup.savedAnalyses.forEachIndexed { i, analysis ->
            items += RestoreItem(RestoreKind.COLLECTION, RestoreKeys.analysis(i), analysis.name, matched = false)
        }

        backup.people.distinctBy { RestoreKeys.person(it.name) }.forEach {
            items += RestoreItem(
                RestoreKind.PERSON, RestoreKeys.person(it.name), it.name,
                matched = RestoreKeys.person(it.name) in library.peopleNames
            )
        }

        // Known by id, by an id merged into one here, or by node: the same watch, recognised.
        backup.watches.forEach { watch ->
            val known = (listOf(watch.watchId) + watch.aliases)
                .any { it in library.watchIds || it in library.watchAliases } ||
                (watch.lastKnownNodeId.isNotBlank() && watch.lastKnownNodeId in library.watchNodes)
            items += RestoreItem(
                RestoreKind.WATCH, RestoreKeys.watch(watch.watchId),
                label = watch.deviceName.ifBlank { watch.lastKnownModel.ifBlank { "Watch ${watch.watchId.take(4)}" } },
                detail = watch.wearerName?.let { "worn by $it" },
                matched = known
            )
        }

        backup.locations.distinctBy { RestoreKeys.venue(it.name) }.forEach {
            items += RestoreItem(
                RestoreKind.VENUE, RestoreKeys.venue(it.name), it.name,
                matched = RestoreKeys.venue(it.name) in library.venueNames
            )
        }

        // Only new tags are listed: one already here is just a name, with nothing to replace.
        (backup.eventGroups.flatMap { it.tags } +
            backup.events.flatMap { it.tags } +
            backup.records.flatMap { it.tags } +
            backup.collections.flatMap { set -> set.ruleNames?.tags.orEmpty().map { it.name } })
            .map(RestoreKeys::qualify)
            .distinctBy(RestoreKeys::tag)
            .filter { RestoreKeys.tag(it) !in library.tags }
            .forEach {
                items += RestoreItem(RestoreKind.TAG, RestoreKeys.tag(it), it.replace(":", " › "), matched = false)
            }

        backup.presets.filter { ExportPreset.fromJson(it.configJson) != null }
            .distinctBy { RestoreKeys.preset(it.name) }
            .forEach { preset ->
                val here = library.presets[RestoreKeys.preset(preset.name)]
                items += RestoreItem(
                    RestoreKind.PRESET, RestoreKeys.preset(preset.name), preset.name,
                    detail = if (here != null && here == preset.configJson) "the same as yours" else null,
                    matched = here != null
                )
            }

        // Only settings a person would recognise, and only ones that differ from this phone's.
        backup.settings
            .filter { it.key in SettingsRepository.RESTORABLE_KEYS && library.settings[it.key] != it.value }
            .forEach {
                items += RestoreItem(
                    RestoreKind.SETTING, it.key,
                    label = SettingsRepository.RESTORABLE_KEYS.getValue(it.key),
                    matched = true,
                    from = library.settings[it.key],
                    to = it.value
                )
            }

        return RestorePreview(items, unreadableRecordings = backup.records.size - readable)
    }
}
