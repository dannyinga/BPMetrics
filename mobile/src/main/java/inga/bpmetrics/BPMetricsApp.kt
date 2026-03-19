package inga.bpmetrics

import android.app.Application
import com.google.android.gms.wearable.Wearable
import inga.bpmetrics.datasync.DataClientListener
import inga.bpmetrics.datasync.DataClientProcessor
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
    }
}
