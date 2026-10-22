package com.byeonggon.latent.backup

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun addsDeclaredSizesWithoutWrappingRound() {
        val big = Long.MAX_VALUE / 2
        // Three of these add up, wrapped, to a negative number: they must come out as the too many they are.
        assertTrue(big + big + big < 0)
        assertFalse(BackupArchive.fitsWithin(sequenceOf(big, big, big), BackupArchive.MOST_UNPACKED))
        assertFalse(BackupArchive.fitsWithin(sequenceOf(Long.MAX_VALUE, Long.MAX_VALUE), Long.MAX_VALUE))
        assertFalse(BackupArchive.fitsWithin(sequenceOf(10, 10, 1), 20))
        // Exactly the limit fits; a size not known (-1) counts for none.
        assertTrue(BackupArchive.fitsWithin(sequenceOf(10, -1, 10), 20))
        assertTrue(BackupArchive.fitsWithin(emptySequence(), 0))
    }

    @Test
    fun refusesZip64SizesThatWouldWrapRoundInASum() = runBlocking {
        val big = Long.MAX_VALUE / 2
        val file = zip64(BackupFormat.LOCATIONS to big, BackupFormat.MEDIA to big, BackupFormat.ALBUMS to big)

        val error = assertThrows<DamagedBackupException> { BackupArchive.open(file) }
        assertTrue(error.message!!, error.message!!.contains("sizes"))
        // The same file, its sizes small, opens: it is the sizes that were refused.
        BackupArchive.open(zip64(BackupFormat.LOCATIONS to 10L, BackupFormat.MEDIA to 10L)).close()
        Unit
    }

    @Test
    fun theLimitsAreFarAboveWhatTheAppWrites() {
        // The writer's entries are eleven and one a LUT or mask in use; 150,000 places are about 15 MB of text.
        assertTrue(BackupArchive.MOST_ENTRIES >= 100_000)
        assertTrue(BackupArchive.MOST_UNPACKED >= 150L * 15 * 1024 * 1024)
    }

    /**
     * A zip of the manifest and, for each of [declared], an entry with no data whose directory (Zip64 extra field) says
     * it unpacks to that many bytes: the zip is written by hand, as no zip writer says what a damaged file does.
     */
    private fun zip64(vararg declared: Pair<String, Long>): File = folders.newFile().also { file ->
        val out = java.io.ByteArrayOutputStream()
        fun le(value: Long, bytes: Int) = repeat(bytes) { out.write((value shr (8 * it)).toInt() and 0xff) }
        class Placed(val name: String, val crc: Long, val size: Long, val offset: Int, val real: Boolean)
        val placed = ArrayList<Placed>()
        val data = manifest.toByteArray()
        val rest = listOf(BackupFormat.MANIFEST to -1L) + declared.toList()
        for ((name, size) in rest) {
            val real = size < 0
            val bytes = if (real) data else ByteArray(0)
            val crc = java.util.zip.CRC32().also { it.update(bytes) }.value
            placed += Placed(name, crc, if (real) bytes.size.toLong() else size, out.size(), real)
            le(0x04034b50, 4); le(20, 2); le(0, 2); le(0, 2); le(0, 2); le(0x21, 2)
            le(crc, 4); le(bytes.size.toLong(), 4); le(bytes.size.toLong(), 4)
            le(name.length.toLong(), 2); le(0, 2)
            out.write(name.toByteArray()); out.write(bytes)
        }
        val directory = out.size()
        for (entry in placed) {
            val extra = if (entry.real) 0 else 20
            le(0x02014b50, 4); le(45, 2); le(45, 2); le(0, 2); le(0, 2); le(0, 2); le(0x21, 2)
            le(entry.crc, 4)
            if (entry.real) { le(entry.size, 4); le(entry.size, 4) } else { le(0xffffffffL, 4); le(0xffffffffL, 4) }
            le(entry.name.length.toLong(), 2); le(extra.toLong(), 2); le(0, 2); le(0, 2); le(0, 2); le(0, 4)
            le(entry.offset.toLong(), 4)
            out.write(entry.name.toByteArray())
            if (!entry.real) { le(1, 2); le(16, 2); le(entry.size, 8); le(0, 8) }
        }
        val size = out.size() - directory
        le(0x06054b50, 4); le(0, 2); le(0, 2); le(placed.size.toLong(), 2); le(placed.size.toLong(), 2)
        le(size.toLong(), 4); le(directory.toLong(), 4); le(0, 2)
        file.writeBytes(out.toByteArray())
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
