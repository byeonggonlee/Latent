package com.byeonggon.latent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimeZoneChoicesTest {
    private val winter = Instant.parse("2026-01-15T00:00:00Z")
    private val choices = TimeZoneChoices.all(Locale.ENGLISH, winter)

    @Test
    fun listsEveryRegionOnceWestToEastWithoutTheOldAliases() {
        assertTrue(choices.size > 300)
        assertEquals(choices.size, choices.map { it.id }.toSet().size)
        assertEquals(choices.sortedBy { it.offset.totalSeconds }.map { it.offset }, choices.map { it.offset })
        assertTrue(choices.none { it.id.startsWith("Etc/") || it.id.startsWith("US/") })
        assertTrue(choices.any { it.id == "UTC" })
    }

    @Test
    fun givesTheOffsetOfTheMomentSoThatSummerTimeIsRight() {
        val summer = TimeZoneChoices.all(Locale.ENGLISH, Instant.parse("2026-07-15T00:00:00Z"))
        assertEquals("UTC+09:00", choices.first { it.id == "Asia/Seoul" }.offsetLabel)
        assertEquals("UTC+01:00", choices.first { it.id == "Europe/Paris" }.offsetLabel)
        assertEquals("UTC+02:00", summer.first { it.id == "Europe/Paris" }.offsetLabel)
        assertEquals("UTC-03:30", choices.first { it.id == "America/St_Johns" }.offsetLabel)
        assertEquals("UTC", choices.first { it.id == "UTC" }.offsetLabel)
    }

    @Test
    fun findsAZoneByCityIdOrOffsetWhateverTheCase() {
        assertEquals(listOf("Asia/Seoul"), TimeZoneChoices.search(choices, "seoul", Locale.ENGLISH).map { it.id })
        assertTrue(TimeZoneChoices.search(choices, "asia tokyo", Locale.ENGLISH).any { it.id == "Asia/Tokyo" })
        assertTrue(TimeZoneChoices.search(choices, "utc+09:00", Locale.ENGLISH).any { it.id == "Asia/Seoul" })
        assertTrue(TimeZoneChoices.search(choices, "nowhere at all", Locale.ENGLISH).isEmpty())
        assertEquals(choices, TimeZoneChoices.search(choices, "  ", Locale.ENGLISH))
    }

    @Test
    fun namesTheChosenZoneForReading() {
        assertEquals("Seoul \u00b7 UTC+09:00", TimeZoneChoices.describe("Asia/Seoul", choices))
        assertEquals("Not/AZone", TimeZoneChoices.describe("Not/AZone", choices))
        assertEquals(null, TimeZoneChoices.describe(null, choices))
        assertFalse(choices.isEmpty())
    }

    @Test
    fun namesCitiesInThePhonesLanguage() {
        val korean = TimeZoneChoices.all(Locale.KOREAN, winter)
        assertEquals("서울", korean.first { it.id == "Asia/Seoul" }.city)
        assertEquals(listOf("Asia/Seoul"), TimeZoneChoices.search(korean, "서울", Locale.KOREAN).map { it.id })
    }
}
