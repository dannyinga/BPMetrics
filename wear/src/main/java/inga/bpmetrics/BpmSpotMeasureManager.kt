package inga.bpmetrics

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.unregisterMeasureCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class BpmSpotMeasureManager (
    private val context: Context
) {
    private val tag = "BPM Spot Measurement"

    private val measureClient =
        HealthServices.getClient(context).measureClient

    private val measureCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(
            dataType: DeltaDataType<*, *>,
            availability: Availability
        ) {
            BpmRepository.instance.onMeasureAvailabilityChanged(availability)
        }

        override fun onDataReceived(data: DataPointContainer) {
            BpmRepository.instance.onMeasureUpdate(data)
        }
    }

    fun start() {
        try {
            measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, measureCallback)
            Log.d(tag, "Measure callback registered")
        } catch (e: Exception) {
            Log.e(tag, "Measure callback registration failed")
        }
    }

    suspend fun stop() {
        try {
            measureClient.unregisterMeasureCallback(DataType.HEART_RATE_BPM, measureCallback)
            Log.d(tag, "Measure callback unregistered")
        } catch (e: Exception) {
            Log.e(tag, "Failed to unregister Measure callback", e)
        }
    }
}