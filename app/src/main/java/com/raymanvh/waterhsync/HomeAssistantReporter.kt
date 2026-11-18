package com.raymanvh.waterhsync

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Posts bottle status to the WaterH Home Assistant integration's webhook.
 * The cloud has no battery data, so this is the only way it gets to Home Assistant.
 * Blocking: call off the main thread.
 */
object HomeAssistantReporter {
    fun send(url: String, info: BottleInfo) {
        val body = JSONObject()
            .put("battery", info.batteryPercent)
            .put("charging", info.charging)
            .put("firmware", info.firmware)
        info.todayMl?.let { body.put("today_ml", it) }

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = conn.responseCode
            if (status !in 200..299) {
                throw IOException(
                    when (status) {
                        400 -> "Home Assistant rejected the report (HTTP 400)"
                        404, 405 -> "webhook not found — copy the URL again from the WaterH integration (HTTP $status)"
                        else -> "HTTP $status"
                    },
                )
            }
        } finally {
            conn.disconnect()
        }
    }
}
