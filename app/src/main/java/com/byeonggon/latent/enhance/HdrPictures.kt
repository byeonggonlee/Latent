package com.byeonggon.latent.enhance

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pictures that show brighter than white on an HDR screen: Ultra HDR JPEGs, with their gain map, and HEIF pictures stored
 * with the HLG or PQ transfer of HDR video. Shown as they are; editing works on the ordinary picture only.
 */
object HdrPictures {
    /** Whether [file] holds such a picture, as far as its header tells; nothing of the picture itself is decoded. */
    fun isHdr(file: File): Boolean = UltraHdr.hasGainMap(file) || isHdrColorSpace(storedColorSpace(file))

    /** Whether pixels in [space] reach beyond white: those of the HLG and PQ transfers. */
    internal fun isHdrColorSpace(space: ColorSpace?): Boolean =
        space == ColorSpace.get(ColorSpace.Named.BT2020_HLG) || space == ColorSpace.get(ColorSpace.Named.BT2020_PQ)

    private fun storedColorSpace(file: File): ColorSpace? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outColorSpace
    }

    /**
     * The picture in [file], upright and no larger than [maxEdge] on its long side, with what makes it HDR kept: its gain
     * map, or its HDR transfer in ten bits. The system's decoder keeps both, where reading pixels into an ordinary
     * picture would not.
     */
    @Throws(IOException::class)
    fun decode(file: File, maxEdge: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val longSide = max(info.size.width, info.size.height)
            if (longSide > maxEdge) {
                val scale = maxEdge.toFloat() / longSide
                decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
            }
        }

    /** Whether [bitmap] carries what an HDR screen shows brighter than white. */
    fun isHdr(bitmap: Bitmap): Boolean = bitmap.hasGainmap() || isHdrColorSpace(bitmap.colorSpace)

    /** Lets [activity]'s window show HDR, or ordinary pictures only: an HDR picture is shown as such only in that mode. */
    fun showHdr(activity: Activity, on: Boolean) {
        activity.window.colorMode = if (on) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
    }
}
