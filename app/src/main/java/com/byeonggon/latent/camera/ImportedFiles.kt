package com.byeonggon.latent.camera

import androidx.exifinterface.media.ExifInterface
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Arrays
import kotlin.math.abs

/** Every time zone offset is a whole number of quarter hours. */
internal const val ZONE_OFFSET_STEP_MS = 15 * 60_000L

/** UTC-12 to UTC+14: the furthest apart two readings of the same wall-clock time can be. */
private const val ZONE_OFFSET_SPAN_MS = 26 * 60 * 60_000L

/**
 * The camera files that were already imported, looked up in a way that survives the phone changing
 * time zone.
 *
 * A camera reports file times as wall-clock time and Android reads them in whichever zone the phone is
 * in at that moment, so the same file shows up with a shifted timestamp after travelling. A file with
 * the same name and size whose time differs by a whole number of quarter hours is therefore the same file.
 */
internal class ImportedFiles(sourceKeys: Collection<String>) {
    /** By name and size, the camera and the time of each import. */
    private val imports = HashMap<String, MutableList<Import>>()

    private class Import(val cameraId: String, val time: Long)

    init {
        sourceKeys.forEach(::add)
    }

    fun add(sourceKey: String) {
        // camera|name|size|time, read from the end: a camera's id is whatever the camera said.
        val timeAt = sourceKey.lastIndexOf('|')
        if (timeAt < 0) return
        val time = sourceKey.substring(timeAt + 1).toLongOrNull() ?: return
        val sizeAt = sourceKey.lastIndexOf('|', timeAt - 1)
        val nameAt = if (sizeAt > 0) sourceKey.lastIndexOf('|', sizeAt - 1) else -1
        if (nameAt < 0) return
        imports.getOrPut(sourceKey.substring(nameAt + 1, timeAt)) { ArrayList() } += Import(sourceKey.substring(0, nameAt), time)
    }

    /** Whether [file] was imported from this camera, or this card, before. */
    fun contains(file: RemoteFile, cameraId: String): Boolean = matches(file).any { it.cameraId == cameraId }

    /**
     * The source keys under which a file like [file] came in from elsewhere, when this source or that one is a card
     * read as a folder (see [isFolderCamera]), which cannot say which camera wrote it. Only candidates: two cameras'
     * DSC00001.ARW can share a name, a size and a time, so what they hold has still to be compared.
     */
    fun importedElsewhere(file: RemoteFile, cameraId: String): List<String> = matches(file)
        .filter { it.cameraId != cameraId && (isFolderCamera(it.cameraId) || isFolderCamera(cameraId)) }
        .map { "${it.cameraId}|${file.name}|${file.sizeBytes}|${it.time}" }

    private fun matches(file: RemoteFile): List<Import> =
        imports["${file.name}|${file.sizeBytes}"].orEmpty().filter { import ->
            // Between times near either end of what a Long counts, as a damaged key or camera might give, the difference
            // itself wraps round, and could come to a quarter hour.
            val shift = try {
                abs(Math.subtractExact(import.time, file.modifiedAt))
            } catch (e: ArithmeticException) {
                return@filter false
            }
            shift in 0..ZONE_OFFSET_SPAN_MS && shift % ZONE_OFFSET_STEP_MS == 0L
        }
}


/**
 * Whether [copy], just copied off a camera or a card, holds what [imported] held when it came in: byte for byte, but
 * for a JPEG's EXIF segment, which the place found for it was written into on the way in. That one is compared value
 * by value instead, all but the place: two JPEGs of the same pixels turned another way, or taken at another time, are
 * two photos. Nothing less than the whole file is compared: two different raw files can share a name, a size and every
 * byte of their ends.
 */
internal fun sameAsImported(copy: File, imported: File): Boolean = try {
    val copyParts = jpegParts(copy)
    val importedParts = jpegParts(imported)
    if (copyParts != null && importedParts != null) {
        copyParts.segments.size == importedParts.segments.size &&
            copyParts.segments.zip(importedParts.segments).all { (a, b) -> a.contentEquals(b) } &&
            sameFrom(copy, copyParts.scanAt, imported, importedParts.scanAt) &&
            sameExifButPlace(copy, imported)
    } else {
        sameFrom(copy, 0, imported, 0)
    }
} catch (e: IOException) {
    false
}

/** Whether the EXIF of two JPEGs says the same, but for the place, which the app writes in. */
private fun sameExifButPlace(a: File, b: File): Boolean {
    val x = ExifInterface(a)
    val y = ExifInterface(b)
    return EXIF_BUT_PLACE.all { x.getAttribute(it) == y.getAttribute(it) }
}

/** What a JPEG's EXIF says of its photo: what a photo's details show, but its place, and the maker's notes. */
private val EXIF_BUT_PLACE = listOf(
    ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_LENS_MAKE,
    ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SPECIFICATION, ExifInterface.TAG_LENS_SERIAL_NUMBER,
    ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_CAMERA_OWNER_NAME,
    ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
    ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
    ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_FLASH, ExifInterface.TAG_LIGHT_SOURCE, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
    ExifInterface.TAG_SCENE_CAPTURE_TYPE, ExifInterface.TAG_CONTRAST, ExifInterface.TAG_SATURATION, ExifInterface.TAG_SHARPNESS,
    ExifInterface.TAG_PIXEL_X_DIMENSION, ExifInterface.TAG_PIXEL_Y_DIMENSION, ExifInterface.TAG_IMAGE_WIDTH,
    ExifInterface.TAG_IMAGE_LENGTH, ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_COLOR_SPACE,
    ExifInterface.TAG_IMAGE_UNIQUE_ID, ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_USER_COMMENT,
    ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
    ExifInterface.TAG_DATETIME, ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_OFFSET_TIME,
    ExifInterface.TAG_MAKER_NOTE,
)

/** A JPEG's segments before its image data, less its EXIF, and where its image data starts. */
private class JpegParts(val segments: List<ByteArray>, val scanAt: Long)

/** The parts of [file] as a JPEG; null if it is not one, or not one these markers describe. */
private fun jpegParts(file: File): JpegParts? = DataInputStream(file.inputStream().buffered()).use { input ->
    try {
        if (input.readUnsignedShort() != 0xFFD8) return@use null
        val segments = ArrayList<ByteArray>()
        var at = 2L
        var marker = input.readUnsignedShort()
        // Up to the start of the scan: the image data follows it, to the end of the file.
        while (marker != 0xFFDA) {
            if (marker shr 8 != 0xFF) return@use null
            val length = input.readUnsignedShort()
            if (length < 2) return@use null
            val payload = ByteArray(length - 2).also(input::readFully)
            val exif = marker == 0xFFE1 && payload.size >= EXIF.size && payload.copyOf(EXIF.size).contentEquals(EXIF)
            if (!exif) segments += byteArrayOf((marker shr 8).toByte(), marker.toByte()) + payload
            at += 2L + length
            marker = input.readUnsignedShort()
        }
        JpegParts(segments, at)
    } catch (e: EOFException) {
        null
    }
}

private val EXIF = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)

/** Whether [a] from [aFrom] on and [b] from [bFrom] on hold the same bytes. */
private fun sameFrom(a: File, aFrom: Long, b: File, bFrom: Long): Boolean {
    if (a.length() - aFrom != b.length() - bFrom) return false
    RandomAccessFile(a, "r").use { x ->
        RandomAccessFile(b, "r").use { y ->
            x.seek(aFrom)
            y.seek(bFrom)
            val xs = ByteArray(COMPARED_AT_ONCE)
            val ys = ByteArray(COMPARED_AT_ONCE)
            while (true) {
                val read = x.read(xs)
                if (read <= 0) return true
                y.readFully(ys, 0, read)
                if (!Arrays.equals(xs, 0, read, ys, 0, read)) return false
            }
        }
    }
}

private const val COMPARED_AT_ONCE = 256 * 1024
