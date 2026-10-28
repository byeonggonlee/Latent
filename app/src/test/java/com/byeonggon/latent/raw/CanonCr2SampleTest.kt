package com.byeonggon.latent.raw

import com.byeonggon.latent.enhance.LinearImage

import com.byeonggon.latent.raw.api.DecodeRequest
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.ByteArrayInputStream
import org.junit.Assert.assertTrue
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.pow
import kotlin.math.sqrt

class CanonCr2SampleTest {
    @Test fun theSceneSamplesClippedHighlightsRemainNeutral() {
        val sample = System.getenv("CANON_7D_SAMPLE")
        assumeTrue("Requires the CC0 EOS 7D scene from raw.pixls.us #131", !sample.isNullOrBlank())
        val file = File(sample!!)
        val image = CanonCr2.decode(file.readBytes(), DecodeRequest(file)).legacyImage
        val developed = RawDeveloper.developPreview(image)
        var clippedBlocks = 0
        for (y in 0 until developed.height) for (x in 0 until developed.width) {
            val sensor = (image.cropTop + y * 2) * image.width + image.cropLeft + x * 2
            val sites = intArrayOf(sensor, sensor + 1, sensor + image.width, sensor + image.width + 1)
            if (!sites.all { image.sensor[it].toInt() in 12640..12651 }) continue
            val i = y * developed.width + x
            val rgb = intArrayOf(developed.red[i].toInt(), developed.green[i].toInt(), developed.blue[i].toInt())
            assertTrue("Clipped block at ($x, $y) must be white: ${rgb.contentToString()}",
                rgb.all { it >= LinearImage.MAX_LEVEL - 1 })
            clippedBlocks++
        }
        assertTrue("The fixed sample must exercise clipped highlights", clippedBlocks >= 100)
    }

    @Test fun theUnclippedChartKeepsItsDeclaredWhiteLevelInsteadOfItsBrightestPixel() {
        val sample = System.getenv("CANON_7D_CHART_SAMPLE")
        assumeTrue("Requires the CC0 EOS 7D chart from raw.pixls.us #1183", !sample.isNullOrBlank())
        val file = File(sample!!)
        val image = CanonCr2.decode(file.readBytes(), DecodeRequest(file)).legacyImage
        org.junit.Assert.assertEquals(12279, image.whiteLevel)
        var brightest = 0
        for (y in image.cropTop until image.cropTop + image.cropHeight) {
            for (x in image.cropLeft until image.cropLeft + image.cropWidth) {
                brightest = maxOf(brightest, image.sensor[y * image.width + x].toInt())
            }
        }
        assertTrue("The chart's active image does not reach the declared white level", brightest < image.whiteLevel)
    }

    @Test fun independentSceneApproximatesTheCameraPreview() {
        val sample = System.getenv("CANON_7D_SAMPLE")
        assumeTrue("Set CANON_7D_SAMPLE to the CC0 EOS 7D scene from raw.pixls.us #131", !sample.isNullOrBlank())
        val file=File(sample!!)
        val image=CanonCr2.decode(file.readBytes(),DecodeRequest(file)).legacyImage
        val developed=RawDeveloper.developPreview(image)
        val bytes=file.readBytes()
        val (tiff,first)=TiffDirectory.open(bytes)
        val main=TiffDirectory.read(tiff,first)
        val offset=main.int(0x111)!!; val length=main.int(0x117)!!
        val jpeg=ImageIO.read(ByteArrayInputStream(bytes,offset,length))
        var error=0.0
        var count=0
        fun lin(v:Int):Double { val x=v/255.0; return if(x<=.04045) x/12.92 else ((x+.055)/1.055).pow(2.4) }
        for (y in 20 until developed.height-20 step 9) for (x in 20 until developed.width-20 step 9) {
            val p=jpeg.getRGB(x*2,y*2)
            val display=intArrayOf(p shr 16 and 255,p shr 8 and 255,p and 255)
            if(display.minOrNull()!!<31 || display.maxOrNull()!!>219 || display.maxOrNull()!!-display.minOrNull()!!<10) continue
            val i=y*developed.width+x
            val ours=doubleArrayOf(developed.red[i].toDouble(),developed.green[i].toDouble(),developed.blue[i].toDouble())
            if(ours.maxOrNull()!! >= 16380 || ours.minOrNull()!! <= 20) continue
            val theirs=DoubleArray(3){lin(display[it])}; val os=ours.sum();val ts=theirs.sum()
            for(c in 0..2){val d=ours[c]/os-theirs[c]/ts;error+=d*d;count++}
        }
        val rms=sqrt(error/count)
        println("holdout chromatic RMS $rms across $count channel samples")
        assertTrue("holdout chromatic RMS $rms",count>1000 && rms<.09)
    }
}
