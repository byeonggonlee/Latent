package com.byeonggon.cameramanager.raw

import com.byeonggon.cameramanager.enhance.PhotoEdit
import com.byeonggon.cameramanager.enhance.render
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Checks the development of real camera files. Photos are personal, so none are checked in: set
 * ARW_SAMPLE_DIR to a folder of .ARW files, each next to the camera's own .JPG of the same shot.
 *
 * The camera straightens its JPEGs with the same lens profile it stores in the raw file, which makes
 * them the reference: after our correction, every detail must sit where the camera put it.
 * Developed pictures are written to an "out" folder beside the samples for a look.
 */
class RawSampleTest {
    private class Picture(val width: Int, val height: Int, val pixels: IntArray)

    private val sampleDir = System.getenv("ARW_SAMPLE_DIR")?.let(::File)?.takeIf { it.isDirectory }

    @Test
    fun straightensLikeTheCamera() {
        assumeTrue(sampleDir != null)
        val out = File(sampleDir, "out").apply { mkdirs() }
        for (arw in sampleDir!!.listFiles { file -> file.extension.equals("ARW", ignoreCase = true) }!!.sorted()) {
            val image = SonyArw.read(arw.readBytes())
            val linear = RawDeveloper.developPreview(image)
            LensCorrection(image.lens!!, linear.width, linear.height).straighten(linear)
            val straightened = Picture(linear.width, linear.height, linear.render(PhotoEdit()))
            write(straightened, File(out, arw.nameWithoutExtension + ".png"))

            val jpeg = File(sampleDir, arw.nameWithoutExtension + ".JPG").takeIf { it.exists() }?.let(ImageIO::read) ?: continue
            val camera = Picture(linear.width, linear.height, IntArray(linear.width * linear.height) { index ->
                jpeg.getRGB(index % linear.width * 2, index / linear.width * 2)
            })
            val shifts = outwardShifts(straightened, camera)
            println("${arw.name}: off by " + shifts.joinToString { "%+.2f".format(it) } + " px from the centre outwards")
            assertTrue("${arw.name} is off by $shifts", shifts.all { abs(it) <= 1.5f })
        }
    }

    /**
     * How far, outwards from the centre, patches of [camera] sit from the same detail in [ours]: the
     * median for each tenth of the way to the corners, to a fraction of a pixel.
     */
    private fun outwardShifts(ours: Picture, camera: Picture): List<Float> {
        val w = ours.width
        val h = ours.height
        val a = brightness(ours)
        val b = brightness(camera)
        val centerX = (w - 1) / 2f
        val centerY = (h - 1) / 2f
        val bands = sortedMapOf<Int, MutableList<Float>>()
        for (gridY in 0 until 11) for (gridX in 0 until 15) {
            val x = (w * (gridX + 0.5f) / 15).toInt().coerceIn(PATCH + SEARCH + 1, w - PATCH - SEARCH - 2)
            val y = (h * (gridY + 0.5f) / 11).toInt().coerceIn(PATCH + SEARCH + 1, h - PATCH - SEARCH - 2)
            fun score(dx: Int, dy: Int) = correlation(b, x, y, a, x + dx, y + dy, w)
            var best = -2.0
            var shiftX = 0
            var shiftY = 0
            for (dy in -SEARCH..SEARCH) for (dx in -SEARCH..SEARCH) {
                val found = score(dx, dy)
                if (found > best) {
                    best = found
                    shiftX = dx
                    shiftY = dy
                }
            }
            // Featureless patches, sky mostly, match anywhere and say nothing.
            if (best < 0.93 || abs(shiftX) == SEARCH || abs(shiftY) == SEARCH) continue
            // A parabola through the neighbouring scores places the best match between pixels.
            fun between(lower: Double, upper: Double) = ((lower - upper) / (2 * (lower - 2 * best + upper))).toFloat().coerceIn(-1f, 1f)
            val exactX = shiftX + between(score(shiftX - 1, shiftY), score(shiftX + 1, shiftY))
            val exactY = shiftY + between(score(shiftX, shiftY - 1), score(shiftX, shiftY + 1))
            val fromCenter = hypot(x - centerX, y - centerY)
            if (fromCenter < 1f) continue
            val outwards = (exactX * (x - centerX) + exactY * (y - centerY)) / fromCenter
            bands.getOrPut((fromCenter / hypot(centerX, centerY) * 10).toInt()) { ArrayList() } += outwards
        }
        return bands.values.map { shifts -> shifts.sorted()[shifts.size / 2] }
    }

    private fun correlation(a: FloatArray, ax: Int, ay: Int, b: FloatArray, bx: Int, by: Int, width: Int): Double {
        var sumA = 0.0
        var sumB = 0.0
        var sumAA = 0.0
        var sumBB = 0.0
        var sumAB = 0.0
        var count = 0
        for (y in -PATCH..PATCH step 2) for (x in -PATCH..PATCH step 2) {
            val va = a[(ay + y) * width + ax + x].toDouble()
            val vb = b[(by + y) * width + bx + x].toDouble()
            sumA += va
            sumB += vb
            sumAA += va * va
            sumBB += vb * vb
            sumAB += va * vb
            count++
        }
        val spread = sqrt((sumAA - sumA * sumA / count) * (sumBB - sumB * sumB / count))
        return if (spread <= 0) -1.0 else (sumAB - sumA * sumB / count) / spread
    }

    private fun brightness(image: Picture) = FloatArray(image.pixels.size) { index ->
        val pixel = image.pixels[index]
        0.3f * ((pixel shr 16) and 0xFF) + 0.6f * ((pixel shr 8) and 0xFF) + 0.1f * (pixel and 0xFF)
    }

    private fun write(image: Picture, file: File) {
        val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        buffered.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
        ImageIO.write(buffered, "png", file)
    }

    private companion object {
        const val PATCH = 40
        const val SEARCH = 6
    }
}
