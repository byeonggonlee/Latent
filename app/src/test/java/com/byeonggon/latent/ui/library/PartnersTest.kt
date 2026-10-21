package com.byeonggon.latent.ui.library

import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PartnersTest {
    private fun item(id: Long, path: String, kind: MediaKind) =
        MediaItemEntity(id = id, path = path, fileName = path.substringAfterLast('/'), kind = kind, sizeBytes = 1, capturedAt = 1, importedAt = 1)

    @Test
    fun pairsTheRawFileAndTheJpegOfTheSameShot() {
        val items = listOf(
            item(1, "/l/2026-10-08/DSC00001.JPG", MediaKind.PHOTO),
            item(2, "/l/2026-10-08/DSC00001.ARW", MediaKind.RAW),
            item(3, "/l/2026-10-08/DSC00002.ARW", MediaKind.RAW),
            // The same name on another day is another shot.
            item(4, "/l/2026-10-09/DSC00001.JPG", MediaKind.PHOTO),
            // A JPEG developed here is not what the camera wrote.
            item(5, "/l/2026-10-08/DSC00002_developed.jpg", MediaKind.PHOTO),
            item(6, "/l/2026-10-08/C0001.MP4", MediaKind.VIDEO),
        )

        assertEquals(mapOf(1L to 2L, 2L to 1L), partnersOf(items))
    }

    @Test
    fun pairsAPixelsRawFileWithItsCoverJpeg() {
        val items = listOf(
            item(1, "/p/DCIM/Camera/PXL_20261021_101500123.RAW-02.ORIGINAL.dng", MediaKind.RAW),
            item(2, "/p/DCIM/Camera/PXL_20261021_101500123.RAW-01.COVER.jpg", MediaKind.PHOTO),
            // As a motion photo, the cover says so in its name.
            item(3, "/p/DCIM/Camera/PXL_20261021_101722456.RAW-02.ORIGINAL.dng", MediaKind.RAW),
            item(4, "/p/DCIM/Camera/PXL_20261021_101722456.RAW-01.MP.COVER.jpg", MediaKind.PHOTO),
            // Another shot a moment later, taken without raw.
            item(5, "/p/DCIM/Camera/PXL_20261021_101500124.jpg", MediaKind.PHOTO),
        )

        assertEquals(mapOf(1L to 2L, 2L to 1L, 3L to 4L, 4L to 3L), partnersOf(items))
    }
}
