package com.byeonggon.latent.raw

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.byeonggon.latent.data.Thumbnails
import com.byeonggon.latent.enhance.PhotoEdit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Public samples only, in a test-specific cache directory. Run through am instrument; never uninstall. */
@RunWith(AndroidJUnit4::class)
class PhoneDngOnDeviceTest {
    @Test
    fun opensAndExportsOpenCameraPixel2Xl() = checkSample("opencamera-2206.dng", 4016, 3008)

    @Test
    fun opensAndExportsOpenCameraHonor6A() = checkSample("opencamera-1877.dng", 4192, 3104)

    @Test
    fun opensAndExportsMotionCamTools10Bit() = checkSample("motioncam-0.dng", 4080, 2286)

    @Test
    fun opensAndExportsMotionCamTools12BitDcg() = checkSample("motioncam-1.dng", 4080, 2286)

    private fun checkSample(name: String, width: Int, height: Int): Unit = runBlocking {
        val directory = InstrumentationRegistry.getArguments().getString("phoneDngDir")
        assumeTrue("Pass -e phoneDngDir <public-sample directory>", directory != null)
        val sample = File(directory!!, name)
        assertTrue("Missing $sample", sample.isFile)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val target = File.createTempFile("phone-dng-export-", ".jpg", context.cacheDir)
        var madeThumbnail: File? = null
        try {
            val thumbnails = Thumbnails(context)
            val cached = thumbnails.fileFor(sample.path, sample.length())
            val alreadyCached = cached.exists()
            val thumbnail = thumbnails.of(sample, sample.length())
            if (!alreadyCached) madeThumbnail = thumbnail
            assertNotNull("$name thumbnail", thumbnail)
            val thumbnailBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(thumbnail!!.path, thumbnailBounds)
            assertTrue(thumbnailBounds.outWidth > 0 && thumbnailBounds.outHeight > 0)

            val started = System.nanoTime()
            val image = RawRenderer.read(sample)
            val decodeMs = (System.nanoTime() - started) / 1_000_000
            val preview = RawRenderer.preview(image, PhotoEdit(autoEnhance = false))
            try {
                assertEquals(width / 2 to height / 2, preview.width to preview.height)
            } finally {
                preview.recycle()
            }
            val exportStarted = System.nanoTime()
            RawRenderer.export(sample, target, PhotoEdit(autoEnhance = false))
            val exportMs = (System.nanoTime() - exportStarted) / 1_000_000
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.path, bounds)
            assertEquals(width to height, bounds.outWidth to bounds.outHeight)
            assertTrue(target.length() > 0)
            Log.i("PhoneDng", "$name: decoded in $decodeMs ms; exported ${width}x$height in $exportMs ms")
        } finally {
            target.delete()
            madeThumbnail?.delete()
        }
    }
}
