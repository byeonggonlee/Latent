package com.byeonggon.latent

import android.content.ContentUris
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Times the ways a library thumbnail can be made from real camera files, to see what scrolling the
 * library costs. Pass `-e jpegSample <path> -e arwSample <path>`; only the app's thumbnail cache is written.
 */
@RunWith(AndroidJUnit4::class)
class ThumbnailOnDeviceTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val samples = listOfNotNull(arguments.getString("jpegSample"), arguments.getString("arwSample")).map(::File)

    private fun <T> timed(what: String, runs: Int = 3, block: () -> T): T {
        var result: T? = null
        val times = List(runs) {
            val startedAt = System.nanoTime()
            result = block()
            (System.nanoTime() - startedAt) / 1_000_000
        }
        Log.i(TAG, "on device: $what in ${times.sorted()[runs / 2]} ms (first ${times.first()})")
        return result!!
    }

    @Test
    fun timesTheWaysToMakeAThumbnail() = runBlocking {
        assumeTrue(samples.isNotEmpty() && samples.all { it.isFile })
        val loader = SingletonImageLoader.get(context)
        for (file in samples) {
            val coil = timed("${file.name} coil at 420px") {
                runBlocking {
                    loader.execute(
                        ImageRequest.Builder(context)
                            .data(file)
                            .size(420)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .build(),
                    )
                }
            }
            Log.i(TAG, "on device: ${file.name} coil gave ${(coil as? SuccessResult)?.image?.let { "${it.width}x${it.height}" } ?: coil}")

            val thumbnails = com.byeonggon.latent.data.Thumbnails(context)
            thumbnails.fileFor(file.path, file.length()).delete()
            val made = timed("${file.name} stored thumbnail made", runs = 1) { runBlocking { thumbnails.of(file, file.length()) } }
            timed("${file.name} stored thumbnail again") { runBlocking { thumbnails.of(file, file.length()) } }
            timed("${file.name} coil from stored thumbnail") {
                runBlocking {
                    loader.execute(ImageRequest.Builder(context).data(made).size(420).memoryCachePolicy(CachePolicy.DISABLED).build())
                }
            }
            Log.i(TAG, "on device: ${file.name} stored thumbnail ${made?.length()} bytes")

            val sampled = timed("${file.name} BitmapFactory 1/16") {
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 16 })
            }
            Log.i(TAG, "on device: ${file.name} BitmapFactory gave ${sampled?.let { "${it.width}x${it.height}" }}")

            val exif = timed("${file.name} exif thumbnail") { ExifInterface(file).thumbnailBitmap }
            Log.i(TAG, "on device: ${file.name} exif thumbnail ${exif?.let { "${it.width}x${it.height}" }}")

            val id = context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DATA} = ?",
                arrayOf(file.path.replace("/sdcard/", "/storage/emulated/0/")),
                null,
            )?.use { if (it.moveToFirst()) it.getLong(0) else null }
            if (id == null) {
                Log.i(TAG, "on device: ${file.name} not in MediaStore")
                continue
            }
            val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), id)
            val system = runCatching {
                timed("${file.name} MediaStore thumbnail") { context.contentResolver.loadThumbnail(uri, Size(420, 420), null) }
            }
            Log.i(TAG, "on device: ${file.name} MediaStore gave ${system.getOrNull()?.let { "${it.width}x${it.height}" } ?: system.exceptionOrNull()}")
        }
    }

    private companion object {
        const val TAG = "ThumbnailTest"
    }
}
