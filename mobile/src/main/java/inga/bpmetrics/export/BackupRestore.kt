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
            if (recordsAlreadyHere > 0) add("$recordsAlreadyHere already in your library")
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
 * Rebuilds a library from a backup file.
 *
 * Order matters. People and watches are created first, because a record's link to them is resolved
 * during ingest — `saveWatchRecordToLibrary` looks a person up by name and a watch up by id, and
 * finding neither would import the recordings as unattributed and quietly lose who made them.
 *
 * Existing people and watches are matched, not duplicated, so restoring into a library that already
 * has some of them merges rather than doubling.
 */
suspend fun restoreBackup(
    backup: LibraryBackup,
    repository: LibraryRepository,
    context: android.content.Context
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
        var peopleCreated = 0
        val existingPeople = repository.getAllPeople().first().associateBy { it.name.lowercase() }
        // Everyone this library now has, by name: what a watch's wearer and a rule's people are
        // matched against. Seeded with who was already here, so a rule naming someone the file
        // didn't carry still finds them.
        val personIdsByName = existingPeople.mapValues { it.value.personId }.toMutableMap()
        backup.people.forEach { person ->
            val personId = existingPeople[person.name.lowercase()]?.personId
                ?: repository.addPerson(person.name, person.colorArgb).also { peopleCreated++ }
            personIdsByName[person.name.lowercase()] = personId

            // Their own resting and maximum, which decide where every zone boundary sits on their
            // charts. Absent from the format until 4, so an older backup leaves them inherited.
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

        // Venues before events, so an event can name where it was. Matched by name rather than
        // duplicated, so restoring into a library that already knows the Gorge reuses it.
        val locationIdsByName = mutableMapOf<String, Long>()
        val existingLocations = repository.getAllLocations().first()
            .associateBy { it.name.lowercase() }
        backup.locations.forEach { place ->
            val id = existingLocations[place.name.lowercase()]?.locationId
                ?: repository.createLocation(
                    name = place.name,
                    timeZoneId = place.timeZoneId,
                    latitude = place.latitude,
                    longitude = place.longitude
                )
            locationIdsByName[place.name] = id
            place.photoBase64?.fromBase64()?.let { bytes ->
                repository.restoreLocationPhoto(context, id, bytes, place.name)
            }
        }

        var watchesCreated = 0
        val existingWatchIds = repository.getAllWatches().first().map { it.watchId }.toSet()
        backup.watches.forEach { watch ->
            if (watch.watchId !in existingWatchIds) {
                repository.restoreWatch(
                    watchId = watch.watchId,
                    deviceName = watch.deviceName,
                    model = watch.lastKnownModel,
                    personId = watch.wearerName?.let { personIdsByName[it.lowercase()] },
                    colorArgb = watch.colorArgb,
                    firstSeen = watch.firstSeen,
                    lastSeen = watch.lastSeen
                )
                watchesCreated++
            }
        }

        // Groups before events before records, so each link exists before something needs it.
        //
        // Two passes over each, because a parent is named rather than numbered and may appear after
        // its child in the file. Creating everything first means the second pass can always resolve
        // the name — a single pass silently dropped any nesting that ran backwards through the list.
        var groupsCreated = 0
        val groupIdsByName = mutableMapOf<String, Long>()
        backup.eventGroups.forEach { group ->
            if (groupIdsByName.containsKey(group.name)) return@forEach
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
            group.tags.forEach { applyTag(repository, it) { id -> repository.addTagToGroup(groupId, id) } }
        }

        var eventsCreated = 0

        // Keyed on the id the event had in the library this file came from, not on its name.
        //
        // The old map was `name -> id`, and it merged. Two events called "Day 2" — one inside Bass
        // Canyon, one inside Griztronics — became a single event, and every recording from both
        // evenings landed in it. The comment defended this as "a fair reading of naming them the
        // same", which holds only for a flat list: a name is unique among siblings, and nesting
        // exists precisely so that it does not have to be unique anywhere else.
        val eventIdsBySourceId = mutableMapOf<Long, Long>()

        // Kept alongside, and used *only* for files written before format 6, where an id is all
        // zeroes and the name genuinely is the only handle there is.
        val eventIdsByName = mutableMapOf<String, Long>()
        val byId = backup.events.any { it.eventId != 0L }

        backup.events.forEach { event ->
            // Within one file an id appears once, so this guards against a malformed file rather
            // than against a name collision — which is no longer a collision.
            if (byId && eventIdsBySourceId.containsKey(event.eventId)) return@forEach
            if (!byId && eventIdsByName.containsKey(event.name)) return@forEach

            val newId = repository.createEvent(
                name = event.name,
                groupId = event.groupName?.let { groupIdsByName[it] }
            )
            if (event.eventId != 0L) eventIdsBySourceId[event.eventId] = newId
            // First writer wins for the name map, matching the old behaviour for old files.
            eventIdsByName.putIfAbsent(event.name, newId)
            eventsCreated++
        }

        /** Where an event from the file landed, by id if the file has one and by name if not. */
        fun landedEvent(sourceId: Long?, name: String?): Long? =
            sourceId?.takeIf { it != 0L }?.let { eventIdsBySourceId[it] }
                ?: name?.let { eventIdsByName[it] }

        backup.events.forEach { event ->
            val eventId = landedEvent(event.eventId, event.name) ?: return@forEach
            if (event.notes.isNotBlank()) repository.setEventNotes(eventId, event.notes)
            repository.restoreEventTaxonomy(
                eventId = eventId,
                // An event naming itself as its parent is dropped rather than written. Everything
                // downstream survives a cycle, but there is no reading of the file under which this
                // was intended, so it is not worth carrying forward.
                // By id where the file has one. `parentName` is as ambiguous as any other name:
                // a day called "Day 2" inside a festival whose *parent* is also called "Day 2"
                // is nonsense, but two festivals each holding a "Day 2" is not, and the by-name
                // lookup could not tell those apart either.
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
            event.tags.forEach { applyTag(repository, it) { id -> repository.addTagToEvent(eventId, id) } }
        }

        // Import records and remember where each one landed. Ids are reassigned on insert, so a
        // saved analysis pointing at record 42 has to be told which recording that is now — without
        // this it would point at whatever happens to be 42 in the new library, which is worse than
        // pointing at nothing.
        val idMap = mutableMapOf<Long, Long>()
        var imported = 0
        var alreadyHere = 0

        // What the library already holds. Restoring a file twice — or retrying one that failed
        // partway, which leaves everything before the failure in place — used to add every
        // recording again, so a second attempt at a stubborn import doubled the library.
        val existing = repository.recordIdsByIdentity()

        backup.records.forEach { dto ->
            val watchRecord = listOf(dto).toWatchRecords().firstOrNull() ?: return@forEach

            existing[Triple(watchRecord.startTime, watchRecord.endTime, watchRecord.deviceId)]
                ?.let { have ->
                    // Linked rather than added, so a collection in the file that names this
                    // recording still finds it.
                    if (dto.recordId != 0L) idMap[dto.recordId] = have
                    alreadyHere++
                    return@forEach
                }

            // The ordinary ingest path, so a restored recording is indistinguishable from one that
            // arrived from a watch — same analysis, same person resolution, same auto-naming.
            val newId = repository.saveWatchRecordToLibrary(watchRecord)
            if (dto.recordId != 0L) idMap[dto.recordId] = newId

            // Filed after saving rather than through the ingest path, which knows nothing about
            // events — it exists to accept what a watch sends, and a watch has no idea what set it
            // was at.
            landedEvent(dto.eventId, dto.eventName)?.let { eventId ->
                repository.assignRecordsToEvent(listOf(newId), eventId)
            }
            dto.locationName?.let { locationIdsByName[it] }
                ?.let { repository.setRecordLocation(newId, it) }
            restoreCover(repository, context, LibraryRepository.CoverOwner.Recording(newId),
                dto.cover, dto.title)
            imported++
        }

        var analysesRestored = 0

        // Selections: collections, smart collections and frozen analyses alike. Members are
        // re-linked through the maps built above, because ids are reassigned on import.
        val collectionIdsBySourceId = mutableMapOf<Long, Long>()
        val restoredSets = backup.collections.map { set ->
            val newId = repository.restoreCollection(set, eventIdsByName, idMap, eventIdsBySourceId)
            if (set.sourceId != 0L) collectionIdsBySourceId[set.sourceId] = newId
            restoreCover(
                repository, context,
                LibraryRepository.CoverOwner.Collection(newId), set.cover, set.name
            )
            analysesRestored++
            set to newId
        }

        // Rules second, once every collection exists, because a rule can name a collection that
        // comes later in the file. Until now a rule was restored verbatim, its ids pointing at
        // whatever held those numbers here. See [RuleRemap].
        val venueIdsByName = existingLocations.mapValues { it.value.locationId } +
            locationIdsByName.mapKeys { it.key.lowercase() }
        val rulesNeedingAttention = mutableListOf<String>()
        restoredSets.forEach { (set, newId) ->
            val rule = set.filterJson?.let { inga.bpmetrics.library.FilterCodec.parseOrNull(it) }
                ?: return@forEach
            val names = set.ruleNames ?: RuleNames()
            val maps = RuleRemap.Maps(
                events = eventIdsBySourceId,
                people = names.people.mapNotNull { ref ->
                    personIdsByName[ref.name.lowercase()]?.let { ref.id to it }
                }.toMap(),
                tags = names.tags.mapNotNull { ref ->
                    val category = ref.name.substringBefore(':', "Uncategorized")
                        .ifBlank { "Uncategorized" }
                    val tagName = ref.name.substringAfter(':', ref.name).trim()
                    repository.findOrCreateTag(category, tagName)?.let { ref.id to it }
                }.toMap(),
                locations = names.locations.mapNotNull { ref ->
                    venueIdsByName[ref.name.lowercase()]?.let { ref.id to it }
                }.toMap(),
                collections = collectionIdsBySourceId
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
                        inga.bpmetrics.library.FilterState(
                            selectedEventIds = setOf(RuleRemap.NOTHING)
                        )
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
        backup.savedAnalyses.forEach { analysis ->
            val remapped = analysis.records.map { row ->
                // An unmapped id means that recording was not in this backup. The row is kept — it
                // is a frozen snapshot and still readable — but pointed at nothing rather than at
                // an unrelated recording.
                row.copy(recordId = idMap[row.recordId] ?: -1L)
            }
            repository.restoreSavedAnalysis(analysis.copy(records = remapped))
            analysesRestored++
        }

        val settingsRestored = if (backup.settings.isNotEmpty()) {
            repository.restoreSettings(backup.settings)
        } else 0

        // Presets last. They depend on nothing, and putting them at the end means a file that
        // fails partway through has still restored the library itself.
        var presetsRestored = 0
        var presetsSkipped = 0
        backup.presets.forEach { preset ->
            val written = repository.restorePreset(
                name = preset.name,
                configJson = preset.configJson,
                isDefault = preset.isDefault,
                isBuiltIn = preset.isBuiltIn,
                createdAt = preset.createdAt
            )
            if (written) presetsRestored++ else presetsSkipped++
        }

        val result = RestoreResult(
            peopleCreated = peopleCreated,
            watchesCreated = watchesCreated,
            recordsImported = imported,
            recordsAlreadyHere = alreadyHere,
            recordsSkipped = backup.records.size - imported - alreadyHere,
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
