package com.byeonggon.latent.backup

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * How much of a backup is read: a file of more entries than the app could have written, or whose entries unpack to
 * more than it could have, is refused as damaged, whether its directory says so or only its data does.
 */
class BackupLimitsTest {
    @get:Rule
    val folders = TemporaryFolder()

    private val manifest = BackupManifest(1, Instant.EPOCH, AppIdentity("p", "v", 1), BackupCounts()).toJson().toString()

    private fun zip(vararg entries: Pair<String, String>): File = folders.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, text) in listOf(BackupFormat.MANIFEST to manifest) + entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private val place = """{"timestamp":1,"latitude":1,"longitude":2,"source":"APP"}"""

    private suspend fun BackupArchive.places(): Int = eachRecord(BackupFormat.LOCATIONS, { json: JsonObject -> json }) {}

    @Test
    fun refusesMoreEntriesThanItReads() = runBlocking {
        val file = zip(*Array(10) { "luts/$it" to "" })

        BackupArchive.open(file, mostEntries = 11).close()
        assertThrows<DamagedBackupException> { BackupArchive.open(file, mostEntries = 10) }
        Unit
    }

    @Test
    fun refusesEntriesThatSayTheyUnpackToMoreThanItReads() = runBlocking {
        val file = zip(BackupFormat.LOCATIONS to (place + "\n").repeat(100))

        BackupArchive.open(file, mostUnpacked = 20_000).use { assertEquals(100, it.places()) }
        assertThrows<DamagedBackupException> { BackupArchive.open(file, mostUnpacked = 5_000) }
        Unit
    }

    @Test
    fun refusesEntriesThatUnpackToMoreThanTheySay() = runBlocking {
        // A bomb: its directory says each entry unpacks to a byte, its data to far more.
        val file = zip(BackupFormat.LOCATIONS to (place + "\n").repeat(100), BackupFormat.MEDIA to "\n".repeat(10_000))
        lieAboutSizes(file)

        // Read as they are, under a limit they keep to.
        BackupArchive.open(file, mostUnpacked = 20_000).use { archive -> assertEquals(0, archive.eachRecord(BackupFormat.MEDIA, { json: JsonObject -> json }) {}) }
        BackupArchive.open(file, mostUnpacked = 8_000).use { archive ->
            assertEquals(100, archive.places())
            val error = assertThrows<DamagedBackupException> { archive.eachRecord(BackupFormat.MEDIA, { json: JsonObject -> json }) {} }
            assertTrue(error.message!!, error.message!!.contains(BackupFormat.MEDIA))
        }
    }

    @Test
    fun countsAnEntryOnceHoweverOftenItIsRead() = runBlocking {
        // A restore reads the photos three times and the albums twice: the limit is on what the file holds.
        val file = zip(BackupFormat.LOCATIONS to (place + "\n").repeat(100))

        BackupArchive.open(file, mostUnpacked = 7_000).use { archive -> repeat(5) { assertEquals(100, archive.places()) } }
    }

    @Test
    fun theLimitsAreFarAboveWhatTheAppWrites() {
        // The writer's entries are eleven and one a LUT or mask in use; 150,000 places are about 15 MB of text.
        assertTrue(BackupArchive.MOST_ENTRIES >= 100_000)
        assertTrue(BackupArchive.MOST_UNPACKED >= 150L * 15 * 1024 * 1024)
    }

    /** Sets every entry's unpacked size in [file]'s headers, local and central, to one byte. */
    private fun lieAboutSizes(file: File) {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (at in 0 until bytes.size - 4) {
            when (buffer.getInt(at)) {
                LOCAL_HEADER -> if (buffer.getInt(at + 22) > 1) buffer.putInt(at + 22, 1)
                CENTRAL_HEADER -> if (buffer.getInt(at + 24) > 1) buffer.putInt(at + 24, 1)
                DATA_DESCRIPTOR -> if (buffer.getInt(at + 12) > 1) buffer.putInt(at + 12, 1)
            }
        }
        file.writeBytes(bytes)
    }

    private companion object {
        const val LOCAL_HEADER = 0x04034b50
        const val CENTRAL_HEADER = 0x02014b50
        const val DATA_DESCRIPTOR = 0x08074b50
    }
}
