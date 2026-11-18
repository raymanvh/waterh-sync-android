package com.raymanvh.waterhsync.api

import android.content.SharedPreferences
import com.raymanvh.waterhsync.protocol.LogRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/**
 * Minimal client for WaterH's private cloud API (the one the official app
 * uses). Blocking calls: run them off the main thread.
 *
 * Only tokens are stored, never the password. The client credentials are the
 * official app's public ones, the same as in the Home Assistant integration.
 */
class WaterHApi(private val prefs: SharedPreferences) {

    data class Account(
        val userId: Int,
        val email: String?,
        val name: String?,
        val imperial: Boolean,
        val goalMl: Int?,
    )

    class ApiException(val status: Int, message: String) : IOException(message)

    val isSignedIn: Boolean get() = prefs.getString(KEY_REFRESH, null) != null

    val email: String? get() = prefs.getString(KEY_EMAIL, null)

    val imperial: Boolean get() = prefs.getString(KEY_UNIT, "imperial") != "metric"

    fun signIn(email: String, password: String): Account {
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .put("grant_type", "password")
            .put("client_id", CLIENT_ID)
            .put("client_secret", CLIENT_SECRET)
        val (status, text) = request("POST", "/account/oauth/token/", body, token = null)
        if (status == 400 || status == 401) throw ApiException(status, "Wrong email or password")
        if (status !in 200..299) throw ApiException(status, "Sign-in failed (HTTP $status)")
        saveTokens(JSONObject(text))
        prefs.edit().putString(KEY_EMAIL, email.trim()).apply()
        return refreshAccount()
    }

    fun signOut() {
        prefs.edit()
            .remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_EXPIRES)
            .remove(KEY_USER).remove(KEY_EMAIL).remove(KEY_UNIT)
            .apply()
    }

    /** Reads the profile (user id, units, goal) and caches what the app needs. */
    fun refreshAccount(): Account {
        val json = JSONObject(authed("GET", "/account/self/", null).second)
        val settings = json.optJSONObject("settings")
        val unit = settings?.optString("unit", "imperial") ?: "imperial"
        val account = Account(
            userId = json.getInt("id"),
            email = json.optString("email").ifBlank { null },
            name = listOf(json.optString("first_name"), json.optString("last_name"))
                .filter { it.isNotBlank() }.joinToString(" ").ifBlank { null },
            imperial = unit != "metric",
            goalMl = json.optInt("goal", 0).takeIf { it > 0 },
        )
        prefs.edit().putInt(KEY_USER, account.userId).putString(KEY_UNIT, unit).apply()
        return account
    }

    /**
     * Uploads drinks read from the bottle, in the same shape as the official
     * app. Returns normally if WaterH accepted them or already had them
     * (HTTP 409); only then is it safe to clear them from the bottle.
     */
    fun uploadBottleDrinks(
        drinks: List<LogRecord>,
        bleData: List<String>,
        macAddress: String,
        sourceId: String,
    ) {
        if (drinks.isEmpty()) return
        val userId = prefs.getInt(KEY_USER, -1).takeIf { it > 0 } ?: refreshAccount().userId
        val zone = ZoneId.systemDefault()
        val data = JSONArray()
        for (d in drinks) {
            val local = d.time.toLocalDateTimeOrNull() ?: continue
            val utc = local.atZone(zone).withZoneSameInstant(ZoneOffset.UTC)
            data.put(
                JSONObject()
                    .put("amount", d.amountMl)
                    .put("date_created", UTC_FORMAT.format(utc))
                    .put("water_type", "water")
                    .put("source_id", sourceId)
                    .put("mac_address", macAddress)
                    .put("tds", d.tds)
                    .put("temperature", d.temperatureRaw * 10),
            )
        }
        val body = JSONObject().put("data", data).put("ble_data", JSONArray(bleData))
        val (status, text) = authed("POST", "/account/$userId/log", body)
        if (status == 409) return // WaterH already has these drinks.
        if (status !in 200..299) {
            throw ApiException(status, "WaterH rejected the upload (HTTP $status): ${text.take(200)}")
        }
    }

    // ---- plumbing --------------------------------------------------------

    private fun saveTokens(json: JSONObject) {
        val expiresIn = json.optLong("expires_in", 3600)
        prefs.edit()
            .putString(KEY_ACCESS, json.getString("access_token"))
            .putString(KEY_REFRESH, json.optString("refresh_token").ifBlank { prefs.getString(KEY_REFRESH, null) })
            .putLong(KEY_EXPIRES, System.currentTimeMillis() + expiresIn * 1000)
            .apply()
    }

    private fun refreshTokens() {
        val refresh = prefs.getString(KEY_REFRESH, null)
            ?: throw ApiException(401, "Not signed in")
        val body = JSONObject()
            .put("grant_type", "refresh_token")
            .put("refresh_token", refresh)
            .put("client_id", CLIENT_ID)
            .put("client_secret", CLIENT_SECRET)
        val (status, text) = request("POST", "/account/oauth/token/", body, token = null)
        if (status == 400 || status == 401) {
            signOut()
            throw ApiException(401, "Your WaterH sign-in expired. Please sign in again.")
        }
        if (status !in 200..299) throw ApiException(status, "Couldn't refresh sign-in (HTTP $status)")
        saveTokens(JSONObject(text))
    }

    private fun accessToken(): String {
        val expires = prefs.getLong(KEY_EXPIRES, 0)
        if (System.currentTimeMillis() > expires - 5 * 60_000) refreshTokens()
        return prefs.getString(KEY_ACCESS, null) ?: throw ApiException(401, "Not signed in")
    }

    private fun authed(method: String, path: String, body: JSONObject?): Pair<Int, String> {
        var result = request(method, path, body, accessToken())
        if (result.first == 401) {
            refreshTokens()
            result = request(method, path, body, accessToken())
        }
        if (result.first == 401) throw ApiException(401, "WaterH refused the sign-in")
        if (result.first == 429) throw ApiException(429, "WaterH is rate limiting; try again later")
        return result
    }

    private fun request(method: String, path: String, body: JSONObject?, token: String?): Pair<Int, String> {
        val conn = URL(BASE_URL + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-App-Version", APP_VERSION)
            conn.setRequestProperty("Time-Zone", TimeZone.getDefault().id)
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return status to text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val BASE_URL = "https://api.waterh.com"
        private const val APP_VERSION = "3.5.1"
        private const val CLIENT_ID = "Qm2VE4FOkzHLHaeYg2JEl4K8BJXYs5iv9ESb5uIX"
        private const val CLIENT_SECRET =
            "Su4fJaRREK9EudcPY7dipTlv6vbPGOiJsB4aSupdEWyAmYq7VqiNhKeyttDR5yIBfugSLQRLLiQz2Vw0d4UAOw6gcmIfOaLK4rmjbRVwBVefqPwEBxDIMXQuq42TWJ4s"

        private val UTC_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+00:00'")

        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES = "token_expires_at"
        private const val KEY_USER = "user_id"
        private const val KEY_EMAIL = "email"
        private const val KEY_UNIT = "unit"
    }
}
