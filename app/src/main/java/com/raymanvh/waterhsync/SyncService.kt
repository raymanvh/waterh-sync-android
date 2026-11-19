package com.raymanvh.waterhsync

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps a low-power Bluetooth scan running for the saved bottle. The bottle
 * advertises when it wakes up (after a drink, or when tilted or charging);
 * each time it's seen, a sync runs unless one ran recently (see [cooldownMs]).
 *
 * Local mode syncs as often as every minute so back-to-back sips arrive
 * quickly; WaterH account mode keeps 3 minutes to stay light on WaterH's API.
 * While the bottle sits on its charger it advertises constantly, so after an
 * empty sync on the charger the app backs off to 15 minutes.
 *
 * Runs as a foreground service so Android keeps it alive, which is why it
 * shows a permanent notification.
 */
@SuppressLint("MissingPermission")
class SyncService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var controller: SyncController
    private var scanning = false
    private var lastAttempt = 0L
    private var lastSeen = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        controller = SyncController.get(this)
        createChannel(this)
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        startForeground(NOTIFICATION_ID, buildNotification(), type)
        controller.onBackgroundUpdate = { updateNotification() }
        startScan()
        // Restart the scan periodically; some phones quietly stop long scans.
        main.postDelayed(rescan, RESCAN_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!scanning) startScan()
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopScan()
        controller.onBackgroundUpdate = null
        super.onDestroy()
    }

    private val rescan: Runnable = object : Runnable {
        override fun run() {
            stopScan()
            startScan()
            main.postDelayed(this, RESCAN_MS)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { onBottleSeen() }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            if (results.isNotEmpty()) main.post { onBottleSeen() }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            controller.noteBackground("Bluetooth scan failed ($errorCode); retrying")
            main.postDelayed({ startScan() }, 60_000)
        }
    }

    private fun onBottleSeen() {
        val now = System.currentTimeMillis()
        lastSeen = now
        if (controller.busy || !controller.canSync) return
        if (now - lastAttempt < cooldownMs()) return
        lastAttempt = now
        controller.syncInBackground()
        updateNotification()
    }

    private fun cooldownMs(): Long {
        val charging = controller.bottleInfo?.charging == true
        return when {
            charging && !controller.lastSyncHadDrinks -> CHARGING_IDLE_COOLDOWN_MS
            controller.isLocal -> LOCAL_COOLDOWN_MS
            else -> CLOUD_COOLDOWN_MS
        }
    }

    private fun startScan() {
        val address = controller.bottleAddress ?: return
        val scanner = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setDeviceAddress(address).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        runCatching {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
        }.onFailure {
            controller.noteBackground("Couldn't start Bluetooth scan: ${it.message}")
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching {
            getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val time = SimpleDateFormat("h:mm a", Locale.getDefault())
        val text = when {
            controller.busy -> controller.progress ?: "Syncing…"
            controller.lastSyncAt != null -> "Last synced ${time.format(Date(controller.lastSyncAt!!))}" +
                (controller.lastResult?.let { " · ${it.removeSuffix(".")}" } ?: "")
            else -> "Waiting for your bottle to wake up"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle("Watching for your bottle")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 1
        private const val LOCAL_COOLDOWN_MS = 60_000L
        private const val CLOUD_COOLDOWN_MS = 3 * 60_000L
        private const val CHARGING_IDLE_COOLDOWN_MS = 15 * 60_000L
        private const val RESCAN_MS = 20 * 60_000L

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Background sync", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while WaterH Sync watches for your bottle"
                    setShowBadge(false)
                },
            )
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }
    }
}

/** Restarts background sync after a reboot or an app update, if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences("waterh", Context.MODE_PRIVATE)
        if (prefs.getBoolean(SyncController.KEY_AUTO_SYNC, false) && prefs.getString("address", null) != null) {
            runCatching { SyncService.start(context) }
        }
    }
}
