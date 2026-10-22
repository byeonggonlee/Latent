package com.byeonggon.latent.backup

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * A backup file opened for reading. Its entries can come in any order; each is read as a stream and checked
 * against its checksum, so that a file damaged anywhere is found out before anything is changed.
 */
internal class BackupArchive private constructor(
    private val zip: ZipFile,
    val manifest: BackupManifest,
    private val mostUnpacked: Long,
) : Closeable {
    /** The names of the entries, in the order they are in the file. */
    val names: List<String> = zip.entries().asSequence().map { it.name }.toList()

    /**
     * Reads every line of the JSON Lines entry [name], none if there is no such entry, and hands what [parse] makes
     * of it to [each]; a line [parse] makes nothing of is left out. Returns how many were handed on.
     */
    suspend fun <T : Any> eachRecord(name: String, parse: (JsonObject) -> T?, each: suspend (T) -> Unit): Int {
        val entry = zip.getEntry(name) ?: return 0
        var count = 0
        readChecked(entry) { input ->
            val reader = input.bufferedReader(Charsets.UTF_8)
            var line = 0
            while (true) {
                line++
                val text = reader.readBoundedLine(LINE_LIMIT) ?: break
                if (text.length > LINE_LIMIT) throw DamagedBackupException(name, line)
                if (line % CANCEL_CHECK == 0) currentCoroutineContext().ensureActive()
                if (text.isBlank()) continue
                val record = try {
                    parse(JsonParser.parseString(text).asJsonObject)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    // Gson and the readers report what is not as written here with assorted unchecked exceptions.
                    throw DamagedBackupException(name, line, e)
                }
                if (record != null) {
                    each(record)
                    count++
                }
            }
        }
        return count
    }

    /** The JSON object in the entry [name]; null if there is none. */
    suspend fun json(name: String): JsonObject? {
        val entry = zip.getEntry(name) ?: return null
        val text = readChecked(entry) { it.readLimited(SMALL_ENTRY, name) }.toString(Charsets.UTF_8)
        return try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: RuntimeException) {
            throw DamagedBackupException(name, cause = e)
        }
    }

    /** The bytes of the entry [name], which must not be larger than [limit]. */
    suspend fun bytes(name: String, limit: Int): ByteArray {
        val entry = zip.getEntry(name) ?: throw DamagedBackupException(name)
        return readChecked(entry) { it.readLimited(limit, name) }
    }

    override fun close() = zip.close()

    /** The entries read whole so far, and the bytes they unpacked to: each counts once, however often it is read. */
    private val counted = HashSet<String>()
    private var unpacked = 0L

    private suspend fun <R> readChecked(entry: ZipEntry, block: suspend (InputStream) -> R): R = try {
        val first = entry.name !in counted
        CheckedInputStream(Unpacked(zip.getInputStream(entry), entry.name, counting = first), CRC32()).use { checked ->
            val result = block(checked)
            // What the reader left unread counts too.
            val rest = ByteArray(DRAIN)
            while (checked.read(rest) >= 0) Unit
            if (entry.crc != -1L && checked.checksum.value != entry.crc) throw DamagedBackupException(entry.name)
            if (first) counted += entry.name
            result
        }
    } catch (e: BackupException) {
        throw e
    } catch (e: IOException) {
        // A zip cut short or changed fails to inflate, or ends before its entry does.
        throw DamagedBackupException(entry.name, cause = e)
    }

    /**
     * An entry as it unpacks, counted towards [mostUnpacked] when [counting]: a file a few kilobytes long can unpack
     * to many gigabytes, whatever sizes its headers say.
     */
    private inner class Unpacked(input: InputStream, private val name: String, private val counting: Boolean) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { if (it > 0) count(it) }

        private fun count(bytes: Int) {
            if (!counting) return
            unpacked += bytes
            if (unpacked > mostUnpacked) throw DamagedBackupException(name)
        }
    }

    companion object {
        /**
         * The most entries a backup is read with. The app writes eleven and one a LUT or a detected mask that is used:
         * tens or hundreds for a library, never near this.
         */
        internal const val MOST_ENTRIES = 200_000

        /**
         * The most the entries a restore reads may unpack to, together. The largest, the timeline, takes about 100 bytes
         * a place: this is some 40 million places, decades recorded every minute.
         */
        internal const val MOST_UNPACKED = 4L * 1024 * 1024 * 1024

        private const val CANCEL_CHECK = 512
        private const val DRAIN = 8192
        private const val SMALL_ENTRY = 1024 * 1024

        /** The longest line of a records file: a version's edit or a place is a few KB; a line without an end is not one. */
        private const val LINE_LIMIT = 4 * 1024 * 1024

        /**
         * Opens [file]; throws [NotABackupException] for what is no Latent backup, [NewerBackupException] for a newer one,
         * and [DamagedBackupException] for one of more than [mostEntries] entries, or whose entries unpack to more than
         * [mostUnpacked] bytes together, then or as they are read.
         */
        @Throws(IOException::class)
        suspend fun open(file: File, mostEntries: Int = MOST_ENTRIES, mostUnpacked: Long = MOST_UNPACKED): BackupArchive {
            val zip = try {
                ZipFile(file)
            } catch (e: IOException) {
                throw NotABackupException(e)
            }
            try {
                val entry = zip.getEntry(BackupFormat.MANIFEST) ?: throw NotABackupException()
                // What the zip's own directory says, before anything is unpacked; what it leaves out is counted as it unpacks.
                if (zip.size() > mostEntries) throw DamagedBackupException("(${zip.size()} entries)")
                if (zip.entries().asSequence().sumOf { it.size.coerceAtLeast(0) } > mostUnpacked) throw DamagedBackupException("(sizes)")
                val manifest = try {
                    val bytes = CheckedInputStream(zip.getInputStream(entry), CRC32()).use { checked ->
                        checked.readLimited(SMALL_ENTRY, entry.name).also {
                            // What the manifest says of a file that its checksum disowns is not to be trusted.
                            while (checked.read() >= 0) Unit
                            if (entry.crc != -1L && checked.checksum.value != entry.crc) throw DamagedBackupException(entry.name)
                        }
                    }
                    BackupManifest.fromJson(JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject)
                } catch (e: BackupException) {
                    throw e
                } catch (e: IOException) {
                    throw NotABackupException(e)
                } catch (e: RuntimeException) {
                    throw NotABackupException(e)
                }
                currentCoroutineContext().ensureActive()
                return BackupArchive(zip, manifest, mostUnpacked)
            } catch (e: Throwable) {
                zip.close()
                throw e
            }
        }

        /**
         * The next line, or null at the end; one longer than [limit] comes back one character too long, so that the caller
         * can refuse it, without reading on to its end, which a damaged file might not have.
         */
        private fun Reader.readBoundedLine(limit: Int): String? {
            val line = StringBuilder()
            while (true) {
                val next = read()
                if (next < 0) return if (line.isEmpty()) null else line.toString()
                if (next == '\n'.code) return line.toString().removeSuffix("\r")
                line.append(next.toChar())
                if (line.length > limit) return line.toString()
            }
        }

        private fun InputStream.readLimited(limit: Int, name: String): ByteArray {
            val bytes = readNBytes(limit + 1)
            if (bytes.size > limit) throw DamagedBackupException(name)
            return bytes
        }
    }
}
