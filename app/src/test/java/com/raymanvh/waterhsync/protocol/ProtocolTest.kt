package com.raymanvh.waterhsync.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    private fun record(
        y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int,
        ml: Int, tds: Int, temp: Int, flag: Int = 0,
    ) = byteArrayOf(
        y.toByte(), mo.toByte(), d.toByte(), h.toByte(), mi.toByte(), s.toByte(),
        (ml shr 8).toByte(), ml.toByte(),
        (tds shr 8).toByte(), tds.toByte(),
        temp.toByte(), 0, flag.toByte(),
    )

    @Test
    fun commandsMatchOfficialApp() {
        assertEquals("47540001FF", bytesToHex(WaterH.REQUEST_STATUS))
        assertEquals("4754000106", bytesToHex(WaterH.REQUEST_WATER_LOGS))
    }

    // Captured from a real Boost 946 mL bottle (firmware 1.1.5).
    private val realStatusCharging =
        "5250002702026307031A0B1117000203040A0F030510FB051000730011021701072601090001003C022A01"
    private val realStatusOffCharger =
        "5250002702026307031A0B1117020303040AC5030510FB051000730011021700072601090001003C022A01"

    @Test
    fun realBoostStatus() {
        val p = parsePacket(hexToBytes(realStatusCharging)) as Packet.BoostStatus
        assertEquals(99, p.batteryPercent)
        assertTrue(p.charging)
        assertEquals("1.1.5", p.firmware)
        assertEquals("2026-11-17 23:00:02", p.clock.toString())
        assertEquals(2575, p.todayMl)
        assertEquals(4347, p.goalMl)
        assertEquals("01090001003C", p.reminderHex)

        val q = parsePacket(hexToBytes(realStatusOffCharger)) as Packet.BoostStatus
        assertFalse(q.charging)
        assertEquals(2757, q.todayMl) // +182 mL = the three drinks read afterwards
    }

    @Test
    fun syncSettingsMatchesOfficialFormat() {
        val now = java.time.LocalDateTime.of(2026, 11, 17, 23, 5, 9)
        val cmd = bytesToHex(WaterH.syncSettings(4347, now, "01090001003C"))
        assertEquals("505400140305" + "10FB" + "0703" + "1A0B1117" + "0509" + "0726" + "01090001003C", cmd)
        // 20-byte payload after the 4-byte header, as announced by 0x0014.
        assertEquals(24, WaterH.syncSettings(4347, now, "01090001003C").size)
    }

    @Test
    fun setTodayTotalCommand() {
        // 74.6 oz ≈ 2206 mL = 0x089E
        assertEquals("505400040304089E", bytesToHex(WaterH.setTodayTotal(2206)))
        assertEquals("5054000403040000", bytesToHex(WaterH.setTodayTotal(0)))
    }

    @Test
    fun clearCommand() {
        assertEquals("5250000403060027", bytesToHex(WaterH.clearRecords(3)))
    }

    @Test
    fun realLogReply() {
        val p = parsePacket(hexToBytes(
            "5054002928061A0B1117011D007E00000163001A0B11170126001D00000163001A0B11170133001B0000016300",
        )) as Packet.LogStart
        assertEquals(3, p.totalRecords)
        assertEquals(listOf(126, 29, 27), p.records.map { it.amountMl })
        assertEquals("2026-11-17 23:01:29", p.records[0].time.toString())
    }

    @Test
    fun shortStatusIsNotACrash() {
        val v = byteArrayOf(0x52, 0x50, 0, 39, 0, 0, 50)
        assertTrue(parsePacket(v) is Packet.Other)
    }

    @Test
    fun noLogsAndLogsFound() {
        assertEquals(Packet.NoLogs, parsePacket(byteArrayOf(0x52, 0x50, 0, 3, 2, 6, 0)))
        assertEquals(Packet.LogsFound, parsePacket(byteArrayOf(0x52, 0x50, 0, 3, 2, 6, 1)))
    }

    @Test
    fun multiPacketLog() {
        val r1 = record(26, 11, 17, 20, 1, 9, ml = 237, tds = 12, temp = 21)
        val r2 = record(26, 11, 17, 20, 5, 58, ml = 59, tds = 13, temp = 20)
        val flagged = record(26, 11, 17, 20, 6, 0, ml = 0, tds = 0, temp = 0, flag = 1)
        val first = byteArrayOf(0x50, 0x54, 0x00, 39, 13, 6) + r1 // 39 bytes = 3 records
        val second = byteArrayOf(13, 6) + r2
        val third = byteArrayOf(13, 6) + flagged

        val c = LogCollector()
        assertFalse(c.add(parsePacket(first)))
        assertEquals(3, c.expected)
        assertFalse(c.add(parsePacket(second)))
        assertTrue(c.add(parsePacket(third)))

        assertEquals(2, c.drinks.size)
        val d1 = c.drinks[0]
        assertEquals(237, d1.amountMl)
        assertEquals(12, d1.tds)
        assertEquals(21, d1.temperatureRaw)
        assertEquals("2026-11-17 20:01:09", d1.time.toString())
        assertEquals("1A0B11140109" + "00ED" + "000C" + "15" + "00", d1.rawHex)
        assertEquals(59, c.drinks[1].amountMl)
    }

    @Test
    fun severalRecordsInOneLargePacket() {
        val r1 = record(26, 11, 17, 8, 0, 0, ml = 300, tds = 5, temp = 18)
        val r2 = record(26, 11, 17, 9, 0, 0, ml = 400, tds = 5, temp = 18)
        val p = parsePacket(byteArrayOf(0x50, 0x54, 0x00, 26, 26, 6) + r1 + r2 + byteArrayOf(1, 2))
        p as Packet.LogStart
        assertEquals(2, p.totalRecords)
        assertEquals(listOf(300, 400), p.records.map { it.amountMl })
    }

    @Test
    fun liveBatteryReport() {
        val p = parsePacket(byteArrayOf(0x52, 0x54, 0, 3, 2, 2, 64))
        assertEquals(Packet.Live(LiveKind.BATTERY, 64), p)
    }

    @Test
    fun invalidClockGivesNullDate() {
        assertEquals(null, BottleTime(0, 0, 0, 0, 0, 0).toLocalDateTimeOrNull())
    }
}
