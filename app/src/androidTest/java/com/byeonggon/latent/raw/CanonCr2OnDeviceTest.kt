package com.byeonggon.latent.raw

import android.graphics.BitmapFactory
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.byeonggon.latent.data.Thumbnails
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.byeonggon.latent.enhance.PhotoEdit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run with -e cr2Sample <path>. Reads the public sample; output stays in app cache. */
@RunWith(AndroidJUnit4::class)
class CanonCr2OnDeviceTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val sample = arguments.getString("cr2Sample")?.let(::File)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun opensAndExportsAFullResolutionCanon7D() {
        assumeTrue(sample?.isFile == true)
        val output=File(context.cacheDir,"canon-cr2-on-device-test.jpg")
        try {
            val start=System.nanoTime()
            val raw=RawRenderer.read(sample!!)
            val preview=RawRenderer.proxy(raw,cells=2,lensCorrection=false)
            assertEquals(1296,preview.width); assertEquals(864,preview.height)
            val previewMs=(System.nanoTime()-start)/1_000_000
            val exportStart=System.nanoTime()
            RawRenderer.export(sample,output,PhotoEdit())
            val exportMs=(System.nanoTime()-exportStart)/1_000_000
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeFile(output.path,bounds)
            assertEquals(5184,bounds.outWidth); assertEquals(3456,bounds.outHeight)
            assertEquals("Canon EOS 7D",ExifInterface(output).getAttribute(ExifInterface.TAG_MODEL))
            Log.i("CanonCr2OnDevice", "preview in $previewMs ms, full export in $exportMs ms; heap ${Runtime.getRuntime().maxMemory() shr 20} MiB")
        } finally { output.delete() }
    }

    @Test fun loadsTheCameraPreviewForTheViewer() = runBlocking {
        assumeTrue(sample?.isFile == true)
        val result = SingletonImageLoader.get(context).execute(
            ImageRequest.Builder(context).data(sample!!).size(1600).build(),
        )
        assertTrue("Viewer cannot display the embedded CR2 preview: $result", result is SuccessResult)
    }

    @Test fun generatesAVisibleThumbnailForCanonCr2() = runBlocking {
        assumeTrue(sample?.isFile == true)
        val thumbnail=Thumbnails(context).of(sample!!,sample.length())
        assertNotNull("CR2 thumbnail",thumbnail)
        assertNotNull("CR2 thumbnail pixels",BitmapFactory.decodeFile(thumbnail!!.path))
    }
}
