package com.byeonggon.latent.backup

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * A backup file opened for reading. Its entries can come in any order; each is read as a stream and checked
 * against its checksum, so that a file damaged anywhere is found out before anything is changed.
 */
internal class BackupArchive private constructor(private val zip: ZipFile, val manifest: BackupManifest) : Closeable {
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
                val text = reader.readLine() ?: break
                line++
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

    private suspend fun <R> readChecked(entry: ZipEntry, block: suspend (InputStream) -> R): R = try {
        CheckedInputStream(zip.getInputStream(entry), CRC32()).use { checked ->
            val result = block(checked)
            // What the reader left unread counts too.
            val rest = ByteArray(DRAIN)
            while (checked.read(rest) >= 0) Unit
            if (entry.crc != -1L && checked.checksum.value != entry.crc) throw DamagedBackupException(entry.name)
            result
        }
    } catch (e: BackupException) {
        throw e
    } catch (e: IOException) {
        // A zip cut short or changed fails to inflate, or ends before its entry does.
        throw DamagedBackupException(entry.name, cause = e)
    }

    companion object {
        private const val CANCEL_CHECK = 512
        private const val DRAIN = 8192
        private const val SMALL_ENTRY = 1024 * 1024

        /** Opens [file]; throws [NotABackupException] for what is no Latent backup and [NewerBackupException] for a newer one. */
        @Throws(IOException::class)
        suspend fun open(file: File): BackupArchive {
            val zip = try {
                ZipFile(file)
            } catch (e: IOException) {
                throw NotABackupException(e)
            }
            try {
                val entry = zip.getEntry(BackupFormat.MANIFEST) ?: throw NotABackupException()
                val manifest = try {
                    val bytes = CheckedInputStream(zip.getInputStream(entry), CRC32()).use { it.readLimited(SMALL_ENTRY, entry.name) }
                    BackupManifest.fromJson(JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject)
                } catch (e: BackupException) {
                    throw e
                } catch (e: IOException) {
                    throw NotABackupException(e)
                } catch (e: RuntimeException) {
                    throw NotABackupException(e)
                }
                currentCoroutineContext().ensureActive()
                return BackupArchive(zip, manifest)
            } catch (e: Throwable) {
                zip.close()
                throw e
            }
        }

        private fun InputStream.readLimited(limit: Int, name: String): ByteArray {
            val bytes = readNBytes(limit + 1)
            if (bytes.size > limit) throw DamagedBackupException(name)
            return bytes
        }
    }
}
