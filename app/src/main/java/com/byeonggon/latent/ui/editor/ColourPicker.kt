package com.byeonggon.latent.ui.editor

import android.graphics.Bitmap

/** A temporary colour choice; no edit is changed until Apply. The preview and cursor use the whole upright photo. */
data class ColourPickerState(
    val preparing: Boolean = true,
    val preview: Bitmap? = null,
    val point: Pair<Float, Float>? = null,
    val hue: Float? = null,
    val colour: Int? = null,
    val invalid: Boolean = false,
)
