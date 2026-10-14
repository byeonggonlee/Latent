package com.byeonggon.latent.raw

import android.graphics.BitmapFactory
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.byeonggon.latent.enhance.Adjustments
import com.byeonggon.latent.enhance.PhotoEdit
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Develops a real raw file on the device, where speed and memory limits differ from a desktop JVM.
 * Pass the file with `-e arwSample <path>`; the result goes to the app's cache and is removed again.
 *
 * Run it with `adb shell am instrument`, not with Gradle's connectedAndroidTest: that task uninstalls
 * the app afterwards and takes the library database with it.
 */
@RunWith(AndroidJUnit4::class)
class RawOnDeviceTest {
    private val sample = InstrumentationRegistry.getArguments().getString("arwSample")?.let(::File)

    @Test
    fun developsAFullFrameWithinTheAppsMemory() {
        assumeTrue(sample != null && sample.isFile)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val target = File(context.cacheDir, "raw-on-device-test.jpg")
        val options = PhotoEdit(Adjustments(exposure = 0.3f, shadows = 0.5f, vibrance = 0.3f), autoEnhance = true)
        try {
            val startedAt = System.nanoTime()
            val preview = RawRenderer.preview(RawRenderer.read(sample!!), options)
            val previewMs = (System.nanoTime() - startedAt) / 1_000_000
            val previewSize = "${preview.width}x${preview.height}"
            preview.recycle()

            val exportStartedAt = System.nanoTime()
            RawRenderer.export(sample, target, options)
            val exportMs = (System.nanoTime() - exportStartedAt) / 1_000_000

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.path, bounds)
            val runtime = Runtime.getRuntime()
            Log.i(
                "RawDevelop",
                "on device: preview $previewSize in $previewMs ms, export ${bounds.outWidth}x${bounds.outHeight} " +
                    "(${target.length() / 1024} KB) in $exportMs ms, heap limit ${runtime.maxMemory() shr 20} MB",
            )
            assertEquals(6000 to 4000, maxOf(bounds.outWidth, bounds.outHeight) to minOf(bounds.outWidth, bounds.outHeight))
            assertEquals("ZV-E10", ExifInterface(target).getAttribute(ExifInterface.TAG_MODEL))
        } finally {
            target.delete()
        }
    }
}
