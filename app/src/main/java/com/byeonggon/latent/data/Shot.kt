package com.byeonggon.latent.data

import java.io.File

/**
 * The shot a file was written for, as a key: its folder and its name without the extension, in lower case, which a
 * raw file and the JPEG the camera wrote with it share. A Pixel tells the two apart after the shot's name
 * (`PXL_20261021_101500123.RAW-02.ORIGINAL.dng` and `PXL_20261021_101500123.RAW-01.COVER.jpg`, or `.RAW-01.MP.COVER.jpg`
 * for a motion photo), so for such a name the key ends before `.RAW-`.
 */
internal fun shotOf(file: File): String {
    val name = file.name.lowercase()
    val stem = PIXEL_RAW_PART.find(name)?.takeIf { it.range.first > 0 }?.let { name.substring(0, it.range.first) }
        ?: name.substringBeforeLast('.')
    return File(file.parentFile, stem).path
}

private val PIXEL_RAW_PART = Regex("""\.raw-\d+\.""")
