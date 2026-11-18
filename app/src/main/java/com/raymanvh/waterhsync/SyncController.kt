package com.raymanvh.waterhsync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.raymanvh.waterhsync.api.WaterHApi
import com.raymanvh.waterhsync.protocol.LiveKind
import com.raymanvh.waterhsync.protocol.LogRecord
import com.raymanvh.waterhsync.protocol.Packet
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.util.concurrent.Executors

data class SyncedDrink(val timeMillis: Long, val ml: Int)

data class FoundBottle(val name: String, val address: String, val rssi: Int)

data class BottleInfo(
    val batteryPercent: Int,
    val charging: Boolean,
    val firmware: String,
    val todayMl: Int?,
    val seenAt: Long,
)

/**
 * App state and orchestration: bottle session → WaterH upload → clear.
 * All state is Compose state and is only touched on the main thread.
 */
@SuppressLint("MissingPermission")
class SyncController private constructor(context: Context) : BottleClient.Listener {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("waterh", Context.MODE_PRIVATE)
    private val api = WaterHApi(prefs)
    private val client = BottleClient(appContext, this)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // ---- account ----
    var signedIn by mutableStateOf(api.isSignedIn)
        private set
    var email by mutableStateOf(api.email)
        private set
    var imperial by mutableStateOf(api.imperial)
        private set
    var signingIn by mutableStateOf(false)
        private set
    var signInError by mutableStateOf<String?>(null)
        private set

    // ---- bottle ----
    var bottleAddress by mutableStateOf(prefs.getString(KEY_ADDRESS, null))
        private set
    var bottleName by mutableStateOf(prefs.getString(KEY_NAME, null))
        private set
    var bottleInfo by mutableStateOf(loadBottleInfo())
        private set

    // ---- sync ----
    var busy by mutableStateOf(false)
        private set
    var progress by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var lastSyncAt by mutableStateOf(prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0 })
        private set
    var lastResult by mutableStateOf(prefs.getString(KEY_LAST_RESULT, null))
        private set
    val history = mutableStateListOf<SyncedDrink>().apply { addAll(loadHistory()) }
    val rawLog = mutableStateListOf<String>()

    private var readOnlyRun = false
    private var backgroundRun = false

    // ---- background sync ----
    var autoSync by mutableStateOf(prefs.getBoolean(KEY_AUTO_SYNC, false))
        private set
    /** Last problem seen by a background sync; shown quietly, never as an error banner. */
    var backgroundNote by mutableStateOf<String?>(null)
        private set
    /** Set by [SyncService] to refresh its notification. */
    var onBackgroundUpdate: (() -> Unit)? = null

    // ---- Home Assistant (optional) ----
    var haWebhookUrl by mutableStateOf(prefs.getString(KEY_HA_WEBHOOK, null))
        private set
    /** Result of the last report to Home Assistant, for the settings screen. */
    var haStatus by mutableStateOf(prefs.getString(KEY_HA_STATUS, null))
        private set

    // ---- scanning ----
    var scanning by mutableStateOf(false)
        private set
    val found = mutableStateListOf<FoundBottle>()

    // ======================================================================
    // Account

    fun signIn(email: String, password: String) {
        if (signingIn) return
        signingIn = true
        signInError = null
        io.execute {
            val result = runCatching { api.signIn(email, password) }
            main.post {
                signingIn = false
                result.onSuccess {
                    signedIn = true
                    this.email = api.email
                    imperial = it.imperial
                }.onFailure {
                    signInError = it.message ?: "Sign-in failed"
                }
            }
        }
    }

    fun signOut() {
        api.signOut()
        signedIn = false
        email = null
    }

    // ======================================================================
    // Sync

    /** Full sync: set clock, read drinks, upload to WaterH, clear the bottle. */
    fun sync() = start(readOnly = false, background = false)

    /** Same as [sync], started by [SyncService] when the bottle is seen. */
    fun syncInBackground() = start(readOnly = false, background = true)

    /** Diagnostic read that changes nothing on the bottle. */
    fun checkOnly() = start(readOnly = true, background = false)

    fun setAutoSyncEnabled(enabled: Boolean) {
        autoSync = enabled
        prefs.edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
        if (enabled && bottleAddress != null) SyncService.start(appContext) else SyncService.stop(appContext)
    }

    /** Starts the service if auto sync is on (e.g. when the app opens). */
    fun ensureServiceRunning() {
        if (autoSync && bottleAddress != null) runCatching { SyncService.start(appContext) }
    }

    fun noteBackground(message: String) {
        backgroundNote = message
    }

    private fun start(readOnly: Boolean, background: Boolean) {
        val address = bottleAddress ?: return
        if (busy) return
        if (!readOnly && !signedIn) {
            if (!background) error = "Sign in to WaterH first."
            return
        }
        if (!background) stopScan()
        readOnlyRun = readOnly
        backgroundRun = background
        rawLog.clear()
        if (!background) error = null
        busy = true
        progress = "Starting…"
        onBackgroundUpdate?.invoke()
        client.start(address, readOnly)
    }

    fun cancel() = client.cancel()

    override fun onProgress(message: String) {
        progress = message
        onBackgroundUpdate?.invoke()
    }

    override fun onStatus(status: Packet.BoostStatus) {
        val info = BottleInfo(
            batteryPercent = status.batteryPercent,
            charging = status.charging,
            firmware = status.firmware,
            todayMl = status.todayMl,
            seenAt = System.currentTimeMillis(),
        )
        bottleInfo = info
        prefs.edit()
            .putInt("battery", info.batteryPercent)
            .putBoolean("charging", info.charging)
            .putString("firmware", info.firmware)
            .putInt("today_ml", info.todayMl ?: -1)
            .putLong("info_at", info.seenAt)
            .apply()
        reportToHomeAssistant(info)
    }

    // ======================================================================
    // Home Assistant

    /** Saves the webhook URL from the WaterH integration (blank clears it). */
    fun setHomeAssistantUrl(url: String) {
        val clean = url.trim().ifBlank { null }
        haWebhookUrl = clean
        haStatus = null
        prefs.edit().putString(KEY_HA_WEBHOOK, clean).remove(KEY_HA_STATUS).apply()
        if (clean != null) bottleInfo?.let { reportToHomeAssistant(it) }
    }

    /** Sends the last known bottle status again (the "Send test" button). */
    fun sendHomeAssistantTest() {
        val info = bottleInfo
        if (info == null) {
            haStatus = "Sync once first so there's bottle status to send."
            return
        }
        reportToHomeAssistant(info)
    }

    private fun reportToHomeAssistant(info: BottleInfo) {
        val url = haWebhookUrl ?: return
        io.execute {
            val result = runCatching { HomeAssistantReporter.send(url, info) }
            main.post {
                val time = java.time.LocalTime.now().withNano(0)
                val text = result.fold(
                    onSuccess = { "Reported to Home Assistant at $time" },
                    onFailure = { "Couldn't reach Home Assistant at $time: ${it.message}" },
                )
                haStatus = text
                prefs.edit().putString(KEY_HA_STATUS, text).apply()
            }
        }
    }

    override fun onLive(update: Packet.Live) {
        val info = bottleInfo ?: return
        bottleInfo = when (update.kind) {
            LiveKind.BATTERY -> info.copy(batteryPercent = update.value)
            LiveKind.CHARGING -> info.copy(charging = update.value == 1)
            else -> info
        }
    }

    override fun onLogs(records: List<LogRecord>, bleData: List<String>, total: Int, complete: Boolean) {
        val drinks = records.filter { it.isDrink }
        if (readOnlyRun) {
            setResult(
                if (drinks.isEmpty()) "Check: no drinks waiting on the bottle."
                else "Check: ${drinks.size} drinks (${formatVolume(drinks.sumOf { it.amountMl })}) waiting on the bottle. Nothing was changed.",
                persist = false,
            )
            return
        }
        if (!complete) return // BottleClient finishes with an error and never clears.
        if (total == 0) {
            setResult("Up to date — no new drinks on the bottle.")
            return
        }
        progress = "Uploading ${drinks.size} drinks to WaterH…"
        val address = bottleAddress ?: return client.finishWithoutClearing("No bottle selected")
        val source = (client.deviceName ?: bottleName ?: "waterh-boost-946ml").lowercase()
        io.execute {
            val result = runCatching { api.uploadBottleDrinks(drinks, bleData, address, source) }
            main.post {
                result.onSuccess {
                    addHistory(drinks)
                    setResult(
                        if (drinks.isEmpty()) "Up to date — no new drinks on the bottle."
                        else "Synced ${drinks.size} ${if (drinks.size == 1) "drink" else "drinks"} (${formatVolume(drinks.sumOf { it.amountMl })}).",
                    )
                    client.confirmUploaded(total)
                }.onFailure {
                    if (it is WaterHApi.ApiException && it.status == 401) signedIn = api.isSignedIn
                    client.finishWithoutClearing(
                        "Upload failed: ${it.message}. Your drinks are still on the bottle and will sync next time.",
                    )
                }
            }
        }
    }

    override fun onRaw(line: String) {
        val time = java.time.LocalTime.now().withNano(0)
        rawLog.add("$time $line")
        if (rawLog.size > 300) rawLog.removeAt(0)
    }

    override fun onFinished(error: String?) {
        busy = false
        progress = null
        if (backgroundRun) {
            // The bottle often drifts out of range or back to sleep; keep it quiet.
            if (error != null) backgroundNote = "${java.time.LocalTime.now().withNano(0)}: $error"
        } else {
            this.error = error
        }
        if (error == null && !readOnlyRun) {
            val now = System.currentTimeMillis()
            lastSyncAt = now
            prefs.edit().putLong(KEY_LAST_SYNC, now).apply()
            if (backgroundRun) backgroundNote = null
        }
        onBackgroundUpdate?.invoke()
    }

    private fun setResult(text: String, persist: Boolean = true) {
        lastResult = text
        if (persist) prefs.edit().putString(KEY_LAST_RESULT, text).apply()
    }

    // ======================================================================
    // Bottle selection

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: return
            if (!name.contains("waterh", ignoreCase = true)) return
            val address = result.device.address
            val i = found.indexOfFirst { it.address == address }
            val item = FoundBottle(name, address, result.rssi)
            if (i >= 0) found[i] = item else found.add(item)
        }

        override fun onScanFailed(errorCode: Int) {
            error = "Bluetooth scan failed ($errorCode)"
            stopScan()
        }
    }

    fun startScan() {
        if (scanning || busy) return
        val scanner = appContext.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            error = "Bluetooth is off"
            return
        }
        error = null
        found.clear()
        scanning = true
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
        main.postDelayed({ stopScan() }, 20_000)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching {
            appContext.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
                ?.stopScan(scanCallback)
        }
    }

    fun selectBottle(bottle: FoundBottle) {
        stopScan()
        bottleAddress = bottle.address
        bottleName = bottle.name
        prefs.edit().putString(KEY_ADDRESS, bottle.address).putString(KEY_NAME, bottle.name).apply()
        if (autoSync) {
            // Restart so the service scans for the new bottle.
            SyncService.stop(appContext)
            SyncService.start(appContext)
        }
    }

    fun forgetBottle() {
        if (busy) return
        bottleAddress = null
        bottleName = null
        bottleInfo = null
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_NAME).remove("info_at").apply()
        SyncService.stop(appContext)
    }

    // ======================================================================
    // Helpers and persistence

    fun formatVolume(ml: Int): String =
        if (imperial) "%.1f oz".format(ml / ML_PER_OZ) else "$ml mL"

    private fun loadBottleInfo(): BottleInfo? {
        val at = prefs.getLong("info_at", 0L)
        if (at == 0L) return null
        return BottleInfo(
            batteryPercent = prefs.getInt("battery", 0),
            charging = prefs.getBoolean("charging", false),
            firmware = prefs.getString("firmware", "?") ?: "?",
            todayMl = prefs.getInt("today_ml", -1).takeIf { it >= 0 },
            seenAt = at,
        )
    }

    private fun addHistory(drinks: List<LogRecord>) {
        val zone = ZoneId.systemDefault()
        val new = drinks.mapNotNull { d ->
            d.time.toLocalDateTimeOrNull()?.let { SyncedDrink(it.atZone(zone).toInstant().toEpochMilli(), d.amountMl) }
        }
        val merged = (history + new).distinctBy { it.timeMillis to it.ml }
            .sortedByDescending { it.timeMillis }
            .take(MAX_HISTORY)
        history.clear()
        history.addAll(merged)
        val arr = JSONArray()
        merged.forEach { arr.put(JSONObject().put("t", it.timeMillis).put("ml", it.ml)) }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    private fun loadHistory(): List<SyncedDrink> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SyncedDrink(o.getLong("t"), o.getInt("ml"))
        }
    }.getOrDefault(emptyList())

    companion object {
        @Volatile private var instance: SyncController? = null

        /** One controller for the whole app, so a sync survives screen rotation. */
        fun get(context: Context): SyncController =
            instance ?: synchronized(this) { instance ?: SyncController(context).also { instance = it } }

        private const val ML_PER_OZ = 29.5735295625
        private const val MAX_HISTORY = 100
        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_LAST_RESULT = "last_result"
        private const val KEY_HISTORY = "history"
        const val KEY_AUTO_SYNC = "auto_sync"
        private const val KEY_HA_WEBHOOK = "ha_webhook"
        private const val KEY_HA_STATUS = "ha_status"
    }
}
