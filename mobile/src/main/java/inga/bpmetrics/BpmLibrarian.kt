package inga.bpmetrics

import android.content.Context
import android.util.Log
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.library.BpmLibrary
import inga.bpmetrics.library.BpmRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BpmLibrarian private constructor(
    private val scope: CoroutineScope,
    context: Context
){
    companion object {
        @Volatile private var instance: BpmLibrarian? = null

        private val tag = "BpmLibrarian"

        // SupervisorJob so one failure doesn’t cancel all
        private val job = SupervisorJob()

        // Runs on Dispatchers.Default unless you specify otherwise
        val applicationScope = CoroutineScope(job + Dispatchers.Default)

        private fun initialize(){
            instance = BpmLibrarian(applicationScope, BPMetricsApp.getAppContext())
        }

        fun getInstance(): BpmLibrarian {
            if (instance == null)
                initialize()

            return instance!!
        }
    }
    private val library = BpmLibrary(context)

    fun startObservingLibrary(onUpdate: (List<BpmRecord>) -> Unit) {
        scope.launch {
//            Log.d(tag, "Beginning realtime observation of library")
            library.loadLibraryFlowFromDB(onUpdate)
        }
    }

    fun sendWatchRecordToLibrary(watchRecord: BpmWatchRecord) {
        scope.launch {
            library.saveWatchRecordToLibrary(watchRecord)
        }
    }

    fun updateRecordMetadata(record: BpmRecord) {
        scope.launch {
            library.updateRecordMetadata(record)
        }
    }

    fun useSampleData() {
        scope.launch {
            library.uploadSampleDataToDB()
        }
    }

    fun deleteAll() {
        scope.launch {
            library.deleteAll()
        }
    }
}