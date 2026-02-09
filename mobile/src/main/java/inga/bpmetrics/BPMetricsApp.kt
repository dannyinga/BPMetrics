package inga.bpmetrics

import android.app.Application
import android.content.Context
import android.util.Log
class BPMetricsApp : Application() {
    companion object {
        private lateinit var instance: BPMetricsApp

        fun getAppContext(): Context = instance.applicationContext
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onTerminate() {
        super.onTerminate()
    }

}