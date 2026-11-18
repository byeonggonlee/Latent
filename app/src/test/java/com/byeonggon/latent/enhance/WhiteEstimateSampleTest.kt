package com.byeonggon.latent.enhance

import com.byeonggon.latent.raw.ArwImage
import com.byeonggon.latent.raw.CanonCr2
import com.byeonggon.latent.raw.Dng
import com.byeonggon.latent.raw.RawDeveloper
import com.byeonggon.latent.raw.api.DecodeRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Auto's white balance for a raw, on the public samples of `docs/auto-colour-evaluation.md` as the editor develops
 * them. External photos stay out of Git; set CANON_7D_SAMPLE, CANON_7D_CHART_SAMPLE and PHONE_DNG_SAMPLE_DIR.
 */
class WhiteEstimateSampleTest {
    private fun overview(image: ArwImage) = RawDeveloper.developPreview(image).downscaled(ToneRenderer.OVERVIEW_EDGE)

    private fun canon(variable: String): LinearImage? = System.getenv(variable)?.let { path ->
        val file = File(path)
        overview(CanonCr2.decode(file.readBytes(), DecodeRequest(file)).legacyImage)
    }

    private fun samples(): Map<String, LinearImage> = buildMap {
        canon("CANON_7D_SAMPLE")?.let { put("EOS 7D, a tungsten-lit desk", it) }
        canon("CANON_7D_CHART_SAMPLE")?.let { put("EOS 7D, colour chart", it) }
        System.getenv("PHONE_DNG_SAMPLE_DIR")?.let { directory ->
            for (name in listOf("opencamera-2206.dng", "opencamera-1877.dng", "motioncam-0.dng", "motioncam-1.dng")) {
                put(name, overview(Dng.decode(File(directory, name).readBytes()).legacyImage))
            }
        }
    }

    @Test
    fun nudgesEverySampleOnlyALittle() {
        val samples = samples()
        assumeTrue("Set CANON_7D_SAMPLE, CANON_7D_CHART_SAMPLE or PHONE_DNG_SAMPLE_DIR to run the sample checks", samples.isNotEmpty())
        for ((name, image) in samples) {
            val estimates = WhiteEstimate.of(image)!!
            val (temperature, tint) = WhiteEstimate.nudge(image)
            println(
                "%-30s gray-world %+.2f %+.2f | gray-edge %+.2f %+.2f | white-patch %+.2f %+.2f | nudge %+.2f %+.2f".format(
                    name, *estimates.all.flatMap { listOf(WhiteEstimate.temperatureFor(it), WhiteEstimate.tintFor(it)) }.toTypedArray(),
                    temperature, tint,
                ),
            )
            assertTrue("$name: $temperature", temperature in -0.1f..0.1f)
            assertTrue("$name: $tint", tint in -0.05f..0.05f)
        }
    }

    @Test
    fun neverTurnsTheChartAwayFromItsGrays() {
        val chart = canon("CANON_7D_CHART_SAMPLE")
        assumeTrue("Set CANON_7D_CHART_SAMPLE to run the chart check", chart != null)
        // The light grays of the chart's bottom row, as the 288 by 192 overview has them.
        val grays = DoubleArray(3)
        for (centre in listOf(52, 92, 132)) {
            for (y in 136..146) {
                for (x in centre - 6..centre + 6) {
                    val index = y * chart!!.width + x
                    grays[0] += chart.red[index].toDouble()
                    grays[1] += chart.green[index].toDouble()
                    grays[2] += chart.blue[index].toDouble()
                }
            }
        }
        // As shot, the grays are a little blue and magenta: they ask for about +0.3 and -0.15.
        assertEquals(0.3f, WhiteEstimate.temperatureFor(grays), 0.05f)
        assertEquals(-0.15f, WhiteEstimate.tintFor(grays), 0.05f)

        val (temperature, tint) = WhiteEstimate.nudge(chart!!)
        assertTrue("$temperature", temperature >= 0f)
        assertTrue("$tint", tint <= 0f)
    }
}
