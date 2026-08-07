package inga.bpmetrics

import android.app.Application
import android.util.Log
import com.google.android.gms.wearable.Wearable
import inga.bpmetrics.datasync.DataClientListener
import inga.bpmetrics.datasync.DataClientProcessor
import inga.bpmetrics.export.ExportUtils
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.ui.settings.SettingsRepository

/**
 * Custom Application class for the BPMetrics mobile app.
 */
class BPMetricsApp : Application() {

    private val dataClient by lazy {
        Wearable.getDataClient(this)
    }

    /**
     * Singleton instance of [SettingsRepository] to manage app preferences.
     */
    val settingsRepository by lazy {
        SettingsRepository(this)
    }

    /**
     * Singleton instance of [LibraryRepository] to manage BPM record storage.
     */
    val libraryRepository by lazy {
        LibraryRepository(this, settingsRepository)
    }

    /**
     * Singleton instance of [DataClientProcessor] to handle incoming records from the watch.
     */
    val dataClientProcessor by lazy {
        DataClientProcessor(dataClient, libraryRepository)
    }

    /**
     * Singleton instance of [DataClientListener] to listen for Wearable data events.
     */
    val dataClientListener by lazy {
        DataClientListener(dataClient, dataClientProcessor)
    }

    override fun onCreate() {
        super.onCreate()

        // Reclaim staged exports before anything opens the database.
        //
        // A render is written to the cache and copied to its destination; until recently the
        // staging copy was never removed, so every video ever exported was still on the phone at
        // full size. Doing this first matters on a device that has already filled up: opening the
        // database on a full disk is what fails, and this is what makes room for it to succeed.
        //
        // Anything found here is finished with by definition — a render in progress belongs to a
        // process that is no longer running.
        runCatching { ExportUtils.clearStagedExports(this) }
            .onFailure { Log.e("BPMetricsApp", "Could not reclaim staged exports", it) }

        // Saved same-time analyses become events, once. They were events in all but name — a set
        // of recordings that happened together, under a name — and leaving both would be two half
        // features that do not know about each other.
        libraryRepository.convertConcurrentAnalysesOnce()
    }
}
