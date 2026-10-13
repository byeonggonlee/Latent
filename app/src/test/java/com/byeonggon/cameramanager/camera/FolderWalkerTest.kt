package com.byeonggon.cameramanager.camera

import com.byeonggon.cameramanager.data.db.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderWalkerTest {
    private val tree = FakeTree()

    private fun list(request: ListingRequest = ListingRequest()) =
        listStorage(tree, FakeTree.ROOT, "card", request, onFound = {})

    @Test
    fun collectsMediaFromNestedFoldersAndCountsWhatItSkips() {
        val dcim = tree.folder(FakeTree.ROOT, "DCIM")
        val shots = tree.folder(dcim, "100MSDCF")
        tree.file(shots, "DSC00001.JPG")
        tree.file(shots, "DSC00001.ARW")
        tree.file(shots, "C0001M01.XML")
        tree.file(shots, "README")
        tree.file(tree.folder(FakeTree.ROOT, "CLIP"), "C0001.MP4")

        val listing = list()

        assertEquals(
            mapOf(
                "DSC00001.JPG" to MediaKind.PHOTO,
                "DSC00001.ARW" to MediaKind.RAW,
                "C0001.MP4" to MediaKind.VIDEO,
            ),
            listing.files.associate { it.name to it.kind },
        )
        assertEquals("card:/DCIM/100MSDCF/", listing.files.first { it.name == "DSC00001.JPG" }.folder)
        assertEquals(mapOf("xml" to 1, "(none)" to 1), listing.skippedExtensions)
    }

    @Test
    fun doesNotOpenDateFoldersOutsideTheRequestedDates() {
        tree.file(tree.folder(FakeTree.ROOT, "2024-12-07"), "DSC00001.JPG")
        tree.file(tree.folder(FakeTree.ROOT, "2024-12-08"), "DSC00002.JPG")
        tree.file(tree.folder(FakeTree.ROOT, "2024-12-09"), "DSC00003.JPG")
        // Without a date in its name there is no telling what is inside, so it has to be opened.
        tree.file(tree.folder(FakeTree.ROOT, "100MSDCF"), "DSC00004.JPG")
        val day = LocalDate.of(2024, 12, 8)

        val listing = list(ListingRequest(dates = SyncDates(day, day)))

        assertEquals(setOf("2024-12-08", "100MSDCF"), tree.listedFolders.toSet())
        assertEquals(setOf("DSC00002.JPG", "DSC00004.JPG"), listing.files.map { it.name }.toSet())
        assertEquals(2, listing.foldersOutsideDates)
    }

    @Test
    fun skipsAnImportedFolderAfterLookingAtItsFirstAndLastObjectOnly() {
        val day = tree.folder(FakeTree.ROOT, "2024-12-08")
        repeat(50) { tree.file(day, "DSC%05d.JPG".format(it)) }
        val imported = list().fingerprints
        tree.lookups = 0

        val listing = list(ListingRequest(importedFolders = imported))

        assertEquals(emptyList<RemoteFile>(), listing.files)
        assertEquals(1, listing.foldersUnchanged)
        // The folder itself, to learn its name, then its first and last file.
        assertEquals(3, tree.lookups)
    }

    @Test
    fun readsAnImportedFolderAgainOnceItsContentsChange() {
        val day = tree.folder(FakeTree.ROOT, "2024-12-08")
        repeat(3) { tree.file(day, "DSC%05d.JPG".format(it)) }
        val imported = list().fingerprints
        tree.file(day, "DSC00003.JPG")

        val listing = list(ListingRequest(importedFolders = imported))

        assertEquals(4, listing.files.size)
        assertEquals(0, listing.foldersUnchanged)
    }

    @Test
    fun onlyFingerprintsPlainFoldersOfFiles() {
        val dcim = tree.folder(FakeTree.ROOT, "DCIM")
        tree.file(dcim, "DSC00001.JPG")
        tree.file(tree.folder(dcim, "100MSDCF"), "DSC00002.JPG")

        assertEquals(setOf("card:/DCIM/100MSDCF/"), list().fingerprints.keys)
    }
}
