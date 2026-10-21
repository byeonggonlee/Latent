package com.byeonggon.latent.enhance

/**
 * How a picture is stored compared with how it is seen, as Exif's orientation (1 to 8) says. To bring the stored
 * picture upright it is first mirrored left to right, if [mirrored], then turned clockwise by [quarterTurns]: the
 * reading of AndroidX's `ExifInterface.isFlipped` and `rotationDegrees`, by which the thumbnails are turned.
 */
data class Orientation(val mirrored: Boolean, val quarterTurns: Int) {
    val isUpright: Boolean get() = !mirrored && quarterTurns.mod(4) == 0

    /** Whether the upright picture is the stored one on its side, so that its width is the stored height. */
    val isSideways: Boolean get() = quarterTurns.mod(2) == 1

    companion object {
        val UPRIGHT = Orientation(mirrored = false, quarterTurns = 0)

        /** What an Exif orientation stands for; any other value, or none, is upright. */
        fun ofExif(value: Int): Orientation = when (value) {
            2 -> Orientation(mirrored = true, quarterTurns = 0)
            3 -> Orientation(mirrored = false, quarterTurns = 2)
            4 -> Orientation(mirrored = true, quarterTurns = 2)
            5 -> Orientation(mirrored = true, quarterTurns = 3)
            6 -> Orientation(mirrored = false, quarterTurns = 1)
            7 -> Orientation(mirrored = true, quarterTurns = 1)
            8 -> Orientation(mirrored = false, quarterTurns = 3)
            else -> UPRIGHT
        }
    }
}
