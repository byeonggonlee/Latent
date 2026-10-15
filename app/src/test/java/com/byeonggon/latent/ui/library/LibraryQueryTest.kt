package com.byeonggon.latent.ui.library

import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class LibraryQueryTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = Instant.parse("2026-10-15T03:00:00Z")

    private fun shot(
        name: String = "DSC00001.JPG",
        camera: String? = "ZV-E10",
        lens: String? = "E PZ 18-105mm F4 G OSS",
        iso: Int? = 100,
        focal: Double? = 35.0,
        at: String = "2026-10-14T03:00:00Z",
        located: Boolean = true,
    ) = MediaItemEntity(
        path = "/p/$name", fileName = name, kind = MediaKind.PHOTO, sizeBytes = 1, capturedAt = Instant.parse(at).toEpochMilli(), importedAt = 1,
        latitude = if (located) 37.5 else null, longitude = if (located) 127.0 else null,
        camera = camera, lens = lens, iso = iso, focalLength = focal, exifRead = true,
    )

    private fun LibraryQuery.finds(item: MediaItemEntity, edited: Boolean = false) = accepts(item, now, zone) { edited }

    @Test
    fun findsEverythingWhenNothingIsAsked() {
        assertFalse(LibraryQuery().isActive)
        assertEquals(0, LibraryQuery().count)
        assertTrue(LibraryQuery().finds(shot(camera = null, iso = null, focal = null)))
    }

    @Test
    fun findsByWordsOfTheFileNameInAnyCase() {
        assertTrue(LibraryQuery(text = "dsc 0001").finds(shot()))
        assertTrue(LibraryQuery(text = "  jpg ").finds(shot()))
        assertFalse(LibraryQuery(text = "dsc9").finds(shot()))
    }

    @Test
    fun findsByAnyOfTheCamerasOrLensesChosen() {
        assertTrue(LibraryQuery(cameras = setOf("ZV-E10", "SM-S928N")).finds(shot()))
        assertFalse(LibraryQuery(cameras = setOf("SM-S928N")).finds(shot()))
        // A photo that does not say is not found by a condition on it.
        assertFalse(LibraryQuery(cameras = setOf("ZV-E10")).finds(shot(camera = null)))
        assertTrue(LibraryQuery(lenses = setOf("E PZ 18-105mm F4 G OSS")).finds(shot()))
        assertFalse(LibraryQuery(lenses = setOf("other")).finds(shot(lens = null)))
    }

    @Test
    fun findsByTheRangesOfIsoAndFocalLength() {
        assertTrue(LibraryQuery(iso = setOf(IsoBand.LOW)).finds(shot(iso = 400)))
        assertFalse(LibraryQuery(iso = setOf(IsoBand.LOW)).finds(shot(iso = 401)))
        assertTrue(LibraryQuery(iso = setOf(IsoBand.MID, IsoBand.HIGH)).finds(shot(iso = 6400)))
        assertTrue(LibraryQuery(iso = setOf(IsoBand.VERY_HIGH)).finds(shot(iso = 12800)))
        assertFalse(LibraryQuery(iso = setOf(IsoBand.LOW)).finds(shot(iso = null)))

        assertTrue(LibraryQuery(focal = setOf(FocalBand.WIDE)).finds(shot(focal = 23.9)))
        assertTrue(LibraryQuery(focal = setOf(FocalBand.STANDARD)).finds(shot(focal = 24.0)))
        assertTrue(LibraryQuery(focal = setOf(FocalBand.STANDARD)).finds(shot(focal = 70.0)))
        assertTrue(LibraryQuery(focal = setOf(FocalBand.TELE)).finds(shot(focal = 70.1)))
        assertFalse(LibraryQuery(focal = setOf(FocalBand.TELE)).finds(shot(focal = 35.0)))
        assertFalse(LibraryQuery(focal = setOf(FocalBand.TELE)).finds(shot(focal = null)))
    }

    @Test
    fun findsByWhenInTheLocalCalendar() {
        assertTrue(LibraryQuery(period = Period.WEEK).finds(shot(at = "2026-10-09T03:00:00Z")))
        assertFalse(LibraryQuery(period = Period.WEEK).finds(shot(at = "2026-10-01T03:00:00Z")))
        assertTrue(LibraryQuery(period = Period.MONTH).finds(shot(at = "2026-09-20T03:00:00Z")))
        // Not yet taken, by a camera clock set wrong, is not "the last week".
        assertFalse(LibraryQuery(period = Period.WEEK).finds(shot(at = "2026-11-01T03:00:00Z")))
        // New Year's Eve in the evening in UTC is already the next year in Seoul.
        assertTrue(LibraryQuery(year = 2026).finds(shot(at = "2025-12-31T16:00:00Z")))
        assertFalse(LibraryQuery(year = 2025).finds(shot(at = "2025-12-31T16:00:00Z")))
    }

    @Test
    fun findsByPlaceAndEditsAndOnlyAsksAboutEditsWhenItMatters() {
        assertTrue(LibraryQuery(located = Ask.YES).finds(shot()))
        assertFalse(LibraryQuery(located = Ask.YES).finds(shot(located = false)))
        assertTrue(LibraryQuery(located = Ask.NO).finds(shot(located = false)))

        assertTrue(LibraryQuery(edited = Ask.YES).finds(shot(), edited = true))
        assertFalse(LibraryQuery(edited = Ask.YES).finds(shot(), edited = false))
        assertTrue(LibraryQuery(edited = Ask.NO).finds(shot(), edited = false))
        var asked = 0
        LibraryQuery(located = Ask.YES).accepts(shot(), now, zone) { asked++; false }
        assertEquals(0, asked)
    }

    @Test
    fun needsAllTheConditionsAndCountsThem() {
        val query = LibraryQuery(text = "dsc", cameras = setOf("ZV-E10"), iso = setOf(IsoBand.LOW), period = Period.MONTH)
        assertEquals(4, query.count)
        assertTrue(query.finds(shot()))
        assertFalse(query.finds(shot(iso = 3200)))
        assertEquals(1, LibraryQuery(year = 2026).count)
    }
}
