package inga.bpmetrics.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** Bulk edits name both kinds of thing picked. With only events, Edit said "Edit 0 recordings". */
class SelectionLabelTest {

    @Test
    fun `events alone are counted as events`() {
        assertEquals("2 events", selectionLabel(eventCount = 2, recordCount = 0))
        assertEquals("1 event", selectionLabel(eventCount = 1, recordCount = 0))
    }

    @Test
    fun `recordings alone read as before`() {
        assertEquals("3 recordings", selectionLabel(eventCount = 0, recordCount = 3))
    }

    @Test
    fun `a mix names both`() {
        assertEquals("1 event and 1 recording", selectionLabel(eventCount = 1, recordCount = 1))
    }
}
