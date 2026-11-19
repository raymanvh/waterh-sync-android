package com.raymanvh.waterhsync

import com.raymanvh.waterhsync.protocol.LogRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId

/**
 * Talks to the WaterH Home Assistant integration's webhook.
 *
 * Every mode: bottle status (battery, charging), which WaterH's cloud doesn't
 * have. Local mode: also the drinks read off the bottle. Home Assistant saves
 * them before replying, so a 2xx reply means they're safe to clear.
 * Blocking: call off the main thread.
 */
object HomeAssistantReporter {

    /** What Home Assistant tells the app on every call. */
    data class Reply(val mode: String?, val accepted: Int, val goalMl: Int?, val imperial: Boolean?)

    class ModeMismatchException(message: String) : IOException(message)

    fun send(url: String, info: BottleInfo): Reply {
        val body = JSONObject()
            .put("battery", info.batteryPercent)
            .put("charging", info.charging)
            .put("firmware", info.firmware)
        info.todayMl?.let { body.put("today_ml", it) }
        return post(url, body)
    }

    /** Empty report: checks the connection and fetches goal, unit and mode. */
    fun hello(url: String): Reply = post(url, JSONObject())

    /** Local mode: hands drinks to Home Assistant. Returns once they're saved there. */
    fun sendDrinks(url: String, records: List<LogRecord>, source: String): Reply {
        val zone = ZoneId.systemDefault()
        val drinks = JSONArray()
        for (r in records) {
            // A bottle whose clock was never set reports impossible dates; keep the
            // drink rather than lose it, stamped with the upload time.
            val millis = r.time.toLocalDateTimeOrNull()?.atZone(zone)?.toInstant()?.toEpochMilli()
                ?: System.currentTimeMillis()
            drinks.put(
                JSONObject()
                    .put("time", millis)
                    .put("amount_ml", r.amountMl)
                    .put("tds", r.tds)
                    .put("temperature", r.temperatureRaw * 10),
            )
        }
        return post(url, JSONObject().put("source", source).put("drinks", drinks))
    }

    private fun post(url: String, body: JSONObject): Reply {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = conn.responseCode
            val text = (if (status >= 400) conn.errorStream else conn.inputStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            when {
                status == 409 -> throw ModeMismatchException(
                    "Home Assistant's WaterH integration is in cloud mode. Add a Local only WaterH entry " +
                        "in Home Assistant and use its URL, or switch this app to WaterH account mode.",
                )
                status == 400 -> throw IOException("Home Assistant rejected the report (HTTP 400)")
                status == 404 || status == 405 ->
                    throw IOException("webhook not found — copy the URL again from the WaterH integration (HTTP $status)")
                status !in 200..299 -> throw IOException("HTTP $status")
            }
            // Older integrations reply {"ok": true} only; treat missing fields as unknown.
            val json = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
            return Reply(
                mode = json.optString("mode").ifBlank { null },
                accepted = json.optInt("accepted", 0),
                goalMl = json.optInt("goal_ml", 0).takeIf { it > 0 },
                imperial = json.optString("unit").ifBlank { null }?.let { it == "oz" },
            )
        } finally {
            conn.disconnect()
        }
    }
}
