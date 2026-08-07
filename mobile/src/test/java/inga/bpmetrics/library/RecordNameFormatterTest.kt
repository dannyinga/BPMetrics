package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Covers what a recording is called.
 *
 * The line worth guarding is between a placeholder the app wrote and a title the user typed.
 * Getting it wrong in one direction leaves a library of "Untitled 4"; in the other it silently
 * discards someone's own name for their recording, which is worse.
 */
class RecordNameFormatterTest {

    private val startTime = 1_772_000_000_000L

    private val expectedTime =
        SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(startTime))

    private fun record(
        title: String,
        wearerName: String = "",
        deviceId: String = ""
    ) = BpmRecordEntity(
        recordId = 1,
        title = title,
        date = startTime,
        startTime = startTime,
        endTime = startTime + 1000,
        durationMs = 1000,
        wearerName = wearerName,
        deviceId = deviceId
    )

    @Test
    fun `a placeholder becomes wearer and time`() {
        assertEquals(
            "Kyle · $expectedTime",
            RecordNameFormatter.displayName(record("Untitled 4"), wearerName = "Kyle")
        )
    }

    @Test
    fun `a bare placeholder counts too`() {
        assertEquals(
            "Kyle · $expectedTime",
            RecordNameFormatter.displayName(record("Untitled"), wearerName = "Kyle")
        )
    }

    @Test
    fun `a title the user typed is never replaced`() {
        assertEquals(
            "Subtronics closing set",
            RecordNameFormatter.displayName(
                record("Subtronics closing set"),
                wearerName = "Kyle"
            )
        )
    }

    @Test
    fun `a title that merely ends in a number is left alone`() {
        // The reason this is not matched on shape. Every one of these is something a person
        // would plausibly type, and replacing them would be silently destructive.
        listOf("Coachella 2026", "Set 2", "Day 3", "Spiderman 2").forEach { title ->
            assertEquals(
                title,
                RecordNameFormatter.displayName(record(title), wearerName = "Kyle")
            )
        }
    }

    @Test
    fun `the live profile name beats the frozen one`() {
        // Correcting a spelling on the profile has to reach every recording they made.
        assertEquals(
            "Kyle · $expectedTime",
            RecordNameFormatter.displayName(
                record("Untitled 1", wearerName = "kyle "),
                wearerName = "Kyle"
            )
        )
    }

    @Test
    fun `a recording with no profile falls back to the name it was stamped with`() {
        assertEquals(
            "Ben · $expectedTime",
            RecordNameFormatter.displayName(record("Untitled 2", wearerName = "Ben"))
        )
    }

    @Test
    fun `a recording with nobody attached falls back to the watch`() {
        assertEquals(
            "Left watch · $expectedTime",
            RecordNameFormatter.displayName(record("Untitled 3"), watchName = "Left watch")
        )
    }

    @Test
    fun `then to the model the watch reported`() {
        assertEquals(
            "Galaxy Watch 6 · $expectedTime",
            RecordNameFormatter.displayName(record("Untitled 3", deviceId = "Galaxy Watch 6"))
        )
    }

    @Test
    fun `with nothing known at all it is just the time`() {
        assertEquals(expectedTime, RecordNameFormatter.displayName(record("Untitled 9")))
    }

    @Test
    fun `an empty title is treated as a placeholder`() {
        assertEquals(
            "Kyle · $expectedTime",
            RecordNameFormatter.displayName(record(""), wearerName = "Kyle")
        )
    }
}
