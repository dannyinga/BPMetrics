package inga.bpmetrics.ui.settings

import android.content.Context
import android.util.Log
import java.io.File

/**
 * What this app is using the phone's storage for.
 *
 * Exists because none of it was visible. The app has already filled a phone once — every exported
 * video was retained in the cache invisibly, and the database backup routine consumed what was
 * left, at which point the database could not open. Both causes are fixed, but neither was ever
 * *observable*: there is still no way to see that `files/db_backups` holds five copies of the
 * database, and no way to restore one of them without a cable and `adb`.
 *
 * A backup nobody can reach is not a backup.
 */
object StorageInspector {

    private const val TAG = "StorageInspector"
    private const val BACKUP_DIR = "db_backups"
    private const val DB_NAME = "bpmetrics_db"

    /** One line of the breakdown. */
    data class Item(val label: String, val bytes: Long, val detail: String? = null)

    /** A database backup on disk, and when it was taken. */
    data class Backup(val file: File, val takenAtMs: Long, val bytes: Long)

    data class Report(
        val items: List<Item>,
        val backups: List<Backup>,
        val freeBytes: Long
    ) {
        val totalBytes: Long get() = items.sumOf { it.bytes }
    }

    /**
     * Measures what is on disk. Call off the main thread — it walks directories.
     */
    fun inspect(context: Context): Report {
        val backups = listBackups(context)
        val staged = context.cacheDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.lowercase() in setOf("mp4", "png", "jpg", "jpeg") }

        val databaseBytes = context.getDatabasePath(DB_NAME)?.let { db ->
            // The write-ahead log and shared-memory file are part of the database's footprint, and
            // the WAL in particular can be larger than the database itself.
            sizeOf(db) + sizeOf(File(db.path + "-wal")) + sizeOf(File(db.path + "-shm"))
        } ?: 0L

        return Report(
            items = listOf(
                Item("Database", databaseBytes, "Recordings, events, tags and presets"),
                Item(
                    "Backups",
                    backups.sumOf { it.bytes },
                    "${backups.size} kept automatically before each upgrade"
                ),
                Item(
                    "Staged exports",
                    staged.sumOf { it.length() },
                    if (staged.isEmpty()) {
                        "Nothing waiting"
                    } else {
                        "${staged.size} file(s) left in the cache"
                    }
                ),
                run {
                    val covers = inga.bpmetrics.library.CoverStore.all(context)
                    Item(
                        "Cover images",
                        covers.sumOf { it.length() },
                        if (covers.isEmpty()) {
                            "None set"
                        } else {
                            "${covers.size} picture(s), downscaled copies"
                        }
                    )
                },
                Item("Other cache", cacheBytesExcluding(context, staged), null)
            ),
            backups = backups,
            freeBytes = context.filesDir.usableSpace
        )
    }

    /** The database backups, newest first. */
    fun listBackups(context: Context): List<Backup> {
        val dir = File(context.filesDir, BACKUP_DIR)
        if (!dir.isDirectory) return emptyList()

        return dir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith(DB_NAME) && it.name.endsWith(".db") }
            .map { Backup(it, it.lastModified(), it.length()) }
            .sortedByDescending { it.takenAtMs }
    }

    /**
     * Puts a backup back, replacing the live database.
     *
     * The database must be closed first and the process restarted afterwards: Room caches the open
     * connection, and swapping the file underneath it leaves every query reading a handle to a file
     * that no longer exists. Restoring is therefore not something that can happen quietly in the
     * background — the caller relaunches.
     *
     * The current database is copied aside before it is replaced. Restoring is the one action here
     * that destroys data, and doing it without a way back would be indefensible.
     *
     * @return null on success, or a message describing what stopped it.
     */
    fun restore(context: Context, backup: Backup): String? {
        val live = context.getDatabasePath(DB_NAME) ?: return "No database to replace"
        if (!backup.file.isFile) return "That backup is no longer on the phone"

        val needed = backup.bytes * 2
        if (live.parentFile?.usableSpace?.let { it < needed } == true) {
            return "Not enough free space to restore safely"
        }

        return try {
            val supersededDir = File(context.filesDir, BACKUP_DIR).apply { mkdirs() }
            val superseded = File(supersededDir, "${DB_NAME}_replaced_${System.currentTimeMillis()}.db")
            if (live.exists()) live.copyTo(superseded, overwrite = true)

            backup.file.copyTo(live, overwrite = true)
            // The old write-ahead log describes the database that was just replaced. Left behind,
            // SQLite would replay it over the restored file and undo most of the restore.
            File(live.path + "-wal").delete()
            File(live.path + "-shm").delete()

            Log.i(TAG, "Restored ${backup.file.name}; previous database kept as ${superseded.name}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Could not restore ${backup.file.name}", e)
            "Could not restore that backup: ${e.message}"
        }
    }

    fun deleteBackup(backup: Backup): Boolean = runCatching {
        // The companion files share the backup's timestamp prefix.
        val base = backup.file.path.removeSuffix(".db")
        File("$base.db-wal").delete()
        File("$base.db-shm").delete()
        backup.file.delete()
    }.getOrDefault(false)

    private fun sizeOf(file: File): Long = if (file.isFile) file.length() else 0L

    private fun cacheBytesExcluding(context: Context, excluded: List<File>): Long {
        val skip = excluded.map { it.path }.toSet()
        return runCatching {
            context.cacheDir.walkBottomUp()
                .filter { it.isFile && it.path !in skip }
                .sumOf { it.length() }
        }.getOrDefault(0L)
    }

    /** Sizes people can act on. Nobody decides anything from a byte count. */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
        bytes > 0 -> "$bytes bytes"
        else -> "empty"
    }
}
