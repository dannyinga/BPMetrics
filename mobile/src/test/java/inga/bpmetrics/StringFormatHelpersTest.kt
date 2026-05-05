package inga.bpmetrics

import com.google.common.truth.Truth.assertThat
import inga.bpmetrics.ui.util.StringFormatHelpers
import org.junit.Test
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class StringFormatHelpersTest {

    @Test
    fun getDurationString_formatsMillisecondsCorrectly() {
        assertThat(StringFormatHelpers.getDurationString(5430500)).isEqualTo("1h 30m 30s 500 ms")
        assertThat(StringFormatHelpers.getDurationString(60000)).isEqualTo("1m 0s 0 ms")
        assertThat(StringFormatHelpers.getDurationString(59999)).isEqualTo("59s 999 ms")
        assertThat(StringFormatHelpers.getDurationString(1000)).isEqualTo("1s 0 ms")
        assertThat(StringFormatHelpers.getDurationString(999)).isEqualTo("999 ms")
        assertThat(StringFormatHelpers.getDurationString(0)).isEqualTo("0 ms")
    }

    @Test
    fun getDateString_formatsEpochMilliToDate() {
        // This test relies on the default system timezone.
        // To make it more robust, consider passing a ZoneId to the function.
        val instant = 1678886400000L // 2023-03-15 in many timezones
        val formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy").withZone(ZoneId.systemDefault())
        val expectedDate = formatter.format(java.time.Instant.ofEpochMilli(instant))

        assertThat(StringFormatHelpers.getDateString(instant)).isEqualTo(expectedDate)
    }

    @Test
    fun getTimeString_formatsEpochMilliToTime() {
        // This test relies on the default system timezone and locale.
        // To make it more robust, consider passing ZoneId and Locale to the function.
        Locale.setDefault(Locale.US) // For AM/PM format
        val instant = 1678886400000L // Represents a specific moment in time
        val formatter = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.US).withZone(ZoneId.systemDefault())
        val expectedTime = formatter.format(java.time.Instant.ofEpochMilli(instant))

        assertThat(StringFormatHelpers.getTimeString(instant)).isEqualTo(expectedTime)
    }
}