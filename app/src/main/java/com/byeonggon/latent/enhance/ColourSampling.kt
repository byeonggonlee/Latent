package com.byeonggon.latent.enhance

import kotlin.math.max
import kotlin.math.min

/** A hue and its display colour, sampled before the selected local mask changes the picture. */
internal data class PickedColour(val hue: Float, val argb: Int)

/** The picker owns these arrays; once published, neither the renderer nor the editor changes them. */
internal class ColourPickPlane(val picture: ShownPicture) {
    fun sample(x: Float, y: Float): PickedColour? {
        if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return null
        val centreX = (x * picture.width).toInt().coerceAtMost(picture.width - 1)
        val centreY = (y * picture.height).toInt().coerceAtMost(picture.height - 1)
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var count = 0
        for (row in max(0, centreY - 2)..min(picture.height - 1, centreY + 2)) {
            for (column in max(0, centreX - 2)..min(picture.width - 1, centreX + 2)) {
                val at = row * picture.width + column
                val r = picture.red[at]
                val g = picture.green[at]
                val b = picture.blue[at]
                if (!r.isFinite() || !g.isFinite() || !b.isFinite()) return null
                red += max(0f, r)
                green += max(0f, g)
                blue += max(0f, b)
                count++
            }
        }
        val r = (red / count).toFloat()
        val g = (green / count).toFloat()
        val b = (blue / count).toFloat()
        val most = maxOf(r, g, b)
        val chroma = most - minOf(r, g, b)
        if (most <= 0f || chroma / most <= LocalEdit.LEAST_VIVID) return null
        return PickedColour(colourHue(r, g, b, most, chroma), displayPixel(r, g, b))
    }

    fun pixels(): IntArray = IntArray(picture.width * picture.height) { displayPixel(picture.red[it], picture.green[it], picture.blue[it]) }
}

/** The same hue coordinate that a colour-range mask tests; callers exclude achromatic inputs. */
internal fun colourHue(red: Float, green: Float, blue: Float, most: Float, chroma: Float): Float {
    val sector = when {
        most == red -> (green - blue) / chroma
        most == green -> 2f + (blue - red) / chroma
        else -> 4f + (red - green) / chroma
    }
    return (if (sector < 0f) sector + 6f else sector) * 60f
}

private fun displayPixel(red: Float, green: Float, blue: Float): Int {
    fun byte(value: Float) = (value * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (0xff shl 24) or (byte(red) shl 16) or (byte(green) shl 8) or byte(blue)
}
