package com.byeonggon.latent.location

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import com.byeonggon.latent.data.ExifSummary
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.fuzz
import com.byeonggon.latent.ui.viewer.PhotoDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneOffset

/**
 * Photos whose Exif a camera or another app wrote damaged: their time, place, details and what Find searches them by
 * are read or left out, never thrown, and a place read is one on the Earth.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoExifFuzzTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun photo(edit: ExifInterface.() -> Unit = {}): File = folder.newFile().apply {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF607080.toInt()) }
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        ExifInterface(this).apply {
            setAttribute(ExifInterface.TAG_MAKE, "SONY")
            setAttribute(ExifInterface.TAG_MODEL, "ZV-E10")
            setAttribute(ExifInterface.TAG_LENS_MODEL, "E 16-50mm")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:12:08 10:42:43")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+09:00")
            setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "35/1")
            setAttribute(ExifInterface.TAG_F_NUMBER, "28/10")
            setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "1/250")
            setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "400")
            setLatLong(37.5665, 126.978)
            setAltitude(38.5)
            edit()
            saveAttributes()
        }
    }

    private fun check(file: File) {
        val metadata = MediaMetadataReader.read(file, MediaKind.PHOTO, ZoneOffset.UTC)
        metadata.location?.let { place ->
            assertTrue("$place", place.latitude in -90.0..90.0 && place.longitude in -180.0..180.0)
            assertTrue("$place", place.altitude?.isFinite() ?: true)
        }
        ExifSummary.read(file)?.focalLength?.let { assertTrue("$it", it.isFinite()) }
        PhotoDetails.all(file)
        PhotoDetails.of(file)
    }

    @Test
    fun theSeedIsReadWhole() {
        val metadata = MediaMetadataReader.read(photo(), MediaKind.PHOTO, ZoneOffset.UTC)

        assertEquals(37.5665, metadata.location!!.latitude, 1e-4)
        assertEquals(1_733_622_163_000L, metadata.capturedAt)
    }

    @Test
    fun aDamagedExifIsReadOrLeftOut() {
        val bytes = photo().readBytes()
        val file = folder.newFile()
        fuzz(seed = 81, cases = 2_000) { mutator ->
            file.writeBytes(mutator.bytes(bytes))
            check(file)
        }
    }

    @Test
    fun valuesOfAnyKindAreReadOrLeftOut() = fuzz(seed = 82, cases = 300) { mutator ->
        val random = mutator.random
        val values = listOf("", "0/0", "1/0", "-1/0", "200/1,0/1,0/1", "999999999/1", "abc", "NaN", "1e999", "4294967295/1", "-5/1", "0", "9999:99:99 99:99:99", "+99:99", "2024:12:08 10:42:43")
        val tags = listOf(
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        )
        check(photo { repeat(1 + random.nextInt(4)) { setAttribute(tags[random.nextInt(tags.size)], values[random.nextInt(values.size)]) } })
    }

    @Test
    fun aPlaceOffTheEarthIsNoPlaceButLeavesTheBlockMarkedDamaged() {
        // 200 degrees north, or 1262 east, is nowhere: the photo has no place then, but its GPS block is still its own.
        for (damaged in listOf(
            photo { setAttribute(ExifInterface.TAG_GPS_LATITUDE, "200/1,0/1,0/1") },
            photo { setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "1262/1,57/1,48/1") },
        )) {
            val metadata = MediaMetadataReader.read(damaged, MediaKind.PHOTO)
            assertNull(metadata.location)
            assertTrue(metadata.locationDamaged)
        }
    }

    @Test
    fun aPhotoWithoutAGpsBlockIsNotDamagedAndOneWithAGoodBlockHasItsPlace() {
        val bare = MediaMetadataReader.read(
            photo {
                for (tag in listOf(ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF)) setAttribute(tag, null)
            },
            MediaKind.PHOTO,
        )
        assertNull(bare.location)
        assertFalse(bare.locationDamaged)

        val good = MediaMetadataReader.read(photo(), MediaKind.PHOTO)
        assertEquals(37.5665, good.location!!.latitude, 1e-4)
        assertFalse(good.locationDamaged)
    }
}
