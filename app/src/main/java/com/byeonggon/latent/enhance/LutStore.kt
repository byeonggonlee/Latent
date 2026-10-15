package com.byeonggon.latent.enhance

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The LUTs the person imported, each kept in a file of its own under [directory], named by the id that
 * edits refer to it by. A file holds the table as plain numbers, which is quicker to read again than
 * the text it was imported from. The tables read lately are kept at hand: the editor asks for them with
 * every move of a slider.
 */
object LutStore {
    private const val EXTENSION = ".lut"
    private const val MAGIC = 0x4C555431 // "LUT1"
    private const val HEADER_BYTES = 4 + 4 + 6 * 4

    /** About 16 MB of tables: four of the largest, or dozens of the usual 33-point ones. */
    private const val KEPT_VALUES = 4 * 1024 * 1024
    private val ID = Regex("[0-9a-f]{32}")

    // In the order they were last asked for.
    private val kept = LinkedHashMap<String, ColorLut>(16, 0.75f, true)

    /** Where the files are; null where there are none, as in tests that do not need them. Setting it forgets the tables at hand. */
    @Volatile
    var directory: File? = null
        set(value) = synchronized(kept) {
            field = value
            kept.clear()
        }

    /** The id of the LUT imported from [bytes]: a digest of them, so that the same file imported twice is one LUT. */
    fun idOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).copyOf(16).joinToString("") { "%02x".format(it) }

    /** The file of the LUT with [id]; null without a [directory], or for what is no id. */
    fun fileOf(id: String): File? = directory?.takeIf { ID.matches(id) }?.let { File(it, id + EXTENSION) }

    /** Keeps [lut] under [id], unless it is kept already. */
    @Throws(IOException::class)
    fun save(id: String, lut: ColorLut) {
        val file = fileOf(id) ?: throw IOException("No place to keep LUTs")
        if (file.isFile && read(file) != null) return
        file.parentFile?.mkdirs()
        val buffer = ByteBuffer.allocate(HEADER_BYTES + lut.table.size * 4)
        buffer.putInt(MAGIC).putInt(lut.size)
        for (value in lut.domainMin) buffer.putFloat(value)
        for (value in lut.domainMax) buffer.putFloat(value)
        buffer.asFloatBuffer().put(lut.table)
        // Written aside first, so that a file under its name is always whole.
        val partial = File(file.parentFile, file.name + ".part")
        partial.writeBytes(buffer.array())
        if (!partial.renameTo(file)) {
            partial.delete()
            throw IOException("Could not keep the LUT $id")
        }
    }

    /** The LUT with [id]; null when it is not kept or its file cannot be read, and an edit then looks as if it had none. */
    fun load(id: String): ColorLut? = synchronized(kept) {
        kept[id] ?: fileOf(id)?.let(::read)?.also { lut ->
            kept[id] = lut
            var values = kept.values.sumOf { it.table.size }
            val eldest = kept.entries.iterator()
            while (values > KEPT_VALUES && kept.size > 1) {
                values -= eldest.next().value.table.size
                eldest.remove()
            }
        }
    }

    /** Lets go of the LUT with [id], and of its file. */
    fun delete(id: String) {
        synchronized(kept) { kept.remove(id) }
        fileOf(id)?.delete()
    }

    /** The ids of all the LUTs kept in files. */
    fun ids(): List<String> = directory?.listFiles().orEmpty().mapNotNull { file ->
        file.name.removeSuffix(EXTENSION).takeIf { file.name.endsWith(EXTENSION) && ID.matches(it) }
    }

    private fun read(file: File): ColorLut? {
        if (!file.isFile) return null
        return try {
            val buffer = ByteBuffer.wrap(file.readBytes())
            if (buffer.remaining() < HEADER_BYTES || buffer.getInt() != MAGIC) return null
            val size = buffer.getInt()
            if (size !in ColorLut.SMALLEST..ColorLut.LARGEST) return null
            val domainMin = FloatArray(3) { buffer.getFloat() }
            val domainMax = FloatArray(3) { buffer.getFloat() }
            val table = FloatArray(size * size * size * 3)
            if (buffer.remaining() != table.size * 4) return null
            buffer.asFloatBuffer().get(table)
            ColorLut(size, table, domainMin, domainMax)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            // A domain with nothing in it: not a file written here.
            null
        }
    }
}
