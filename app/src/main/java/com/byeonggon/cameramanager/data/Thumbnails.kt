package com.byeonggon.cameramanager.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Small copies of the library's photos for the grid, kept in the app's cache. Filling a cell from a
 * 24-megapixel JPEG takes about a fifth of a second on the phone, more than a scrolling grid can
 * wait; a stored thumbnail takes a few milliseconds. A camera JPEG's own preview makes the thumbnail
 * almost as quickly; any other photo is decoded at a fraction of its size, once.
 */
class Thumbnails(context: Context) {
    private val folder = File(context.cacheDir, "thumbnails")

    // Thumbnails are made on one thread of low priority, so that making them never takes more than a
    // core from the grid and the editor. It loads classes the way the app's own code does.
    private val maker = Executors.newSingleThreadExecutor { work ->
        Thread(work, "thumbnails").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            contextClassLoader = Thumbnails::class.java.classLoader
        }
    }.asCoroutineDispatcher()

    /** Where the thumbnail of the photo at [path] goes; a photo replaced by an edit changes size, and so name. */
    fun fileFor(path: String, sizeBytes: Long): File =
        File(folder, UUID.nameUUIDFromBytes("$path#$sizeBytes".toByteArray()).toString() + ".jpg")

    /** The thumbnail of [photo], made first if there is none yet; null if the photo cannot be decoded. */
    suspend fun of(photo: File, sizeBytes: Long): File? {
        val thumbnail = fileFor(photo.path, sizeBytes)
        if (thumbnail.exists()) return thumbnail
        return withContext(maker) { make(photo, thumbnail) }
    }

    private fun make(photo: File, thumbnail: File): File? {
        // Another request for the same photo may have made it while this one waited its turn.
        if (thumbnail.exists()) return thumbnail
        val bitmap = decode(photo) ?: return null
        try {
            folder.mkdirs()
            // Made under a name of its own: the grid may ask for the same photo twice at once.
            val part = File.createTempFile("thumbnail", ".part", folder)
            try {
                part.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                if (!part.renameTo(thumbnail) && !thumbnail.exists()) return null
            } finally {
                part.delete()
            }
        } catch (e: IOException) {
            return null
        } finally {
            bitmap.recycle()
        }
        return thumbnail
    }

    /** Drops the thumbnails of photos that are no longer as they were, given the files that should have one. */
    fun keepOnly(wanted: Collection<File>) {
        val names = wanted.mapTo(HashSet()) { it.name }
        folder.listFiles()?.forEach { if (it.name !in names) it.delete() }
    }

    private fun decode(photo: File): Bitmap? {
        val preview = MpfPreview.of(photo)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (preview != null) BitmapFactory.decodeByteArray(preview, 0, preview.size, bounds) else BitmapFactory.decodeFile(photo.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SHORT_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = (if (preview != null) BitmapFactory.decodeByteArray(preview, 0, preview.size, options) else BitmapFactory.decodeFile(photo.path, options))
            ?: return null
        return upright(shrunk(decoded), photo)
    }

    /** No larger than needed for its short side to fill a cell. */
    private fun shrunk(bitmap: Bitmap): Bitmap {
        val scale = SHORT_SIDE.toFloat() / min(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        val width = max(1, (bitmap.width * scale).toInt())
        val height = max(1, (bitmap.height * scale).toInt())
        return bitmap.scale(width, height).also { if (it !== bitmap) bitmap.recycle() }
    }

    /** Turned the way the camera was held: the thumbnail has no Exif to say so. */
    private fun upright(bitmap: Bitmap, photo: File): Bitmap {
        val exif = try {
            ExifInterface(photo)
        } catch (e: IOException) {
            return bitmap
        }
        val matrix = Matrix()
        if (exif.isFlipped) matrix.postScale(-1f, 1f)
        matrix.postRotate(exif.rotationDegrees.toFloat())
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    private companion object {
        /** Enough for the largest cell, 160dp on a dense screen, to be filled without enlarging much. */
        const val SHORT_SIDE = 400
        const val QUALITY = 85
    }
}
