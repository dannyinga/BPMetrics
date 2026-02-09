package inga.bpmetrics.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BpmRecordEntity::class, BpmDataPointEntity::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bpmRecordDao(): BpmRecordDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bpmetrics_db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}