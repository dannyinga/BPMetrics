package inga.bpmetrics.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import java.sql.Date

/**
 * Shared Gson instance with robust date handling across different Wear OS and Android devices.
 * Serializes [java.sql.Date] as epoch millisecond timestamps to avoid locale formatting discrepancies.
 */
object BpmGson {
    val instance: Gson = GsonBuilder()
        .registerTypeAdapter(Date::class.java, JsonSerializer<Date> { src, _, _ ->
            JsonPrimitive(src.time)
        })
        .registerTypeAdapter(Date::class.java, JsonDeserializer { json, _, _ ->
            try {
                if (json.isJsonPrimitive && json.asJsonPrimitive.isNumber) {
                    Date(json.asLong)
                } else {
                    val rawStr = json.asString
                    val epoch = rawStr.toLongOrNull()
                    if (epoch != null) {
                        Date(epoch)
                    } else {
                        try {
                            Date(java.time.Instant.parse(rawStr).toEpochMilli())
                        } catch (e: Exception) {
                            Date(System.currentTimeMillis())
                        }
                    }
                }
            } catch (e: Exception) {
                Date(System.currentTimeMillis())
            }
        })
        .create()
}
