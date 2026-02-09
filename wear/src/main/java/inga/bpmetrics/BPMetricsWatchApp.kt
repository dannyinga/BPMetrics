package inga.bpmetrics

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log

class BPMetricsWatchApp: Application() {

    private val tag = "BpmApplication"
    lateinit var controller: BpmWatchController

    override fun onCreate() {
        super.onCreate()
        controller = BpmWatchController.getInstance(this)
        controller.bindService()
        Log.d(tag, "Controller initialized and service bound")
    }

    override fun onTerminate() {
        super.onTerminate()
        controller.stopRecording()
        controller.unbindService()
        Log.d(tag, "Application terminated, controller unbound")
    }
}