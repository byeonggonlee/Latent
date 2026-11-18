package com.byeonggon.latent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.decode.DataSource
import coil3.memory.MemoryCache
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.byeonggon.latent.data.Thumbnails
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.enhance.Adjustments
import com.byeonggon.latent.enhance.PhotoEdit
import com.byeonggon.latent.raw.RawCache
import com.byeonggon.latent.raw.SyntheticDng
import com.byeonggon.latent.ui.common.Thumbnail
import com.byeonggon.latent.ui.common.ThumbnailFetcher
import com.byeonggon.latent.ui.common.rememberMediaModel
import com.byeonggon.latent.ui.common.rememberThumbnailModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbnailModelKeyTest {
    @get:Rule
    val temp = TemporaryFolder()

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun buildLoader(thumbnails: Thumbnails): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizeBytes(16L * 1024 * 1024).build() }
            .components {
                add(ThumbnailFetcher.Factory(thumbnails))
                add(BitmapFactoryDecoder.Factory())
            }
            .build()

    private fun executeSuccess(loader: ImageLoader, request: ImageRequest): SuccessResult = runBlocking {
        when (val result = loader.execute(request.newBuilder().size(200, 200).build())) {
            is SuccessResult -> result
            is ErrorResult -> throw AssertionError("ImageLoader failed: ${result.throwable}", result.throwable)
        }
    }

    @Test
    fun rememberThumbnailModelBustsCoilMemoryAndDiskCacheForRawAcrossEditsAndReset() {
        val rawFile = temp.newFile("TEST_RAW.dng").apply {
            writeBytes(SyntheticDng.file(red = 400, green = 250, blue = 200))
        }
        val thumbnails = Thumbnails(context, RawCache())
        val loader = buildLoader(thumbnails)

        val item0 = MediaItemEntity(
            id = 1L,
            path = rawFile.path,
            fileName = rawFile.name,
            kind = MediaKind.RAW,
            sizeBytes = rawFile.length(),
            capturedAt = 1_000L,
            importedAt = 1_000L,
            origin = MediaOrigin.LIBRARY,
        )
        var current by mutableStateOf(item0)
        lateinit var request: ImageRequest
        compose.setContent { request = rememberThumbnailModel(current) }
        compose.waitForIdle()

        assertEquals(Thumbnail(rawFile.path, rawFile.length(), null, applyEdit = true, raw = true), request.data)
        val result0First = executeSuccess(loader, request)
        assertEquals(DataSource.DISK, result0First.dataSource)
        val result0Cached = executeSuccess(loader, request)
        assertEquals(DataSource.MEMORY_CACHE, result0Cached.dataSource)

        // First edit (+1.8 exposure): must bust both memoryCacheKey and Thumbnail data without size change.
        val edit1Json = PhotoEdit(Adjustments(exposure = 1.8f)).toJson()
        val req0Key = request.memoryCacheKey
        current = item0.copy(edit = edit1Json)
        compose.waitForIdle()

        assertNotEquals(req0Key, request.memoryCacheKey)
        assertEquals(Thumbnail(rawFile.path, rawFile.length(), edit1Json, applyEdit = true, raw = true), request.data)
        val result1 = executeSuccess(loader, request)
        assertEquals(DataSource.DISK, result1.dataSource)
        val luma1 = meanLuminance(result1.image.toBitmap())

        // Second continuous edit (-1.8 exposure): must bust again and render darker.
        val edit2Json = PhotoEdit(Adjustments(exposure = -1.8f)).toJson()
        val req1Key = request.memoryCacheKey
        current = item0.copy(edit = edit2Json)
        compose.waitForIdle()

        assertNotEquals(req1Key, request.memoryCacheKey)
        val result2 = executeSuccess(loader, request)
        assertEquals(DataSource.DISK, result2.dataSource)
        val luma2 = meanLuminance(result2.image.toBitmap())
        assertTrue("Second RAW edit should be darker ($luma2 < $luma1)", luma2 + 5.0 < luma1)

        // Reset back to unedited: returns to the unedited cache key.
        current = item0.copy(edit = null)
        compose.waitForIdle()
        assertEquals(req0Key, request.memoryCacheKey)
    }

    @Test
    fun rememberThumbnailAndMediaModelsBustMemoryCacheEvenWhenLibraryJpegHasIdenticalSize() {
        val jpgFile = temp.newFile("SAME_SIZE.jpg").apply {
            writeBytes(jpegBytes(Color.rgb(50, 50, 50)))
        }
        val fixedSize = 12_345L
        val thumbnails = Thumbnails(context, RawCache())
        val loader = buildLoader(thumbnails)

        val item0 = MediaItemEntity(
            id = 2L,
            path = jpgFile.path,
            fileName = jpgFile.name,
            kind = MediaKind.PHOTO,
            sizeBytes = fixedSize,
            capturedAt = 2_000L,
            importedAt = 2_000L,
            origin = MediaOrigin.LIBRARY,
        )
        var current by mutableStateOf(item0)
        lateinit var thumbReq: ImageRequest
        lateinit var mediaReq: ImageRequest
        compose.setContent {
            thumbReq = rememberThumbnailModel(current)
            mediaReq = rememberMediaModel(current)
        }
        compose.waitForIdle()

        assertEquals(Thumbnail(jpgFile.path, fixedSize, null, applyEdit = false, raw = false), thumbReq.data)
        val res0 = executeSuccess(loader, thumbReq)
        val luma0 = meanLuminance(res0.image.toBitmap())

        // Simulate overwriting library JPEG on disk with an edited file that happened to keep identical byte size.
        jpgFile.writeBytes(jpegBytes(Color.rgb(210, 210, 210)))
        val thumbKey0 = thumbReq.memoryCacheKey
        val mediaKey0 = mediaReq.memoryCacheKey
        val editJson = PhotoEdit(Adjustments(exposure = 1.0f)).toJson()
        current = item0.copy(edit = editJson)
        compose.waitForIdle()

        assertNotEquals(thumbKey0, thumbReq.memoryCacheKey)
        assertNotEquals(mediaKey0, mediaReq.memoryCacheKey)
        assertEquals(Thumbnail(jpgFile.path, fixedSize, editJson, applyEdit = false, raw = false), thumbReq.data)
        val res1 = executeSuccess(loader, thumbReq)
        assertEquals(DataSource.DISK, res1.dataSource)
        val luma1 = meanLuminance(res1.image.toBitmap())
        assertTrue("Edited Library JPEG must not reuse stale cache ($luma1 > $luma0)", luma1 > luma0 + 50.0)
    }

    @Test
    fun failedEditedThumbnailRenderErrorsCleanlyWithoutPopulatingMemoryCache() {
        val missingFile = File(temp.root, "NO_SUCH_FILE.dng")
        val thumbnails = Thumbnails(context, RawCache())
        val loader = buildLoader(thumbnails)

        val item = MediaItemEntity(
            id = 3L,
            path = missingFile.path,
            fileName = missingFile.name,
            kind = MediaKind.RAW,
            sizeBytes = 512L,
            capturedAt = 3_000L,
            importedAt = 3_000L,
            edit = PhotoEdit(Adjustments(exposure = 1.0f)).toJson(),
            origin = MediaOrigin.LIBRARY,
        )
        lateinit var request: ImageRequest
        compose.setContent { request = rememberThumbnailModel(item) }
        compose.waitForIdle()

        val result = runBlocking { loader.execute(request.newBuilder().size(200, 200).build()) }
        assertTrue("Expected ErrorResult when edited render fails, got $result", result is ErrorResult)
        assertTrue(loader.memoryCache?.keys?.isEmpty() != false)
        assertFalse(thumbnails.fileFor(item.path, item.sizeBytes, item.edit).exists())
    }

    @Test
    fun phonePhotoAndHeifEditsMarkApplyEditAndSeparateRawFlag() {
        val editJson = PhotoEdit(Adjustments(contrast = 0.4f)).toJson()
        val phoneJpeg = MediaItemEntity(
            id = 4L,
            path = "/sdcard/DCIM/Camera/P.jpg",
            fileName = "P.jpg",
            kind = MediaKind.PHOTO,
            sizeBytes = 1000L,
            capturedAt = 1L,
            importedAt = 1L,
            edit = editJson,
            origin = MediaOrigin.PHONE,
        )
        val heif = MediaItemEntity(
            id = 5L,
            path = "/sdcard/DCIM/Latent/H.heic",
            fileName = "H.heic",
            kind = MediaKind.PHOTO,
            sizeBytes = 2000L,
            capturedAt = 1L,
            importedAt = 1L,
            edit = editJson,
            origin = MediaOrigin.LIBRARY,
        )
        var current by mutableStateOf(phoneJpeg)
        lateinit var request: ImageRequest
        compose.setContent { request = rememberThumbnailModel(current) }
        compose.waitForIdle()
        assertEquals(Thumbnail(phoneJpeg.path, 1000L, editJson, applyEdit = true, raw = false), request.data)

        current = heif
        compose.waitForIdle()
        assertEquals(Thumbnail(heif.path, 2000L, editJson, applyEdit = true, raw = false), request.data)
    }

    private fun meanLuminance(bitmap: Bitmap): Double {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var sum = 0.0
        for (pixel in pixels) {
            sum += 0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)
        }
        return sum / pixels.size
    }

    private fun jpegBytes(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }
}
