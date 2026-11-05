package inga.bpmetrics.export

import android.util.Log
import inga.bpmetrics.export.JsonExporter.toWatchRecords
import inga.bpmetrics.library.LibraryRepository
import kotlinx.coroutines.flow.first

/**
 * What a restore did, so the UI can say something specific rather than "done".
 */
data class RestoreResult(
    val peopleCreated: Int = 0,
    val watchesCreated: Int = 0,
    val recordsImported: Int = 0,
    val recordsSkipped: Int = 0,
    val eventsCreated: Int = 0,
    val groupsCreated: Int = 0,
    val analysesRestored: Int = 0,
    val settingsRestored: Int = 0,
    val presetsRestored: Int = 0,
    /** Presets skipped because a newer build wrote them. Counted so the UI can say so. */
    val presetsSkipped: Int = 0,
    /** Recordings the library already had, so were linked to rather than added a second time. */
    val recordsAlreadyHere: Int = 0,
    /** New recordings left out by choice. */
    val recordsLeftOut: Int = 0,
    /** Things here that were overwritten with the file's version. */
    val itemsReplaced: Int = 0,
    /**
     * Smart collections whose rule named something this file couldn't identify, so the rule now
     * matches less than it did (often nothing). Always the case for a rule from a file written
     * before format 7, which didn't carry what its ids meant.
     */
    val rulesNeedingAttention: List<String> = emptyList(),
    val failure: String? = null
) {
    val succeeded: Boolean get() = failure == null

    /** One line for a toast or a snackbar. */
    fun summary(): String {
        if (!succeeded) return "Import failed: $failure"
        val parts = buildList {
            add("Imported $recordsImported recording${if (recordsImported == 1) "" else "s"}")
            if (itemsReplaced > 0) add("replaced $itemsReplaced")
            if (recordsAlreadyHere > 0) add("$recordsAlreadyHere already in your library")
            if (recordsLeftOut > 0) add("left out $recordsLeftOut")
            if (recordsSkipped > 0) add("$recordsSkipped unreadable")
            if (peopleCreated > 0) add("$peopleCreated new ${if (peopleCreated == 1) "person" else "people"}")
        }
        val rules = when (rulesNeedingAttention.size) {
            0 -> ""
            1 -> ". Check the rule of ${rulesNeedingAttention.single()}: part of it couldn't be restored."
            else -> ". Check the rules of ${rulesNeedingAttention.size} smart collections " +
                "(${rulesNeedingAttention.joinToString()}): part of each couldn't be restored."
        }
        return parts.joinToString(", ") + rules
    }
}

/**
 * Rebuilds a library from a backup file, doing with each item what [choices] says.
 *
 * Order matters. People and watches are created first, because a record's link to them is resolved
 * during ingest — `saveWatchRecordToLibrary` looks a person up by name and a watch up by id, and
 * finding neither would import the recordings as unattributed and quietly lose who made them.
 *
 * Everything is matched against what's here, by the rules [RestorePlanner] shows in the preview:
 * recordings by start, end and watch; people and venues by name; watches by id, alias or node;
 * events by name, parent and creation time; collections by name and creation time; presets by
 * name. For a match, [RestoreChoice.SKIP] keeps what's here and links the file's references to
 * it, [RestoreChoice.REPLACE] overwrites it with the file's version (a recording's readings are
 * never touched: a match means they're the same), and [RestoreChoice.ADD] adds a second one where
 * that is offered. For a new item, [RestoreChoice.SKIP] leaves it out, and what pointed at it
 * points at the nearest thing that came in instead: a skipped event's recordings go to its parent.
 */
suspend fun restoreBackup(
    backup: LibraryBackup,
    repository: LibraryRepository,
    context: android.content.Context,
    /** What to do with each item. The default adds what's new and keeps what's here. */
    choices: RestoreChoices = RestoreChoices(),
    /** Recordings processed so far, out of how many the file holds. Called as each is done. */
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
): RestoreResult {
    val tag = "BackupRestore"

    if (backup.formatVersion > LibraryBackup.FORMAT_VERSION) {
        return RestoreResult(
            failure = "This backup was written by a newer version of the app " +
                "(format ${backup.formatVersion}, this build reads ${LibraryBackup.FORMAT_VERSION})."
        )
    }

    return try {
      repository.inBulk {
        var replaced = 0

        // --- Tags: a new tag that was skipped is never attached to anything ---

        val tagsHere = RestorePlanner.tagsHere(repository)
        fun tagAllowed(name: String): Boolean {
            val key = RestoreKeys.tag(RestoreKeys.qualify(name))
            return key in tagsHere || choices.of(RestoreKind.TAG, key, matched = false) == RestoreChoice.ADD
        }

        // --- People ---

        var peopleCreated = 0
        val existingPeople = repository.getAllPeople().first().associateBy { RestoreKeys.person(it.name) }
        // Everyone this library now has, by name: what a watch's wearer and a rule's people are
        // matched against. Seeded with who was already here, so a rule naming someone the file
        // didn't carry still finds them.
        val personIdsByName = existingPeople.mapValues { it.value.personId }.toMutableMap()
        backup.people.distinctBy { RestoreKeys.person(it.name) }.forEach { person ->
            val key = RestoreKeys.person(person.name)
            val here = existingPeople[key]
            val choice = choices.of(RestoreKind.PERSON, key, matched = here != null)
            val personId = when {
                choice == RestoreChoice.SKIP -> {
                    // Kept as they are, or left out. Their recordings still carry their name.
                    here?.let { personIdsByName[key] = it.personId }
                    return@forEach
                }
                here != null -> here.personId.also {
                    repository.setPersonColor(it, person.colorArgb)
                    replaced++
                }
                else -> repository.addPerson(person.name, person.colorArgb).also { peopleCreated++ }
            }
            personIdsByName[key] = personId

            // Carried although nothing reads them yet (Audit #7): the figures survive for when
            // personal zones come back.
            if (person.restingBpm != null || person.maxBpm != null) {
                repository.setPersonZones(personId, person.restingBpm, person.maxBpm)
            }
            if (person.createdAt > 0L) repository.setPersonCreatedAt(personId, person.createdAt)
            val photo = person.photoBase64?.fromBase64()
            if (photo != null || person.photoCrop != null) {
                repository.restorePersonPhoto(
                    context = context,
                    personId = personId,
                    bytes = photo,
                    crop = person.photoCrop.toCover(),
                    nameHint = person.name
                )
            }
        }

        // --- Venues, before events, so an event can name where it was ---

        val existingLocations = repository.getAllLocations().first().associateBy { RestoreKeys.venue(it.name) }
        val locationIdsByName = mutableMapOf<String, Long>()
        backup.locations.distinctBy { RestoreKeys.venue(it.name) }.forEach { place ->
            val key = RestoreKeys.venue(place.name)
            val here = existingLocations[key]
            val choice = choices.of(RestoreKind.VENUE, key, matched = here != null)
            val id = when {
                choice == RestoreChoice.SKIP -> here?.locationId ?: return@forEach
                here != null -> here.locationId.also {
                    repository.setLocationZone(it, place.timeZoneId)
                    repository.setLocationCoordinates(it, place.latitude, place.longitude)
                    replaced++
                }
                else -> repository.createLocation(
                    name = place.name,
                    timeZoneId = place.timeZoneId,
                    latitude = place.latitude,
                    longitude = place.longitude
                )
            }
            locationIdsByName[place.name] = id
            if (choice != RestoreChoice.SKIP) {
                place.photoBase64?.fromBase64()?.let { bytes ->
                    repository.restoreLocationPhoto(context, id, bytes, place.name)
                }
            }
        }

        // --- Watches ---

        var watchesCreated = 0
        val existingWatchIds = repository.getAllWatches().first().map { it.watchId }.toSet()
        // A watch in the file that this library knows under another id: file id to the id here.
        // Rules naming the file's id are translated through it.
        val watchIdsHere = mutableMapOf<String, String>()
        backup.watches.forEach { watch ->
            // The same watch under another id: one it was merged into here, one of the file's own
            // aliases, or the one last heard from its node. Recognised rather than added again
            // (Audit #39), and every id the file knew it by is remembered, so its recordings and
            // its next sync land on the entry here. Kept or replaced, never duplicated.
            val known = if (watch.watchId in existingWatchIds) null else
                (listOf(watch.watchId) + watch.aliases).firstNotNullOfOrNull {
                    repository.knownWatchFor(it, null)
                } ?: repository.knownWatchFor(watch.watchId, watch.lastKnownNodeId)
            val hereId = if (watch.watchId in existingWatchIds) watch.watchId else known?.watchId
            val choice = choices.of(RestoreKind.WATCH, RestoreKeys.watch(watch.watchId), matched = hereId != null)

            if (hereId != null) {
                (listOf(watch.watchId) + watch.aliases).forEach { repository.addWatchAlias(it, hereId) }
                if (hereId != watch.watchId) watchIdsHere[watch.watchId] = hereId
                if (choice == RestoreChoice.REPLACE) {
                    if (watch.deviceName.isNotBlank()) repository.renameWatch(hereId, watch.deviceName)
                    watch.wearerName?.let { personIdsByName[RestoreKeys.person(it)] }
                        ?.let { repository.setWatchPerson(hereId, it) }
                    replaced++
                }
                return@forEach
            }
            // A skipped watch still appears if its recordings come in: an entry is made for the
            // watch a recording came from. Skipping keeps its name and wearer out.
            if (choice == RestoreChoice.SKIP) return@forEach

            repository.restoreWatch(
                watchId = watch.watchId,
                deviceName = watch.deviceName,
                model = watch.lastKnownModel,
                personId = watch.wearerName?.let { personIdsByName[RestoreKeys.person(it)] },
                colorArgb = watch.colorArgb,
                firstSeen = watch.firstSeen,
                lastSeen = watch.lastSeen,
                nodeId = watch.lastKnownNodeId,
                aliases = watch.aliases
            )
            watchesCreated++
        }

        // --- Legacy groups (files before format 5), always new ---

        var groupsCreated = 0
        val groupIdsByName = mutableMapOf<String, Long>()
        backup.eventGroups.forEach { group ->
            if (groupIdsByName.containsKey(group.name)) return@forEach
            if (choices.of(RestoreKind.EVENT, RestoreKeys.group(group.name), matched = false) == RestoreChoice.SKIP) {
                return@forEach
            }
            groupIdsByName[group.name] = repository.createEventGroup(group.name)
            groupsCreated++
        }
        backup.eventGroups.forEach { group ->
            val groupId = groupIdsByName[group.name] ?: return@forEach
            if (group.notes.isNotBlank()) repository.setEventGroupNotes(groupId, group.notes)
            group.parentName?.let { parent ->
                groupIdsByName[parent]?.let { repository.setEventGroupParent(groupId, it) }
            }
            // An event, because that is what a legacy group becomes (see createEventGroup). Writing
            // it as a Collection put the picture in the collections table under an event's id, so
            // it landed on whichever collection happened to share the number, or on nothing.
            restoreCover(repository, context, LibraryRepository.CoverOwner.Event(groupId),
                group.cover, group.name)
            group.tags.filter(::tagAllowed)
                .forEach { applyTag(repository, it) { id -> repository.addTagToGroup(groupId, id) } }
        }

        // --- Events ---

        var eventsCreated = 0
        val eventsHere = EventMatcher.match(
            backup.events,
            repository.allEventsInTree.first().map { EventMatcher.Here(it.eventId, it.name, it.parentId, it.createdAt) }
        )

        // Keyed on the id the event had in the library this file came from, not on its name.
        //
        // The old map was `name -> id`, and it merged. Two events called "Day 2" — one inside Bass
        // Canyon, one inside Griztronics — became a single event, and every recording from both
        // evenings landed in it. A name is unique among siblings, and nesting exists precisely so
        // that it does not have to be unique anywhere else.
        val eventIdsBySourceId = mutableMapOf<Long, Long>()

        // Kept alongside, and used *only* for files written before format 6, where an id is all
        // zeroes and the name genuinely is the only handle there is.
        val eventIdsByName = mutableMapOf<String, Long>()

        /** Events whose details the file's version is written onto: the ones added or replaced. */
        val writeDetails = mutableSetOf<Long>()
        val byKey = backup.events.associateBy { RestoreKeys.event(it) }

        backup.events.distinctBy { RestoreKeys.event(it) }.forEach { event ->
            val here = eventsHere[event.eventId]
            val choice = choices.of(RestoreKind.EVENT, RestoreKeys.event(event), matched = here != null)
            val landed = when {
                choice == RestoreChoice.SKIP -> here ?: return@forEach
                choice == RestoreChoice.REPLACE && here != null -> here.also {
                    if (event.name.isNotBlank()) repository.renameEvent(it, event.name)
                    replaced++
                }
                else -> repository.createEvent(
                    name = event.name,
                    groupId = event.groupName?.let { groupIdsByName[it] },
                    // Kept, so restoring this library's own backup can recognise the event again.
                    createdAt = event.createdAt.takeIf { it > 0L }
                ).also { eventsCreated++ }
            }
            if (choice != RestoreChoice.SKIP) writeDetails += landed
            if (event.eventId != 0L) eventIdsBySourceId[event.eventId] = landed
            // First writer wins for the name map, matching the old behaviour for old files.
            eventIdsByName.putIfAbsent(event.name, landed)
        }

        /**
         * Where an event from the file landed, by id if the file has one and by name if not. A
         * skipped event hands what it held to its nearest ancestor that came in, or to nothing.
         */
        fun landedEvent(sourceId: Long?, name: String?): Long? {
            var id = sourceId?.takeIf { it != 0L }
            var steps = 0
            while (id != null && steps++ < 64) {
                eventIdsBySourceId[id]?.let { return it }
                id = byKey["id:$id"]?.parentEventId?.takeIf { it != 0L }
            }
            return name?.let { eventIdsByName[it] }.takeIf { sourceId == null || sourceId == 0L }
        }

        backup.events.distinctBy { RestoreKeys.event(it) }.forEach { event ->
            val eventId = (if (event.eventId != 0L) eventIdsBySourceId[event.eventId] else eventIdsByName[event.name])
                ?.takeIf { it in writeDetails } ?: return@forEach
            if (event.notes.isNotBlank()) repository.setEventNotes(eventId, event.notes)
            repository.restoreEventTaxonomy(
                eventId = eventId,
                // An event naming itself as its parent is dropped rather than written. By id where
                // the file has one: `parentName` is as ambiguous as any other name.
                parentId = landedEvent(event.parentEventId, event.parentName)
                    ?.takeIf { it != eventId },
                windowStart = event.windowStart,
                windowEnd = event.windowEnd,
                type = event.type,
                excludedFromParentAnalysis = event.excludedFromParentAnalysis
            )
            event.locationName?.let { locationIdsByName[it] }
                ?.let { repository.setEventLocation(eventId, it) }
            restoreCover(repository, context, LibraryRepository.CoverOwner.Event(eventId),
                event.cover, event.name)
            // Replacing makes the event's own tags the file's; adding just gives it the file's.
            val wanted = event.tags.filter(::tagAllowed).mapNotNull { qualified ->
                val q = RestoreKeys.qualify(qualified)
                repository.findOrCreateTag(q.substringBefore(':'), q.substringAfter(':'))
            }.toSet()
            repository.getTagsForEvent(eventId).first().map { it.tagId }
                .filterNot { it in wanted }
                .forEach { repository.removeTagFromEvent(eventId, it) }
            wanted.forEach { repository.addTagToEvent(eventId, it) }
        }

        // --- Recordings ---

        // Import records and remember where each one landed. Ids are reassigned on insert, so a
        // saved analysis pointing at record 42 has to be told which recording that is now.
        val idMap = mutableMapOf<Long, Long>()
        var imported = 0
        var alreadyHere = 0
        var leftOut = 0
        var unreadable = 0

        // What the library already holds, added to as recordings land, so the same recording twice
        // in one file is added once — which is also what RestorePlanner promises in the preview.
        val existing = repository.recordIdsByIdentity().toMutableMap()

        val total = backup.records.size
        backup.records.forEachIndexed { index, dto ->
            onProgress(index + 1, total)
            val parsed = listOf(dto).toWatchRecords().firstOrNull()
            if (parsed == null) {
                unreadable++
                return@forEachIndexed
            }
            // Tags that were skipped don't come in on the recording either.
            val watchRecord = parsed.copy(tagNames = parsed.tagNames.filter(::tagAllowed))
            val identity = Triple(watchRecord.startTime, watchRecord.endTime, watchRecord.deviceId)
            val key = RestoreKeys.recording(identity.first, identity.second, identity.third)
            val here = existing[identity]
            val choice = choices.of(RestoreKind.RECORDING, key, matched = here != null)

            if (here != null) {
                // Linked rather than added, so a collection in the file that names this recording
                // still finds it.
                if (dto.recordId != 0L) idMap[dto.recordId] = here
                if (choice == RestoreChoice.REPLACE) {
                    replaceRecording(repository, context, here, dto, watchRecord.tagNames, personIdsByName,
                        landedEvent(dto.eventId, dto.eventName), dto.locationName?.let { locationIdsByName[it] })
                    replaced++
                } else {
                    alreadyHere++
                }
                return@forEachIndexed
            }
            if (choice == RestoreChoice.SKIP) {
                leftOut++
                return@forEachIndexed
            }

            // The ordinary ingest path, so a restored recording is indistinguishable from one that
            // arrived from a watch — same analysis, same person resolution, same auto-naming.
            val newId = repository.saveWatchRecordToLibrary(watchRecord)
            existing[identity] = newId
            if (dto.recordId != 0L) idMap[dto.recordId] = newId

            // Filed after saving rather than through the ingest path, which knows nothing about
            // events.
            landedEvent(dto.eventId, dto.eventName)?.let { eventId ->
                repository.assignRecordsToEvent(listOf(newId), eventId)
            }
            dto.locationName?.let { locationIdsByName[it] }
                ?.let { repository.setRecordLocation(newId, it) }
            restoreCover(repository, context, LibraryRepository.CoverOwner.Recording(newId),
                dto.cover, dto.title)
            imported++
        }

        // --- Collections ---

        var analysesRestored = 0
        val collectionsHere = repository.getAllCollections().first()
            .associateBy { it.name.trim().lowercase() to it.createdAt }
        val collectionIdsBySourceId = mutableMapOf<Long, Long>()
        val restoredSets = backup.collections.mapIndexedNotNull { index, set ->
            val here = collectionsHere[set.name.trim().lowercase() to set.createdAt]
                ?.takeIf { RestorePlanner.collectionIsHere(set, collectionsHere.keys) }
            val choice = choices.of(RestoreKind.COLLECTION, RestoreKeys.collection(index), matched = here != null)
            if (choice == RestoreChoice.SKIP) {
                // Kept as it is: a rule elsewhere in the file naming it still finds it.
                here?.let { if (set.sourceId != 0L) collectionIdsBySourceId[set.sourceId] = it.collectionId }
                return@mapIndexedNotNull null
            }
            val keep = here?.collectionId?.takeIf { choice == RestoreChoice.REPLACE }
            val newId = repository.restoreCollection(set, eventIdsByName, idMap, eventIdsBySourceId, replacing = keep)
            if (keep != null) replaced++ else analysesRestored++
            if (set.sourceId != 0L) collectionIdsBySourceId[set.sourceId] = newId
            restoreCover(
                repository, context,
                LibraryRepository.CoverOwner.Collection(newId), set.cover, set.name
            )
            set to newId
        }

        // Rules second, once every collection exists, because a rule can name a collection that
        // comes later in the file. See [RuleRemap].
        val venueIdsByName = existingLocations.mapValues { it.value.locationId } +
            locationIdsByName.mapKeys { RestoreKeys.venue(it.key) }
        val rulesNeedingAttention = mutableListOf<String>()
        restoredSets.forEach { (set, newId) ->
            val rule = set.filterJson?.let { inga.bpmetrics.library.FilterCodec.parseOrNull(it) }
                ?: return@forEach
            val names = set.ruleNames ?: RuleNames()
            val maps = RuleRemap.Maps(
                events = eventIdsBySourceId,
                people = names.people.mapNotNull { ref ->
                    personIdsByName[RestoreKeys.person(ref.name)]?.let { ref.id to it }
                }.toMap(),
                tags = names.tags.filter { tagAllowed(it.name) }.mapNotNull { ref ->
                    val q = RestoreKeys.qualify(ref.name)
                    repository.findOrCreateTag(q.substringBefore(':'), q.substringAfter(':'))?.let { ref.id to it }
                }.toMap(),
                locations = names.locations.mapNotNull { ref ->
                    venueIdsByName[RestoreKeys.venue(ref.name)]?.let { ref.id to it }
                }.toMap(),
                collections = collectionIdsBySourceId,
                watches = watchIdsHere
            )
            val remapped = RuleRemap.remap(rule, maps)
            val written = repository.setCollectionRule(
                newId,
                inga.bpmetrics.library.FilterCodec.toJson(remapped.rule)
            )
            if (!written) {
                // Refused because it would contain itself. Never leave the verbatim rule in place:
                // its ids name unrelated things here. Matching nothing is visible and fixable.
                repository.setCollectionRule(
                    newId,
                    inga.bpmetrics.library.FilterCodec.toJson(
                        inga.bpmetrics.library.FilterState(selectedEventIds = setOf(RuleRemap.NOTHING))
                    )
                )
            }
            if (remapped.lost.isNotEmpty() || !written) {
                Log.w(tag, "Rule of '${set.name}' lost: ${remapped.lost}, written=$written")
                rulesNeedingAttention += set.name
            }
        }

        // Pre-format-5 frozen analyses, restored as frozen collections. Nothing writes this any
        // more; it is here so an older file does not lose numbers that cannot be recomputed.
        backup.savedAnalyses.forEachIndexed { index, analysis ->
            if (choices.of(RestoreKind.COLLECTION, RestoreKeys.analysis(index), matched = false) == RestoreChoice.SKIP) {
                return@forEachIndexed
            }
            val remapped = analysis.records.map { row ->
                // An unmapped id means that recording was not brought in. The row is kept — it is a
                // frozen snapshot and still readable — but pointed at nothing rather than at an
                // unrelated recording.
                row.copy(recordId = idMap[row.recordId] ?: -1L)
            }
            repository.restoreSavedAnalysis(analysis.copy(records = remapped))
            analysesRestored++
        }

        // --- Settings: only the ones chosen, and only ones a restore may change ---

        val settingsRestored = backup.settings
            .filter { choices.of(RestoreKind.SETTING, it.key, matched = true) == RestoreChoice.REPLACE }
            .takeIf { it.isNotEmpty() }
            ?.let { repository.restoreSettings(it) } ?: 0

        // --- Presets, last: they depend on nothing ---

        var presetsRestored = 0
        var presetsSkipped = 0
        val presetNames = repository.getPresetsForBackup().map { RestoreKeys.preset(it.name) }.toMutableSet()
        backup.presets.distinctBy { RestoreKeys.preset(it.name) }.forEach { preset ->
            val matched = RestoreKeys.preset(preset.name) in presetNames
            val name = when (choices.of(RestoreKind.PRESET, RestoreKeys.preset(preset.name), matched)) {
                RestoreChoice.SKIP -> return@forEach
                RestoreChoice.REPLACE -> preset.name
                // A copy alongside: the first free "Name (2)", "Name (3)"…
                RestoreChoice.ADD -> if (!matched) preset.name else
                    generateSequence(2) { it + 1 }.map { "${preset.name} ($it)" }
                        .first { RestoreKeys.preset(it) !in presetNames }
            }
            val written = repository.restorePreset(
                name = name,
                configJson = preset.configJson,
                isDefault = preset.isDefault,
                isBuiltIn = preset.isBuiltIn,
                createdAt = preset.createdAt
            )
            presetNames += RestoreKeys.preset(name)
            when {
                !written -> presetsSkipped++
                matched && name == preset.name -> replaced++
                else -> presetsRestored++
            }
        }

        val result = RestoreResult(
            peopleCreated = peopleCreated,
            watchesCreated = watchesCreated,
            recordsImported = imported,
            recordsAlreadyHere = alreadyHere,
            recordsSkipped = unreadable,
            recordsLeftOut = leftOut,
            itemsReplaced = replaced,
            eventsCreated = eventsCreated,
            groupsCreated = groupsCreated,
            analysesRestored = analysesRestored,
            settingsRestored = settingsRestored,
            presetsRestored = presetsRestored,
            presetsSkipped = presetsSkipped,
            rulesNeedingAttention = rulesNeedingAttention
        )
        Log.i(tag, "Restored $result")
        result
      }
    } catch (e: Exception) {
        Log.e(tag, "Restore failed", e)
        RestoreResult(failure = e.message ?: e.toString())
    }
}

/**
 * Writes the file's version of a recording onto the one here: its title, notes, wearer, tags,
 * event, venue and cover. Not its readings, which a match means are the same.
 */
private suspend fun replaceRecording(
    repository: LibraryRepository,
    context: android.content.Context,
    recordId: Long,
    dto: BpmRecordJsonDto,
    tagNames: List<String>,
    personIdsByName: Map<String, Long>,
    eventId: Long?,
    locationId: Long?
) {
    if (dto.title.isNotBlank()) repository.updateRecordTitle(recordId, dto.title)
    repository.updateRecordDescription(recordId, dto.description)
    dto.wearerName?.let { personIdsByName[RestoreKeys.person(it)] }
        ?.let { repository.assignPersonToRecords(listOf(recordId), it) }
    val wanted = tagNames.mapNotNull { qualified ->
        val q = RestoreKeys.qualify(qualified)
        repository.findOrCreateTag(q.substringBefore(':'), q.substringAfter(':'))
    }.toSet()
    repository.getTagsForRecord(recordId).first().map { it.tagId }
        .filterNot { it in wanted }
        .forEach { repository.removeTagFromRecord(recordId, it) }
    wanted.forEach { repository.addTagToRecord(recordId, it) }
    repository.assignRecordsToEvent(listOf(recordId), eventId)
    repository.setRecordLocation(recordId, locationId)
    restoreCover(repository, context, LibraryRepository.CoverOwner.Recording(recordId), dto.cover, dto.title)
}

/**
 * Writes a backup's cover onto whatever it belonged to, if it had one.
 *
 * Silent when there is no cover, which is the common case, so every call site can be one line
 * rather than an `if` around three.
 */
private suspend fun restoreCover(
    repository: LibraryRepository,
    context: android.content.Context,
    owner: LibraryRepository.CoverOwner,
    dto: CoverDto?,
    nameHint: String
) {
    if (dto == null) return
    repository.restoreCover(
        context = context,
        owner = owner,
        bytes = dto.imageBase64?.fromBase64(),
        crop = dto.toCover(),
        nameHint = nameHint
    )
}

/**
 * Finds or creates the tag a backup named, then hands its id to [attach].
 *
 * Tags travel as "Category:Tag" because ids are reassigned on import. A name with no colon is
 * treated as uncategorised rather than dropped — a value is more use on the wrong axis than absent,
 * and moving it afterwards is one gesture.
 */
private suspend fun applyTag(
    repository: LibraryRepository,
    qualified: String,
    attach: suspend (Long) -> Unit
) {
    val categoryName = qualified.substringBefore(':', "Uncategorized").ifBlank { "Uncategorized" }
    val tagName = qualified.substringAfter(':', qualified).trim()
    repository.findOrCreateTag(categoryName, tagName)?.let { attach(it) }
}

/** The framing a backup recorded, defaulting to the whole picture when it carried none. */
internal fun CoverDto?.toCover(): inga.bpmetrics.library.Cover =
    inga.bpmetrics.library.Cover(
        path = "",
        cropLeft = this?.cropLeft ?: 0f,
        cropTop = this?.cropTop ?: 0f,
        cropRight = this?.cropRight ?: 1f,
        cropBottom = this?.cropBottom ?: 1f,
        blur = this?.blur ?: 0f,
        dim = this?.dim ?: 0f
    )
