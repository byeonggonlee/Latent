package com.byeonggon.cameramanager.enhance

/**
 * A part of a picture at its full size, for looking at fine detail: [image] lies [left] pixels across
 * and [top] rows down the whole picture, which is [frameWidth] by [frameHeight] pixels, upright.
 */
class PictureRegion(val image: LinearImage, val left: Int, val top: Int, val frameWidth: Int, val frameHeight: Int) {
    companion object {
        /**
         * Where a region of at most [size] pixels each way lies in a picture of [width] by [height]
         * pixels when it is centred on ([centreX], [centreY]), given as fractions of the picture; moved
         * inwards where it would reach beyond an edge. Returns left, top, width and height.
         */
        fun bounds(width: Int, height: Int, centreX: Float, centreY: Float, size: Int): IntArray {
            val regionWidth = minOf(size, width)
            val regionHeight = minOf(size, height)
            val left = (centreX * width - regionWidth / 2f).toInt().coerceIn(0, width - regionWidth)
            val top = (centreY * height - regionHeight / 2f).toInt().coerceIn(0, height - regionHeight)
            return intArrayOf(left, top, regionWidth, regionHeight)
        }

        /**
         * Where an upright rectangle (left, top, width, height) lies in the picture as it is stored,
         * [storedWidth] by [storedHeight] pixels, if [uprightTurns] quarter turns clockwise bring the
         * stored picture upright. Returns left, top, width and height again.
         */
        fun asStored(upright: IntArray, uprightTurns: Int, storedWidth: Int, storedHeight: Int): IntArray {
            val (left, top, width, height) = upright
            return when (uprightTurns.mod(4)) {
                1 -> intArrayOf(top, storedHeight - left - width, height, width)
                2 -> intArrayOf(storedWidth - left - width, storedHeight - top - height, width, height)
                3 -> intArrayOf(storedWidth - top - height, left, height, width)
                else -> upright
            }
        }
    }
}
