package com.byeonggon.cameramanager.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MediaTimeTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    @Test
    fun exifTimeUsesTheRecordedOffsetWhenPresent() {
        val expected = Instant.parse("2026-10-13T05:30:00Z").toEpochMilli()

        assertEquals(expected, MediaTime.parseExif("2026:10:13 07:30:00", "+02:00", seoul))
    }

    @Test
    fun exifTimeFallsBackToTheGivenZone() {
        val expected = Instant.parse("2026-10-13T05:30:00Z").toEpochMilli()

        assertEquals(expected, MediaTime.parseExif("2026:10:13 14:30:00", null, seoul))
        assertEquals(expected, MediaTime.parseExif("2026:10:13 14:30:00", "garbage", seoul))
    }

    @Test
    fun exifTimeRejectsUnsetClocks() {
        assertNull(MediaTime.parseExif("0000:00:00 00:00:00", null, seoul))
        assertNull(MediaTime.parseExif(null, null, seoul))
    }

    @Test
    fun videoTimeIsReadAsUtc() {
        val expected = Instant.parse("2026-10-13T05:30:00Z").toEpochMilli()

        assertEquals(expected, MediaTime.parseVideo("20261013T053000.000Z"))
        assertEquals(expected, MediaTime.parseVideo("20261013T053000Z"))
    }

    @Test
    fun videoTimeRejectsTheContainerEpoch() {
        assertNull(MediaTime.parseVideo("19040101T000000.000Z"))
    }
}
