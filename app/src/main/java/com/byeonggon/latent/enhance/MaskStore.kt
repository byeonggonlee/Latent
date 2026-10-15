package com.byeonggon.latent.enhance

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.roundToInt

/**
 * An outline of a mask, [width] by [height] cells over the whole upright picture, each from 0 (left
 * alone) to 255 (covered), row by row.
 */
class MaskRaster(val width: Int, val height: Int, val levels: ByteArray) {
    init {
        require(width > 0 && height > 0 && levels.size == width * height) { "A $width by $height outline with ${levels.size} cells" }
    }

    /** How much each cell is covered, from 0 to 1. */
    fun coverage(): FloatArray = FloatArray(levels.size) { (levels[it].toInt() and 0xFF) / 255f }

    companion object {
        /** The outline of [coverage], from 0 to 1 for each cell; what lies beyond that range counts as its end. */
        fun of(width: Int, height: Int, coverage: FloatArray): MaskRaster =
            MaskRaster(width, height, ByteArray(width * height) { (coverage[it].coerceIn(0f, 1f) * 255f).roundToInt().toByte() })
    }
}

/**
 * The outlines of masks that a model found in pictures, each kept in a file of its own under [directory],
 * named by the id that edits refer to it by. An edit holds only the id: without the file, the mask
 * covers nothing. The outlines read lately are kept at hand, as every rendering of the edit asks for them.
 */
object MaskStore {
    private const val EXTENSION = ".mask"
    private const val MAGIC = 0x4D534B31 // "MSK1"
    private const val KEPT = 6
    private const val LARGEST_SIDE = 8192
    private val ID = Regex("[0-9a-f]{32}")

    // In the order they were last asked for.
    private val kept = LinkedHashMap<String, MaskRaster>(8, 0.75f, true)

    /** Where the files are; null where there are none, as in tests that do not need them. Setting it forgets the outlines at hand. */
    @Volatile
    var directory: File? = null
        set(value) {
            synchronized(kept) {
                field = value
                kept.clear()
            }
            BrushRaster.forget()
        }

    /** A new id, for an outline found just now. */
    fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    /** The file of the outline with [id]; null without a [directory], or for what is no id. */
    fun fileOf(id: String): File? = directory?.takeIf { ID.matches(id) }?.let { File(it, id + EXTENSION) }

    /** Keeps [raster] under [id]. */
    @Throws(IOException::class)
    fun save(id: String, raster: MaskRaster) {
        val file = fileOf(id) ?: throw IOException("No place to keep mask outlines")
        file.parentFile?.mkdirs()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(raster.width)
            data.writeInt(raster.height)
            // An outline is mostly long runs of the same level, which pack down to a few dozen kilobytes.
            DeflaterOutputStream(data).use { it.write(raster.levels) }
        }
        // Written aside first, so that a file under its name is always whole.
        val partial = File(file.parentFile, file.name + ".part")
        partial.writeBytes(bytes.toByteArray())
        if (!partial.renameTo(file)) {
            partial.delete()
            throw IOException("Could not keep the mask outline $id")
        }
        synchronized(kept) { remember(id, raster) }
    }

    /** The outline with [id]; null when it is not kept or its file cannot be read, and the mask then covers nothing. */
    fun load(id: String): MaskRaster? = synchronized(kept) {
        kept[id] ?: fileOf(id)?.let(::read)?.also { remember(id, it) }
    }

    /** Whether the outline with [id] is there to be used. */
    fun isKept(id: String): Boolean = synchronized(kept) { kept.containsKey(id) } || fileOf(id)?.isFile == true

    /** Lets go of the outline with [id], and of its file. */
    fun delete(id: String) {
        synchronized(kept) { kept.remove(id) }
        fileOf(id)?.delete()
    }

    /** The ids of all the outlines kept in files, with when each was made, in milliseconds since 1970. */
    fun kept(): Map<String, Long> = directory?.listFiles().orEmpty().mapNotNull { file ->
        file.name.removeSuffix(EXTENSION).takeIf { file.name.endsWith(EXTENSION) && ID.matches(it) }?.let { it to file.lastModified() }
    }.toMap()

    private fun remember(id: String, raster: MaskRaster) {
        kept[id] = raster
        val eldest = kept.entries.iterator()
        while (kept.size > KEPT) {
            eldest.next()
            eldest.remove()
        }
    }

    private fun read(file: File): MaskRaster? {
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { data ->
                if (data.readInt() != MAGIC) return null
                val width = data.readInt()
                val height = data.readInt()
                if (width !in 1..LARGEST_SIDE || height !in 1..LARGEST_SIDE) return null
                val levels = ByteArray(width * height)
                val inflated = DataInputStream(InflaterInputStream(data))
                inflated.readFully(levels)
                MaskRaster(width, height, levels)
            }
        } catch (e: IOException) {
            // Cut short or not written here: as if it were not kept.
            null
        }
    }
}
