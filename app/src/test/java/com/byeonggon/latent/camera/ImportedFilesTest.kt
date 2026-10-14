package com.byeonggon.latent.camera

import com.byeonggon.latent.data.db.MediaKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedFilesTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val importedAt = 1_733_600_000_000

    private fun file(name: String = "DSC00001.ARW", sizeBytes: Long = 25_000_000, modifiedAt: Long = importedAt) =
        RemoteFile(handle = 1, name, sizeBytes, modifiedAt, MediaKind.RAW, folder = "card:/2024-12-08/")

    private val imported = ImportedFiles(listOf(file().sourceKey("camera")))

    @Test
    fun recognisesAnImportedFile() {
        assertTrue(imported.contains(file(), "camera"))
    }

    @Test
    fun recognisesAnImportedFileSeenFromAnotherTimeZone() {
        assertTrue(imported.contains(file(modifiedAt = importedAt - 8 * hour), "camera"))
        assertTrue(imported.contains(file(modifiedAt = importedAt + 5 * hour + 45 * minute), "camera"))
    }

    @Test
    fun treatsAnyOtherDifferenceAsADifferentFile() {
        assertFalse(imported.contains(file(name = "DSC00002.ARW"), "camera"))
        assertFalse(imported.contains(file(sizeBytes = 25_000_001), "camera"))
        assertFalse(imported.contains(file(), "another camera"))
        // A shift that no time zone change can explain.
        assertFalse(imported.contains(file(modifiedAt = importedAt + 8 * hour + 1_000), "camera"))
        assertFalse(imported.contains(file(modifiedAt = importedAt + 48 * hour), "camera"))
    }

    @Test
    fun learnsFilesImportedDuringASync() {
        val later = file(name = "DSC00002.ARW")
        assertFalse(imported.contains(later, "camera"))

        imported.add(later.sourceKey("camera"))

        assertTrue(imported.contains(later, "camera"))
    }
}
