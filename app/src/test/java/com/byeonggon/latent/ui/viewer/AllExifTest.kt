package com.byeonggon.latent.ui.viewer

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AllExifTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun photo(vararg tags: Pair<String, String>, place: Pair<Double, Double>? = null): File {
        val file = folder.newFile("DSC00001.JPG")
        file.outputStream().use { Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file).apply {
            tags.forEach { (tag, value) -> setAttribute(tag, value) }
            place?.let { (latitude, longitude) -> setLatLong(latitude, longitude) }
            saveAttributes()
        }
        return file
    }

    private fun rows(groups: List<ExifGroup>) = groups.associate { it.section to it.rows.associate { row -> row.name to row.value } }

    @Test
    fun listsWhatTheFileRecordsInPartsUnderTheStandardsNames() {
        val file = photo(
            ExifInterface.TAG_MODEL to "ZV-E10",
            ExifInterface.TAG_LENS_MODEL to "E PZ 18-105mm F4 G OSS",
            ExifInterface.TAG_EXPOSURE_TIME to "0.004",
            ExifInterface.TAG_F_NUMBER to "4",
            ExifInterface.TAG_FOCAL_LENGTH to "35/1",
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE to "-2/3",
            ExifInterface.TAG_DATETIME_ORIGINAL to "2026:10:09 15:42:00",
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL to "+09:00",
            place = 37.55 to 126.92,
        )

        val groups = PhotoDetails.all(file)

        assertEquals(listOf(ExifSection.CAMERA, ExifSection.EXPOSURE, ExifSection.IMAGE, ExifSection.TIME, ExifSection.PLACE), groups.map { it.section })
        val shown = rows(groups)
        assertEquals("ZV-E10", shown.getValue(ExifSection.CAMERA)["Model"])
        assertEquals("E PZ 18-105mm F4 G OSS", shown.getValue(ExifSection.CAMERA)["LensModel"])
        assertEquals("1/250 s", shown.getValue(ExifSection.EXPOSURE)["ExposureTime"])
        assertEquals("4", shown.getValue(ExifSection.EXPOSURE)["FNumber"])
        assertEquals("35", shown.getValue(ExifSection.EXPOSURE)["FocalLength"])
        assertEquals("-0.667", shown.getValue(ExifSection.EXPOSURE)["ExposureBiasValue"])
        assertEquals("2026:10:09 15:42:00", shown.getValue(ExifSection.TIME)["DateTimeOriginal"])
        assertEquals("+09:00", shown.getValue(ExifSection.TIME)["OffsetTimeOriginal"])
        assertEquals("37.550000, 126.920000", shown.getValue(ExifSection.PLACE)["GPSPosition"])
    }

    @Test
    fun leavesOutWhatTheFileDoesNotRecordAndPartsWithNothingInThem() {
        val groups = PhotoDetails.all(photo(ExifInterface.TAG_MODEL to "ZV-E10"))

        val shown = rows(groups)
        assertEquals(null, shown[ExifSection.PLACE])
        assertEquals(null, shown[ExifSection.TIME])
        assertEquals(null, shown.getValue(ExifSection.CAMERA)["LensModel"])
        assertTrue(groups.all { group -> group.rows.all { it.value.isNotBlank() } })
    }

    @Test
    fun saysNothingOfAFileThatIsNotAPicture() {
        val notAPicture = folder.newFile("notes.txt").apply { writeText("not a picture") }

        assertEquals(emptyList<ExifGroup>(), PhotoDetails.all(notAPicture))
        assertEquals(emptyList<ExifGroup>(), PhotoDetails.all(File(folder.root, "missing.jpg")))
    }

    @Test
    fun writesValuesAsPeopleReadThem() {
        assertEquals("35", PhotoDetails.readable("FocalLength", "35/1"))
        assertEquals("5.6", PhotoDetails.readable("FNumber", "56/10"))
        assertEquals("1/250 s", PhotoDetails.readable("ExposureTime", "1/250"))
        assertEquals("2 s", PhotoDetails.readable("ExposureTime", "2/1"))
        assertEquals("0", PhotoDetails.readable("ExposureBiasValue", "0/1"))
        // Kept as it is: not a ratio, or one that divides by nothing.
        assertEquals("ZV-E10", PhotoDetails.readable("Model", " ZV-E10 "))
        assertEquals("5/0", PhotoDetails.readable("FNumber", "5/0"))
        assertNull(PhotoDetails.readable("Model", "  "))
    }
}
