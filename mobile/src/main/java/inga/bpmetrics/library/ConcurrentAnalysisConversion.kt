package inga.bpmetrics.library

import android.util.Log

/**
 * What the conversion did, so it can be logged rather than guessed at.
 */
data class ConversionResult(
    val eventsCreated: Int = 0,
    val recordsFiled: Int = 0,
    val analysesRemoved: Int = 0,
    val failure: String? = null
)

/**
 * Turns saved same-time analyses into events.
 *
 * A saved concurrent analysis was already an event in everything but name: a name, a set of
 * recordings that happened together, and a stretch of time. Events do the same thing properly —
 * they merge a person's split recordings into one lane, they can be grouped, and they are not
 * frozen. Leaving both would mean two half-features that do not know about each other.
 *
 * Nothing is lost by removing the saved analysis afterwards. A concurrent analysis never stored its
 * curves; it stored *which* recordings and re-read them from the library on open — the save dialog
 * says so. The event holds exactly the same reference. Group analyses are snapshots and are left
 * alone.
 *
 * Written in Kotlin against the repository rather than as SQL inside a Room migration. This moves
 * user data between tables on a correlation the schema does not express, and getting it subtly
 * wrong would mis-file recordings under someone else's event with no error and no way back. A
 * failure here is logged and retried next launch; a failure in a migration is an app that will not
 * open.
 *
 * @return what happened, or a [ConversionResult.failure] if it did not.
 */
suspend fun convertConcurrentAnalysesToEvents(repository: LibraryRepository): ConversionResult {
    val tag = "ConcurrentConversion"

    return try {
        val concurrent = repository.getConcurrentAnalyses()
        if (concurrent.isEmpty()) return ConversionResult()

        var eventsCreated = 0
        var recordsFiled = 0
        var analysesRemoved = 0

        // Oldest first, so if two analyses share a recording the earlier one is the one that ends
        // up unclaimed and the later — more likely the one the user refined — keeps it.
        concurrent.sortedBy { it.metadata.createdAt }.forEach { analysis ->
            val eventId = repository.createEvent(analysis.metadata.name)
            eventsCreated++

            // Only recordings that are not already in an event. Someone who filed recordings by
            // hand before this ran has made a decision, and a background conversion must not
            // quietly overrule it.
            val unfiled = repository.recordIdsWithoutEvent(analysis.records.map { it.recordId })
            if (unfiled.isNotEmpty()) {
                recordsFiled += repository.assignRecordsToEvent(unfiled, eventId)
            }

            repository.deleteSavedAnalysis(analysis.metadata.analysisId)
            analysesRemoved++
        }

        val result = ConversionResult(eventsCreated, recordsFiled, analysesRemoved)
        Log.i(tag, "Converted same-time analyses into events: $result")
        result
    } catch (e: Exception) {
        Log.e(tag, "Could not convert same-time analyses into events", e)
        ConversionResult(failure = e.message ?: e.toString())
    }
}
