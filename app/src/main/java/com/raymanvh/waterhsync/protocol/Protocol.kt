package com.raymanvh.waterhsync.protocol

import java.time.LocalDateTime
import java.util.UUID

/**
 * WaterH bottle Bluetooth protocol, as implemented by the official WaterH
 * Android app (v3.5.1). See docs/PROTOCOL.md for the full write-up.
 *
 * The bottle exposes a serial-style pair of characteristics: commands are
 * written to [WRITE_CHAR] and every reply arrives as a notification on
 * [NOTIFY_CHAR]. Multi-byte numbers are big-endian.
 */
object WaterH {
    val WRITE_SERVICE: UUID = UUID.fromString("0000ffe5-0000-1000-8000-00805f9b34fb")
    val WRITE_CHAR: UUID = UUID.fromString("0000ffe9-0000-1000-8000-00805f9b34fb")
    val NOTIFY_SERVICE: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHAR: UUID = UUID.fromString("0000ffe4-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** "GT" read requests. These only ask for data; they change nothing on the bottle. */
    val REQUEST_STATUS: ByteArray = hexToBytes("47540001ff")
    val REQUEST_WATER_LOGS: ByteArray = hexToBytes("4754000106")

    /** Size of one stored drink record in a log reply. */
    const val RECORD_SIZE = 13

    /**
     * Sets the bottle's clock, goal and reminder in one command (the official
     * app sends this on every connect, before asking for stored drinks).
     * Pass the bottle's own [goalMl] and [reminderHex] from its status reply
     * to change only the clock.
     */
    fun syncSettings(goalMl: Int, now: LocalDateTime, reminderHex: String): ByteArray {
        require(reminderHex.length == 12) { "reminder must be 6 bytes" }
        val clock = listOf(now.year % 100, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second)
            .joinToString("") { "%02x".format(it) }
        return hexToBytes("505400140305" + "%04x".format(goalMl and 0xffff) + "0703" + clock + "0726" + reminderHex)
    }

    /**
     * Sets the bottle's own "today" total (status tag 4) to [ml]. The official
     * app sends this with the account's daily total so the bottle matches it.
     */
    fun setTodayTotal(ml: Int): ByteArray =
        hexToBytes("505400040304" + "%04x".format(ml.coerceIn(0, 0xffff)))

    /**
     * Deletes [totalRecords] records (the total announced in the log reply,
     * including flagged ones) from the bottle. The official app sends this only
     * after WaterH accepted the upload (HTTP 2xx or 409 duplicate).
     */
    fun clearRecords(totalRecords: Int): ByteArray =
        hexToBytes("525000040306" + "%04x".format((totalRecords * RECORD_SIZE) and 0xffff))
}

/** A date and time as kept by the bottle's own clock (set by the phone on sync). */
data class BottleTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
) {
    /** Null if the bottle sent an impossible date (e.g. clock never set). */
    fun toLocalDateTimeOrNull(): LocalDateTime? =
        runCatching { LocalDateTime.of(2000 + year, month, day, hour, minute, second) }.getOrNull()

    override fun toString(): String =
        "20%02d-%02d-%02d %02d:%02d:%02d".format(year, month, day, hour, minute, second)
}

/**
 * One 13-byte record from the bottle's stored log.
 *
 * Bytes 0-11 are what the official app uploads to WaterH as `ble_data`
 * (hex, uppercase). Byte 12 is a flag: 0 means a drink; anything else is a
 * record the app counts toward the total but does not upload as a drink.
 */
data class LogRecord(
    val time: BottleTime,
    val amountMl: Int,
    val tds: Int,
    /** The app multiplies this by 10 before uploading it as `temperature`. */
    val temperatureRaw: Int,
    val flag: Int,
    val rawHex: String,
) {
    val isDrink: Boolean get() = flag == 0
}

sealed class Packet {
    /**
     * Status reply from a Boost bottle (reply to [WaterH.REQUEST_STATUS]).
     * The reply is a list of length/tag/value fields; see [parseTlv].
     */
    data class BoostStatus(
        val batteryPercent: Int,
        val charging: Boolean,
        val firmware: String,
        val clock: BottleTime,
        /** The bottle's own running total for today, in mL (tag 4). */
        val todayMl: Int?,
        /** Daily goal stored on the bottle, in mL (tag 5). */
        val goalMl: Int?,
        /** Reminder settings stored on the bottle, 6 bytes as hex (tag 38). */
        val reminderHex: String?,
    ) : Packet()

    /** Status reply from a Vita bottle; its layout differs and isn't decoded yet. */
    data class VitaStatus(val hex: String) : Packet()

    /** First packet of a stored-log reply. [totalRecords] covers all packets. */
    data class LogStart(val totalRecords: Int, val records: List<LogRecord>) : Packet()

    /** A follow-on packet of a stored-log reply. */
    data class LogContinued(val records: List<LogRecord>) : Packet()

    /** The bottle has no stored log records. */
    data object NoLogs : Packet()

    /** The bottle has stored records and is about to send them. */
    data object LogsFound : Packet()

    /** Acknowledgement of the clock/goal/reminder sync command. */
    data class SettingsAck(val goalMl: Int) : Packet()

    /** Unsolicited live update ("RT" report). */
    data class Live(val kind: LiveKind, val value: Int) : Packet()

    /** Anything this app doesn't interpret (registration, reminders, OTA...). */
    data class Other(val description: String, val hex: String) : Packet()
}

enum class LiveKind { TEMPERATURE, BATTERY, CHARGING, VOLUME, TDS }

private fun ByteArray.u(i: Int): Int? = if (i in indices) this[i].toInt() and 0xff else null

private fun ByteArray.be16(i: Int): Int? {
    val hi = u(i) ?: return null
    val lo = u(i + 1) ?: return null
    return (hi shl 8) or lo
}

private fun ByteArray.time(i: Int): BottleTime? {
    if (i + 5 !in indices) return null
    return BottleTime(u(i)!!, u(i + 1)!!, u(i + 2)!!, u(i + 3)!!, u(i + 4)!!, u(i + 5)!!)
}

fun bytesToHex(bytes: ByteArray, upper: Boolean = true): String {
    val s = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return if (upper) s.uppercase() else s
}

fun hexToBytes(hex: String): ByteArray {
    val clean = hex.replace(Regex("\\s+"), "")
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/**
 * Decodes a run of fields laid out as [length][tag][value…], where length
 * counts the tag byte plus the value. Stops at the first malformed field.
 */
fun parseTlv(value: ByteArray, start: Int): Map<Int, ByteArray> {
    val out = linkedMapOf<Int, ByteArray>()
    var i = start
    while (i + 1 < value.size) {
        val len = value[i].toInt() and 0xff
        if (len < 1 || i + 1 + len > value.size) break
        val tag = value[i + 1].toInt() and 0xff
        out[tag] = value.copyOfRange(i + 2, i + 1 + len)
        i += 1 + len
    }
    return out
}

/** Decodes the 13-byte records starting at [start]. Partial trailing bytes are ignored. */
fun parseRecords(value: ByteArray, start: Int): List<LogRecord> {
    val records = mutableListOf<LogRecord>()
    var i = start
    while (i + WaterH.RECORD_SIZE <= value.size) {
        records += LogRecord(
            time = value.time(i)!!,
            amountMl = value.be16(i + 6)!!,
            tds = value.be16(i + 8)!!,
            temperatureRaw = value.u(i + 10)!!,
            flag = value.u(i + 12)!!,
            rawHex = bytesToHex(value.copyOfRange(i, i + 12)),
        )
        i += WaterH.RECORD_SIZE
    }
    return records
}

/** Decodes one notification, following the same branch order as the official app. */
fun parsePacket(value: ByteArray): Packet {
    val hex = bytesToHex(value)
    val b0 = value.u(0) ?: return Packet.Other("empty", hex)
    val b1 = value.u(1)

    // "RP" (0x52 0x50): responses to commands.
    if (b0 == 0x52 && b1 == 0x50) {
        if (value.u(5) == 0x20) return Packet.Other("signature response", hex)
        if (value.u(2) == 0 && value.u(3) == 49) return Packet.VitaStatus(hex)
        if (value.u(2) == 0 && value.u(3) == 39) {
            val f = parseTlv(value, 4)
            val battery = f[2]?.firstOrNull()?.toInt()?.and(0xff)
            val clock = f[3]?.takeIf { it.size >= 6 }?.time(0)
            val version = f[16]?.be16(0)
            if (battery == null || clock == null || version == null) {
                return Packet.Other("short status", hex)
            }
            val charging = f[23]?.firstOrNull()?.toInt()?.and(0xff)
            return Packet.BoostStatus(
                batteryPercent = battery,
                charging = charging == 1 || charging == 2,
                firmware = "${version / 100}.${(version % 100) / 10}.${version % 10}",
                clock = clock,
                todayMl = f[4]?.be16(0),
                goalMl = f[5]?.be16(0),
                reminderHex = f[38]?.takeIf { it.size == 6 }?.let { bytesToHex(it) },
            )
        }
        if (value.u(2) == 0 && value.u(3) == 15) {
            return Packet.SettingsAck(value.be16(6) ?: 0)
        }
        when (value.u(5)) {
            38 -> return Packet.Other("reminder response", hex)
            5 -> return Packet.Other("goal response", hex)
            6 -> return when (value.u(6)) {
                0 -> Packet.NoLogs
                1 -> Packet.LogsFound
                else -> Packet.Other("water log response", hex)
            }
            28 -> return Packet.Other("registration response", hex)
        }
        return Packet.Other("response", hex)
    }

    // "RT" (0x52 0x54): live reports.
    if (b0 == 0x52 && b1 == 0x54) {
        val v = value.u(6) ?: return Packet.Other("short report", hex)
        return when (value.u(5)) {
            1 -> Packet.Live(LiveKind.TEMPERATURE, value[6].toInt())
            2 -> Packet.Live(LiveKind.BATTERY, v)
            23 -> Packet.Live(LiveKind.CHARGING, if (v == 1 || v == 2) 1 else 0)
            8 -> Packet.Live(LiveKind.VOLUME, value.be16(6) ?: v)
            40 -> Packet.Live(LiveKind.TDS, value.be16(6) ?: v)
            0xa1 -> Packet.Other("recalibrate report", hex)
            28 -> Packet.Other("registration report", hex)
            else -> Packet.Other("report", hex)
        }
    }

    // "PT" (0x50 0x54) with command 6: first packet of the stored log.
    if (b0 == 0x50 && b1 == 0x54) {
        if (value.u(5) == 6) {
            val total = (value.be16(2) ?: 0) / WaterH.RECORD_SIZE
            return Packet.LogStart(total, parseRecords(value, 6))
        }
        return Packet.Other("PT packet", hex)
    }

    // Follow-on log packet: byte 0 is this packet's payload length, byte 1 is 6.
    if (b1 == 6) return Packet.LogContinued(parseRecords(value, 2))

    return Packet.Other("unknown", hex)
}

/**
 * Collects a multi-packet stored-log reply. The reply is complete once the
 * number of records received (drinks plus flagged records) reaches the total
 * announced in the first packet, mirroring the official app.
 */
class LogCollector {
    var expected: Int? = null
        private set
    private val received = mutableListOf<LogRecord>()

    val records: List<LogRecord> get() = received.toList()
    val drinks: List<LogRecord> get() = received.filter { it.isDrink }

    /** Raw record hex as the official app uploads it in `ble_data` (all records, in order). */
    val bleData: List<String> get() = received.map { it.rawHex }

    /** Returns true when the reply is complete. */
    fun add(packet: Packet): Boolean {
        when (packet) {
            is Packet.LogStart -> {
                expected = packet.totalRecords
                received.clear()
                received += packet.records
            }
            is Packet.LogContinued -> {
                if (expected == null) return false
                received += packet.records
            }
            else -> return false
        }
        return expected?.let { received.size >= it } ?: false
    }
}
