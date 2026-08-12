package inga.bpmetrics

import android.app.Application
import android.util.Log
import com.google.android.gms.wearable.Wearable
import inga.bpmetrics.datasync.DataClientListener
import inga.bpmetrics.datasync.DataClientProcessor
import inga.bpmetrics.export.BpmExportService
import inga.bpmetrics.export.ExportUtils
import inga.bpmetrics.export.RenderQueueManager
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

    /**
     * Why the library could not be opened, or null when it opened.
     *
     * The database is built as a default constructor argument to [LibraryRepository], so the first
     * touch of [libraryRepository] opens it — and until now that first touch was here, in
     * `onCreate`, on the main thread. Anything wrong with the file therefore killed the process
     * before a single pixel was drawn: no message, no way to reach the pre-migration backups
     * sitting on disk a directory away, and an identical crash on every subsequent launch.
     *
     * That is what turned "you are running an older build than the one that migrated your library"
     * into an app that appeared to have destroyed itself. The failure is caught and kept now, and
     * [inga.bpmetrics.ui.recovery.RecoveryScreen] says what happened.
     */
    @Volatile
    var databaseFailure: Throwable? = null
        private set

    /**
     * Opens the library, once, reporting failure rather than dying.
     *
     * @return true when everything below can safely touch the database.
     */
    fun openLibrary(): Boolean {
        databaseFailure?.let { return false }
        return runCatching { libraryRepository }
            .onFailure {
                databaseFailure = it
                Log.e("BPMetricsApp", "The library could not be opened", it)
            }
            .isSuccess
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
        // process that is no longer running. That stays true now the queue is persisted: a job
        // restored as queued has not rendered yet, and one restored as interrupted will start over
        // rather than resume, so neither has a staging file worth keeping.
        runCatching { ExportUtils.clearStagedExports(this) }
            .onFailure { Log.e("BPMetricsApp", "Could not reclaim staged exports", it) }

        // Everything below touches the database, and opening it is what can fail. Guarded rather
        // than assumed: the whole point is that a bad file no longer takes the process down before
        // anything can say so. [MainActivity] reads [databaseFailure] and shows the recovery screen.
        if (!openLibrary()) return

        // Recordings from before the derived figures were columns get them computed once, off the
        // main thread. Self-healing: the gate is "still null", so this also repairs anything that
        // later arrives without them.
        libraryRepository.backfillDerivedFiguresOnce()

        // The export presets the app ships with. Seeded from Kotlin rather than the migration so
        // a fresh install and an upgrade take the same path and there is one definition of what
        // ships rather than two.
        libraryRepository.seedBuiltInPresetsOnce()

        // The render queue, restored from where the last process left it. Anything the database
        // still records as rendering was interrupted — that status is only ever written by a
        // process that is now gone — so it comes back as failed with a reason and can be retried.
        //
        // Restoring only. Picking the queue back up is left to [MainActivity], because starting a
        // foreground service is not allowed from the background on Android 12 and later, and this
        // runs whenever the process starts — including when a watch delivers a recording and no
        // one has opened the app at all.
        RenderQueueManager.attach(libraryRepository.renderJobStore) { stillQueued ->
            if (stillQueued > 0) Log.i("BPMetricsApp", "$stillQueued render(s) still queued")
        }
    }
}
