package com.byeonggon.latent.enhance

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap

/**
 * The picture that [develop] makes, drawn into a bitmap with [edit] applied; [scale] is as for
 * [DetailFilter]. A full-size picture takes up much of the app's memory more than once on the way,
 * so each stage lets go of what the one before it needed: the linear picture is dropped once its
 * tones are rendered, and its detail is worked out straight into the bitmap.
 */
internal fun renderedBitmap(edit: PhotoEdit, scale: Float, develop: () -> LinearImage): Bitmap {
    val toned = toned(edit, develop)
    return bitmapOf(toned.pixels, toned.width, toned.height, DetailFilter(edit.adjustments, scale))
}

/** A bitmap of [pixels] with their detail changed by [detail]. */
internal fun bitmapOf(pixels: IntArray, width: Int, height: Int, detail: DetailFilter): Bitmap {
    if (!detail.isNeeded) return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    val bitmap = createBitmap(width, height)
    detail.apply(pixels, width, height) { top, rows, band -> bitmap.setPixels(band, 0, width, 0, top, width, rows) }
    return bitmap
}

/** Changes the detail of a finished picture in place. */
internal fun DetailFilter.applyTo(bitmap: Bitmap) {
    if (!isNeeded) return
    val width = bitmap.width
    val pixels = IntArray(width * bitmap.height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, bitmap.height)
    apply(pixels, width, bitmap.height) { top, rows, band -> bitmap.setPixels(band, 0, width, 0, top, width, rows) }
}

private class Toned(val width: Int, val height: Int, val pixels: IntArray)

// A function of its own, so that the linear picture is out of reach, and can be freed, once it returns.
private fun toned(edit: PhotoEdit, develop: () -> LinearImage): Toned {
    val image = develop()
    val pixels = ToneRenderer(edit, image.downscaled(ToneRenderer.OVERVIEW_EDGE)).render(image)
    return Toned(image.width, image.height, pixels)
}
