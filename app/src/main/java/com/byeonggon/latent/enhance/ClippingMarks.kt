package com.byeonggon.latent.enhance

import android.graphics.Bitmap

/**
 * Marks where a picture has lost its detail to white or to black, the way a camera's blinking
 * highlights do: red where any colour is at its brightest, blue where all of them are at their darkest.
 */
object ClippingMarks {
    const val HIGHLIGHT = 0xFFFF2020.toInt()
    const val SHADOW = 0xFF2060FF.toInt()

    private const val BRIGHTEST = 254
    private const val DARKEST = 1

    /** Paints the marks over [pixels] (ARGB) in place. Returns whether anything was marked. */
    fun mark(pixels: IntArray): Boolean {
        var marked = false
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val red = (pixel ushr 16) and 0xFF
            val green = (pixel ushr 8) and 0xFF
            val blue = pixel and 0xFF
            val mark = when {
                red >= BRIGHTEST || green >= BRIGHTEST || blue >= BRIGHTEST -> HIGHLIGHT
                red <= DARKEST && green <= DARKEST && blue <= DARKEST -> SHADOW
                else -> continue
            }
            pixels[index] = mark
            marked = true
        }
        return marked
    }

    /** [bitmap] with the marks painted over it: itself if it can be changed, else a copy. */
    fun markOn(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val pixels = IntArray(width * bitmap.height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, bitmap.height)
        if (!mark(pixels)) return bitmap
        // A bitmap made straight from an array of pixels cannot be changed.
        val target = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)
        target.setPixels(pixels, 0, width, 0, 0, width, bitmap.height)
        return target
    }
}
