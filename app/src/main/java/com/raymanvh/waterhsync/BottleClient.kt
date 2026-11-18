package com.raymanvh.waterhsync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.raymanvh.waterhsync.protocol.LogCollector
import com.raymanvh.waterhsync.protocol.LogRecord
import com.raymanvh.waterhsync.protocol.Packet
import com.raymanvh.waterhsync.protocol.WaterH
import com.raymanvh.waterhsync.protocol.bytesToHex
import com.raymanvh.waterhsync.protocol.parsePacket
import java.time.LocalDateTime

/**
 * One Bluetooth session with a WaterH bottle, following the official app's
 * sequence:
 *
 * 1. connect and enable notifications
 * 2. request status (battery, clock, goal, reminder)
 * 3. set the bottle's clock, resending its own goal and reminder unchanged
 *    (skipped in read-only mode)
 * 4. request the stored drinks
 * 5. hand them to [Listener.onLogs]; the caller uploads them and then calls
 *    [confirmUploaded] (clears them from the bottle) or [finishWithoutClearing]
 *
 * Every command waits 1 s first, as the official app does; the bottle ignores
 * commands sent straight after notifications are enabled.
 * All [Listener] callbacks run on the main thread.
 */
@SuppressLint("MissingPermission")
class BottleClient(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onProgress(message: String)
        fun onStatus(status: Packet.BoostStatus)
        fun onLive(update: Packet.Live)

        /**
         * Stored records are in. [total] is the count the bottle announced
         * (drinks plus flagged records). When [complete] is false the reply
         * stopped early and the session ends without clearing.
         */
        fun onLogs(records: List<LogRecord>, bleData: List<String>, total: Int, complete: Boolean)
        fun onRaw(line: String)
        fun onFinished(error: String?)
    }

    private companion object {
        /** Sanity range for a goal written to the bottle (the WaterH app allows 500–10,000 mL). */
        val GOAL_RANGE_ML = 500..10_000
    }

    private enum class Stage { IDLE, CONNECTING, STATUS, SETTINGS, LOGS, UPLOADING, CLEARING, DONE }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var stage = Stage.IDLE
    private var collector = LogCollector()
    private var timeout: Runnable? = null
    private var readOnly = true

    // The bottle negotiates the packet size itself as well as answering ours,
    // so MTU callbacks can arrive twice; set up only once per connection.
    private var discoveryStarted = false
    private var notifySetupStarted = false

    val isBusy: Boolean get() = stage != Stage.IDLE && stage != Stage.DONE

    var deviceName: String? = null
        private set

    /**
     * Goal to write to the bottle on sync (your WaterH account goal, in mL).
     * When it returns null, the bottle's own goal is resent unchanged.
     */
    var goalProvider: (() -> Int?)? = null

    /** Starts a session. With [readOnly] nothing on the bottle is changed. */
    fun start(address: String, readOnly: Boolean) {
        if (isBusy) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onFinished("Bluetooth is off")
            return
        }
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            listener.onFinished("Invalid bottle address $address")
            return
        }
        this.readOnly = readOnly
        deviceName = runCatching { device.name }.getOrNull()
        collector = LogCollector()
        discoveryStarted = false
        notifySetupStarted = false
        stage = Stage.CONNECTING
        listener.onProgress("Connecting to bottle…")
        armTimeout(25_000, "Couldn't reach the bottle. Is it nearby? Tilt it to wake it, and make sure the WaterH app isn't open.")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun cancel() = finish("Cancelled")

    /** WaterH accepted the upload: delete [total] records from the bottle and finish. */
    fun confirmUploaded(total: Int) {
        val g = gatt ?: return
        if (stage != Stage.UPLOADING) return
        stage = Stage.CLEARING
        listener.onProgress("Clearing synced drinks from the bottle…")
        armTimeout(8_000, null) // Finish quietly if the bottle doesn't answer.
        sendLater(g, WaterH.clearRecords(total), "clear $total synced records", 1_000)
        // The official app doesn't wait for a reply; give the write time to land.
        main.postDelayed({ if (g == gatt && stage == Stage.CLEARING) done() }, 3_500)
    }

    /** Upload failed or was skipped: keep the drinks on the bottle and finish. */
    fun finishWithoutClearing(error: String?) {
        if (stage == Stage.UPLOADING) finish(error) else if (error != null) finish(error)
    }

    private fun done() {
        stage = Stage.DONE
        finish(null)
    }

    private fun finish(error: String?) {
        if (stage == Stage.IDLE) return
        clearTimeout()
        stage = Stage.IDLE
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        listener.onFinished(error)
    }

    private fun armTimeout(ms: Long, message: String?) {
        clearTimeout()
        val r = Runnable {
            if (stage == Stage.LOGS && collector.records.isNotEmpty()) {
                // Partial reply: report what arrived but never clear.
                stage = Stage.UPLOADING
                listener.onLogs(collector.records, collector.bleData, collector.expected ?: 0, false)
                finish("The bottle stopped sending partway through; nothing was cleared.")
            } else if (stage == Stage.SETTINGS) {
                // No acknowledgement of the clock sync; carry on like the app would.
                requestLogs()
            } else {
                if (message == null) done() else finish(message)
            }
        }
        timeout = r
        main.postDelayed(r, ms)
    }

    private fun clearTimeout() {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
    }

    private fun sendLater(g: BluetoothGatt, bytes: ByteArray, label: String, delayMs: Long) {
        main.postDelayed({ if (g == gatt) send(g, bytes, label) }, delayMs)
    }

    @Suppress("DEPRECATION")
    private fun send(g: BluetoothGatt, bytes: ByteArray, label: String) {
        val c = g.getService(WaterH.WRITE_SERVICE)?.getCharacteristic(WaterH.WRITE_CHAR)
        if (c == null) {
            finish("This doesn't look like a WaterH bottle (no command channel).")
            return
        }
        listener.onRaw("→ ${bytesToHex(bytes)}  ($label)")
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = bytes
            g.writeCharacteristic(c)
        }
        if (!ok) finish("Couldn't send a command to the bottle ($label)")
    }

    private fun startDiscovery(g: BluetoothGatt) {
        if (discoveryStarted) return
        discoveryStarted = true
        main.postDelayed({
            if (g == gatt && !g.discoverServices()) finish("Couldn't start service discovery")
        }, 300)
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(g: BluetoothGatt, attempt: Int) {
        val c = g.getService(WaterH.NOTIFY_SERVICE)?.getCharacteristic(WaterH.NOTIFY_CHAR)
        if (c == null) {
            finish("This doesn't look like a WaterH bottle (no reply channel).")
            return
        }
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(WaterH.CCCD) ?: c.descriptors.firstOrNull()
        if (d == null) {
            finish("Notify descriptor missing")
            return
        }
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val code = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value)
        } else {
            d.value = value
            if (g.writeDescriptor(d)) BluetoothGatt.GATT_SUCCESS else -1
        }
        if (code == BluetoothGatt.GATT_SUCCESS) return
        listener.onRaw("Enable notifications attempt $attempt failed (code $code)")
        if (attempt < 5) {
            main.postDelayed({ if (g == gatt) enableNotifications(g, attempt + 1) }, 400L * attempt)
        } else {
            finish("Couldn't enable notifications (code $code)")
        }
    }

    private fun requestLogs() {
        val g = gatt ?: return
        stage = Stage.LOGS
        listener.onProgress("Reading drinks stored on the bottle…")
        armTimeout(10_000, "The bottle didn't send its stored drinks.")
        sendLater(g, WaterH.REQUEST_WATER_LOGS, "request stored drinks", 1_000)
    }

    private fun logsComplete() {
        stage = Stage.UPLOADING
        val records = collector.records
        val total = collector.expected ?: records.size
        clearTimeout()
        // Safety net: if the caller never answers, end without clearing.
        armTimeout(60_000, "Upload took too long; drinks were kept on the bottle.")
        listener.onLogs(records, collector.bleData, total, true)
        if (readOnly) finish(null)
    }

    private fun handle(g: BluetoothGatt, value: ByteArray) {
        val packet = parsePacket(value)
        listener.onRaw("← ${bytesToHex(value)}  (${packet::class.simpleName})")
        when (packet) {
            is Packet.BoostStatus -> {
                listener.onStatus(packet)
                if (stage != Stage.STATUS) return
                val goal = goalProvider?.invoke()?.takeIf { it in GOAL_RANGE_ML } ?: packet.goalMl
                val reminder = packet.reminderHex
                if (!readOnly && goal != null && reminder != null) {
                    stage = Stage.SETTINGS
                    listener.onProgress("Setting the bottle's clock…")
                    armTimeout(6_000, null)
                    // Reminder settings are resent unchanged; the goal follows the account.
                    val label = if (goal != packet.goalMl) "set clock, goal ${packet.goalMl} → $goal mL" else "set clock"
                    sendLater(g, WaterH.syncSettings(goal, LocalDateTime.now(), reminder), label, 1_000)
                } else {
                    requestLogs()
                }
            }
            is Packet.VitaStatus -> {
                listener.onProgress("Vita bottles aren't supported yet")
                if (stage == Stage.STATUS) requestLogs()
            }
            is Packet.SettingsAck -> if (stage == Stage.SETTINGS) requestLogs()
            is Packet.LogsFound -> listener.onProgress("Receiving stored drinks…")
            is Packet.NoLogs -> if (stage == Stage.LOGS) {
                collector = LogCollector()
                stage = Stage.UPLOADING
                listener.onLogs(emptyList(), emptyList(), 0, true)
                done()
            }
            is Packet.LogStart, is Packet.LogContinued -> {
                if (stage != Stage.LOGS) return
                val complete = collector.add(packet)
                listener.onProgress("Received ${collector.records.size} of ${collector.expected ?: "?"} records…")
                if (complete) logsComplete()
                else armTimeout(5_000, "The bottle stopped sending partway through; nothing was cleared.")
            }
            is Packet.Live -> listener.onLive(packet)
            else -> Unit
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    listener.onProgress("Connected, setting up…")
                    if (!g.requestMtu(247)) startDiscovery(g)
                    main.postDelayed({ if (g == gatt) startDiscovery(g) }, 1_500)
                } else if (stage == Stage.CLEARING) {
                    done() // The bottle may drop the link after clearing.
                } else if (stage != Stage.IDLE && stage != Stage.DONE) {
                    finish("The bottle disconnected (status $status). Try again.")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                if (g != gatt) return@post
                listener.onRaw("MTU $mtu (status $status)")
                startDiscovery(g)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g != gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish("Service discovery failed ($status)")
                    return@post
                }
                if (notifySetupStarted) return@post
                notifySetupStarted = true
                listener.onRaw("Services discovered")
                enableNotifications(g, attempt = 1)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (g != gatt || stage != Stage.CONNECTING) return@post
                listener.onRaw("Notifications enabled (status $status)")
                stage = Stage.STATUS
                listener.onProgress("Checking the bottle…")
                armTimeout(12_000, "The bottle didn't answer. Tilt it to wake it and try again.")
                sendLater(g, WaterH.REQUEST_STATUS, "request status", 1_000)
                main.postDelayed({
                    if (g == gatt && stage == Stage.STATUS) send(g, WaterH.REQUEST_STATUS, "request status, retry")
                }, 6_000)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post { if (g == gatt) listener.onRaw("Write acknowledged (status $status)") }
        }

        // Android 13+.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid != WaterH.NOTIFY_CHAR) return
            val copy = value.copyOf()
            main.post { if (g == gatt) handle(g, copy) }
        }

        // Android 12.
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            if (c.uuid != WaterH.NOTIFY_CHAR) return
            val copy = c.value?.copyOf() ?: return
            main.post { if (g == gatt) handle(g, copy) }
        }
    }
}
