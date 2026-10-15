package com.byeonggon.latent.data

import com.byeonggon.latent.enhance.PhotoEnhancer

/**
 * What the JPEGs sent to other apps or saved as copies are like: how finely they are compressed
 * ([quality], 1 to 100), and whether they leave the place out ([removeLocation], for shared copies only).
 */
data class ExportOptions(
    val quality: Int = PhotoEnhancer.JPEG_QUALITY,
    val removeLocation: Boolean = false,
)
