package com.byeonggon.latent.backup

import com.byeonggon.latent.data.db.GeoSource
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Which photo on this phone a photo of a backup is, and what a restore leaves of each. */
class MediaMatcherTest {
    private val library = File("/storage/emulated/10/DCIM/Latent")
    private val paths = MediaPaths(library) { it.replace(Regex("^/storage/emulated/\\d+/"), "") }
    private var nextId = 1L
    private val hour = 3_600_000L

    private fun onPhone(path: String, size: Long = 100, capturedAt: Long = 1_000 * hour, origin: MediaOrigin = MediaOrigin.LIBRARY, kind: MediaKind = MediaKind.PHOTO) =
        MediaItemEntity(id = nextId++, path = path, fileName = path.substringAfterLast('/'), kind = kind, sizeBytes = size, capturedAt = capturedAt, importedAt = 1, origin = origin)

    private fun record(path: String?, name: String, size: Long = 100, capturedAt: Long = 1_000 * hour, origin: MediaOrigin = MediaOrigin.LIBRARY, kind: MediaKind = MediaKind.PHOTO) =
        MediaRecord(id = nextId++, origin = origin, path = path, name = name, kind = kind, size = size, capturedAt = capturedAt, importedAt = 1)

    @Test
    fun placesAFileByItsRootWhereverThatIs() {
        assertEquals("2026/2026-10-08/DSC00001.JPG", paths.of(onPhone("$library/2026/2026-10-08/DSC00001.JPG")))
        assertEquals("DCIM/Camera/a.jpg", paths.of(onPhone("/storage/emulated/10/DCIM/Camera/a.jpg", origin = MediaOrigin.PHONE)))
        // A library file outside the library's folder has no place to be found by.
        assertNull(paths.of(onPhone("/somewhere/else/DSC00001.JPG")))
        assertEquals("DCIM/Camera/", paths.phoneFolderOf("DCIM/Camera/a.jpg"))
        assertEquals("DCIM/CameraManager/", paths.phoneFolderOf("DCIM/CameraManager/2026/2026-10-08/a.jpg"))
    }

    @Test
    fun findsAPhotoAtItsPlaceThoughItsSizeChangedWithAnEdit() {
        val there = onPhone("$library/2026/2026-10-08/DSC00001.JPG", size = 2_345)
        val matcher = MediaMatcher(listOf(there), paths)

        assertEquals(there, matcher.atPath(record("2026/2026-10-08/DSC00001.JPG", "DSC00001.JPG", size = 1_234)))
    }

    @Test
    fun findsAMovedPhotoByItsNameAndSize() {
        val moved = onPhone("$library/2026/2026-10-09/dsc00001.jpg")
        val matcher = MediaMatcher(listOf(moved, onPhone("$library/2026/2026-10-09/DSC00002.JPG")), paths)
        val record = record("2026/2026-10-08/DSC00001.JPG", "DSC00001.JPG")

        assertNull(matcher.atPath(record))
        assertEquals(moved, matcher.elsewhere(record))
    }

    @Test
    fun takesNoLonePhotoOfTheSameNameAndSizeShotAtAnotherTime() {
        // The camera's numbers came round: last year's raw file is gone, this year's has its name and, uncompressed, its size.
        val thisYear = onPhone("$library/2026/2026-01-01/DSC00001.DNG", size = 24_000_000, capturedAt = 1_767_268_800_000L, kind = MediaKind.RAW)
        val matcher = MediaMatcher(listOf(thisYear), paths)
        val lastYear = record("2025/2025-01-01/DSC00001.DNG", "DSC00001.DNG", size = 24_000_000, capturedAt = 1_735_732_800_000L, kind = MediaKind.RAW)

        assertNull(matcher.atPath(lastYear))
        assertNull(matcher.elsewhere(lastYear))
        // Read in another time zone, the same moment is still the same photo.
        assertEquals(thisYear, matcher.elsewhere(record("2025/2025-12-31/DSC00001.DNG", "DSC00001.DNG", size = 24_000_000, capturedAt = 1_767_268_800_000L - 9 * hour, kind = MediaKind.RAW)))
    }

    @Test
    fun takesEachPhotoOnce() {
        val only = onPhone("$library/2026/2026-10-08/DSC00001.JPG")
        val matcher = MediaMatcher(listOf(only), paths)

        assertEquals(only, matcher.atPath(record("2026/2026-10-08/DSC00001.JPG", "DSC00001.JPG")))
        assertNull(matcher.elsewhere(record("2026/2026-10-07/DSC00001.JPG", "DSC00001.JPG")))
    }

    @Test
    fun tellsCopiesApartByWhenTheyWereTakenAsClocksInOtherZonesSeeIt() {
        // Camera file names come round again: the same name and size, months apart.
        val march = onPhone("$library/2026/2026-03-01/DSC00001.JPG", capturedAt = 500 * hour)
        val october = onPhone("$library/2026/2026-10-08/DSC00001.JPG", capturedAt = 1_000 * hour)
        val matcher = MediaMatcher(listOf(march, october), paths)

        // Read nine hours apart, in another time zone.
        assertEquals(october, matcher.elsewhere(record(null, "DSC00001.JPG", capturedAt = 1_009 * hour)))
        assertEquals(march, matcher.elsewhere(record(null, "DSC00001.JPG", capturedAt = 500 * hour)))
    }

    @Test
    fun leavesAPhotoUnfoundRatherThanGuessAmongCopies() {
        val one = onPhone("$library/a/DSC00001.JPG", capturedAt = 500 * hour)
        val other = onPhone("$library/b/DSC00001.JPG", capturedAt = 600 * hour)
        val matcher = MediaMatcher(listOf(one, other), paths)

        assertNull(matcher.elsewhere(record(null, "DSC00001.JPG", capturedAt = 700 * hour + 1)))
    }

    @Test
    fun neverTakesAPhotoOfTheOtherOrigin() {
        // A library photo copied into the phone's own camera folder is another app's there: its edit would be applied twice.
        val copy = onPhone("/storage/emulated/10/DCIM/Camera/DSC00001.JPG", origin = MediaOrigin.PHONE)
        val matcher = MediaMatcher(listOf(copy), paths)

        assertNull(matcher.elsewhere(record("2026/2026-10-08/DSC00001.JPG", "DSC00001.JPG")))
        assertNull(matcher.atPath(record("DCIM/Camera/DSC00001.JPG", "DSC00001.JPG")))
    }

    private val edit = """{"exposure":0.5}"""

    @Test
    fun givesASettingsFileTheBackupsEditAndTakesAnEditOfItsOwnAway() {
        val raw = onPhone("$library/x/DSC00001.ARW", kind = MediaKind.RAW).copy(edit = """{"contrast":0.2}""", rating = 2)

        val withEdit = restoredOnto(raw, record("x/DSC00001.ARW", "DSC00001.ARW", kind = MediaKind.RAW).copy(edit = edit, rating = 4), hasOriginal = false)
        assertEquals(edit, withEdit.edit)
        assertEquals(4, withEdit.rating)
        assertTrue(replacesOwn(raw, withEdit))

        val without = restoredOnto(raw, record("x/DSC00001.ARW", "DSC00001.ARW", kind = MediaKind.RAW).copy(flag = 1), hasOriginal = false)
        assertNull(without.edit)
        assertEquals(0, without.rating)
    }

    @Test
    fun anEditSavedAgainWithItsRenderVersionIsStillTheBackupsEdit() {
        // Saved by 0.1.0 into the backup, saved again unchanged by 0.2.0 on the phone.
        val raw = onPhone("$library/x/DSC00001.ARW", kind = MediaKind.RAW).copy(edit = """{"exposure":0.5,"renderVersion":1}""")
        val record = record("x/DSC00001.ARW", "DSC00001.ARW", kind = MediaKind.RAW).copy(edit = edit)

        assertFalse(replacesOwn(raw, restoredOnto(raw, record, hasOriginal = false)))
        assertTrue(replacesOwn(raw, restoredOnto(raw, record.copy(edit = """{"exposure":0.6}"""), hasOriginal = false)))
        // Only the missing number counts as the current one: another number is another rendering.
        assertTrue(replacesOwn(raw.copy(edit = """{"exposure":0.5,"renderVersion":7}"""), restoredOnto(raw, record, hasOriginal = false)))
    }

    @Test
    fun aRestoreThatDropsWhatALaterAppWroteTakesTheEditAway() {
        // What a restore writes is the backup's text, read back from the file as it is; a key this app does not
        // know is lost with it, so it counts.
        val raw = onPhone("$library/x/DSC00001.ARW", kind = MediaKind.RAW)
            .copy(edit = """{"exposure":0.5,"renderVersion":7,"futureSetting":0.8}""")
        val written = record("x/DSC00001.ARW", "DSC00001.ARW", kind = MediaKind.RAW).copy(edit = """{"exposure":0.5,"renderVersion":7}""")
        val record = MediaRecord.fromJson(written.toJson())!!

        assertTrue(replacesOwn(raw, restoredOnto(raw, record, hasOriginal = false)))
        assertTrue(replacesOwn(raw, restoredOnto(raw, record.copy(edit = """{"exposure":0.5,"renderVersion":7,"futureSetting":0.9}"""), hasOriginal = false)))
        // Written apart, the same: other key order, other number spelling.
        assertFalse(replacesOwn(raw, restoredOnto(raw, record.copy(edit = """{"futureSetting":0.80,"renderVersion":7,"exposure":0.5}"""), hasOriginal = false)))
    }

    @Test
    fun onlyTellsAJpegOfTheLibraryHowItsEditWasMadeWhereThePhoneDoesNotKnow() {
        val adopted = onPhone("$library/x/DSC00001.JPG").copy(edit = UNKNOWN_EDIT)
        val record = record("x/DSC00001.JPG", "DSC00001.JPG").copy(edit = edit)

        assertEquals(edit, restoredOnto(adopted, record, hasOriginal = true).edit)
        assertFalse(replacesOwn(adopted, restoredOnto(adopted, record, hasOriginal = true)))
        // Without its original the file is as it is, edited or not.
        assertEquals(UNKNOWN_EDIT, restoredOnto(adopted, record, hasOriginal = false).edit)
        // Edited again on this phone: the file shows that edit, which stays.
        val editedHere = adopted.copy(edit = """{"contrast":0.2}""")
        assertEquals("""{"contrast":0.2}""", restoredOnto(editedHere, record, hasOriginal = true).edit)
    }

    @Test
    fun keepsThePlaceThePhoneReadWhereTheBackupHasNone() {
        val located = onPhone("$library/x/DSC00001.JPG").copy(latitude = 37.5, longitude = 127.0, geoSource = GeoSource.CAMERA)

        val restored = restoredOnto(located, record("x/DSC00001.JPG", "DSC00001.JPG").copy(rating = 1), hasOriginal = false)
        assertEquals(37.5, restored.latitude!!, 0.0)
        assertEquals(GeoSource.CAMERA, restored.geoSource)

        // The same place read back from the file, rounded: not counted as replaced.
        val tracked = record("x/DSC00001.JPG", "DSC00001.JPG").copy(latitude = 37.500003, longitude = 127.000002, geoSource = GeoSource.TRACK)
        assertFalse(replacesOwn(located, restoredOnto(located, tracked, hasOriginal = false)))
        val elsewhere = tracked.copy(latitude = 35.1, longitude = 129.0)
        assertTrue(replacesOwn(located, restoredOnto(located, elsewhere, hasOriginal = false)))
    }
}
