package com.byeonggon.latent.location

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import com.byeonggon.latent.data.db.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaMetadataReaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val paris = ZoneId.of("Europe/Paris")
    private val seoulNoon = OffsetDateTime.parse("2026-10-09T12:00:00+09:00").toInstant().toEpochMilli()
    private val parisNoon = OffsetDateTime.parse("2026-10-09T12:00:00+02:00").toInstant().toEpochMilli()

    private fun photo(vararg tags: Pair<String, String>): File {
        val file = folder.newFile("DSC${folder.root.list()!!.size}.JPG")
        file.outputStream().use { Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file).apply {
            tags.forEach { (tag, value) -> setAttribute(tag, value) }
            saveAttributes()
        }
        return file
    }

    private fun timeOf(file: File) = MediaMetadataReader.read(file, MediaKind.PHOTO, paris).capturedAt

    @Test
    fun takesTheZoneFromTheOriginalTimeFirst() {
        val file = photo(
            ExifInterface.TAG_DATETIME_ORIGINAL to "2026:10:09 12:00:00",
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL to "+09:00",
            ExifInterface.TAG_DATETIME_DIGITIZED to "2026:10:09 12:00:00",
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED to "+01:00",
        )

        assertEquals(seoulNoon, timeOf(file))
    }

    @Test
    fun takesTheZoneFromTheDigitizedTimeWhenThatIsTheOnlyOneAndTheSameMoment() {
        val file = photo(
            ExifInterface.TAG_DATETIME_ORIGINAL to "2026:10:09 12:00:00",
            ExifInterface.TAG_DATETIME_DIGITIZED to "2026:10:09 12:00:00",
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED to "+09:00",
        )

        assertEquals(seoulNoon, timeOf(file))
    }

    @Test
    fun doesNotTakeTheZoneOfADigitizedTimeThatIsAnotherMoment() {
        // Scanned or developed later: its zone says nothing of where the picture was shot.
        val file = photo(
            ExifInterface.TAG_DATETIME_ORIGINAL to "2026:10:09 12:00:00",
            ExifInterface.TAG_DATETIME_DIGITIZED to "2026:10:12 08:30:00",
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED to "+09:00",
        )

        assertEquals(parisNoon, timeOf(file))
    }

    @Test
    fun readsInTheCamerasZoneWhenNoZoneIsRecorded() {
        assertEquals(parisNoon, timeOf(photo(ExifInterface.TAG_DATETIME_ORIGINAL to "2026:10:09 12:00:00")))
    }
}
