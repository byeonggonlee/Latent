package com.byeonggon.latent.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LightroomPresetTest {
    private fun parse(xml: String) = LightroomPreset.parse(xml.byteInputStream())

    /** A preset as Lightroom writes it: the settings as attributes, the name and the curve as elements. */
    private val preset = """
        <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 7.0-c000">
         <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <rdf:Description rdf:about=""
            xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
           crs:PresetType="Normal"
           crs:UUID="8F5B3C4E-1111-2222-3333-444455556666"
           crs:SupportsAmount="True"
           crs:Version="14.0"
           crs:ProcessVersion="11.0"
           crs:Exposure2012="+0.40"
           crs:Contrast2012="+25"
           crs:Highlights2012="-30"
           crs:Shadows2012="+20"
           crs:Whites2012="+10"
           crs:Blacks2012="-5"
           crs:Texture="+10"
           crs:Clarity2012="+15"
           crs:Dehaze="+5"
           crs:Vibrance="+20"
           crs:Saturation="-10"
           crs:IncrementalTemperature="+12"
           crs:IncrementalTint="-4"
           crs:PostCropVignetteAmount="-20"
           crs:GrainAmount="25"
           crs:Sharpness="60"
           crs:HueAdjustmentOrange="+10"
           crs:SaturationAdjustmentBlue="-30"
           crs:LuminanceAdjustmentGreen="+15"
           crs:SplitToningShadowHue="220"
           crs:SplitToningShadowSaturation="30"
           crs:SplitToningHighlightHue="40"
           crs:SplitToningHighlightSaturation="20"
           crs:SplitToningBalance="-20"
           crs:ColorGradeMidtoneHue="30"
           crs:ColorGradeMidtoneSat="10"
           crs:Temperature="5500"
           crs:LensProfileEnable="1"
           crs:RedHue="+10"
           crs:HasSettings="True">
           <crs:Name>
            <rdf:Alt>
             <rdf:li xml:lang="x-default">Warm Sunset</rdf:li>
            </rdf:Alt>
           </crs:Name>
           <crs:ShortName>
            <rdf:Alt>
             <rdf:li xml:lang="x-default"/>
            </rdf:Alt>
           </crs:ShortName>
           <crs:ToneCurvePV2012>
            <rdf:Seq>
             <rdf:li>0, 0</rdf:li>
             <rdf:li>64, 51</rdf:li>
             <rdf:li>192, 204</rdf:li>
             <rdf:li>255, 255</rdf:li>
            </rdf:Seq>
           </crs:ToneCurvePV2012>
           <crs:ToneCurvePV2012Red>
            <rdf:Seq>
             <rdf:li>0, 0</rdf:li>
             <rdf:li>255, 255</rdf:li>
            </rdf:Seq>
           </crs:ToneCurvePV2012Red>
          </rdf:Description>
         </rdf:RDF>
        </x:xmpmeta>
        <?xpacket end="w"?>
    """.trimIndent()

    @Test
    fun readsTheSettingsOfAPresetOntoTheScalesOfThisApp() {
        val read = parse(preset)!!

        assertEquals("Warm Sunset", read.name)
        val adjustments = read.edit.adjustments
        assertEquals(0.4f, adjustments.exposure, 1e-5f)
        assertEquals(0.25f, adjustments.contrast, 1e-5f)
        assertEquals(-0.3f, adjustments.highlights, 1e-5f)
        assertEquals(0.2f, adjustments.shadows, 1e-5f)
        assertEquals(0.1f, adjustments.whites, 1e-5f)
        assertEquals(-0.05f, adjustments.blacks, 1e-5f)
        assertEquals(0.1f, adjustments.texture, 1e-5f)
        assertEquals(0.15f, adjustments.clarity, 1e-5f)
        assertEquals(0.05f, adjustments.dehaze, 1e-5f)
        assertEquals(0.2f, adjustments.vibrance, 1e-5f)
        assertEquals(-0.1f, adjustments.saturation, 1e-5f)
        assertEquals(0.12f, adjustments.temperature, 1e-5f)
        assertEquals(-0.04f, adjustments.tint, 1e-5f)
        assertEquals(-0.2f, adjustments.vignette, 1e-5f)
        assertEquals(0.25f, adjustments.grain, 1e-5f)
        assertEquals(0.4f, adjustments.sharpening, 1e-5f)
    }

    @Test
    fun readsTheColourMixerGradingAndCurves() {
        val edit = parse(preset)!!.edit

        assertEquals(0.1f, edit.mixer.hue[MixerBand.ORANGE.ordinal], 1e-5f)
        assertEquals(-0.3f, edit.mixer.saturation[MixerBand.BLUE.ordinal], 1e-5f)
        assertEquals(0.15f, edit.mixer.luminance[MixerBand.GREEN.ordinal], 1e-5f)
        assertEquals(0f, edit.mixer.hue[MixerBand.RED.ordinal], 0f)
        assertEquals(Tint(220f, 0.3f), edit.grading.shadows)
        assertEquals(Tint(40f, 0.2f), edit.grading.highlights)
        assertEquals(Tint(30f, 0.1f), edit.grading.midtones)
        assertEquals(-0.2f, edit.grading.balance, 1e-5f)
        assertEquals(listOf(0f, 64 / 255f, 192 / 255f, 1f), edit.curves.all.points.map { it.x })
        assertEquals(51 / 255f, edit.curves.all.points[1].y, 1e-5f)
        // A curve that is the diagonal is no curve.
        assertTrue(edit.curves.red.isStraight)
        assertTrue(edit.changesColors)
    }

    @Test
    fun saysWhatItCouldNotTakeAndCountsWhatItDid() {
        val read = parse(preset)!!

        assertEquals(setOf(Unsupported.WHITE_BALANCE, Unsupported.LENS, Unsupported.CALIBRATION), read.skipped)
        // 17 adjustments, 3 mixer values, 3 + 1 grading hues and amounts, the balance, one curve.
        assertTrue("used ${read.used}", read.used in 25..32)
    }

    @Test
    fun readsSettingsWrittenAsElementsAndUnderAnotherPrefix() {
        val read = parse(
            """
            <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
             <rdf:Description rdf:about="" xmlns:cr="http://ns.adobe.com/camera-raw-settings/1.0/" cr:Contrast2012="+50">
              <cr:Exposure2012>-1.25</cr:Exposure2012>
              <cr:ConvertToGrayscale>True</cr:ConvertToGrayscale>
              <cr:GrayMixerBlue>-40</cr:GrayMixerBlue>
             </rdf:Description></rdf:RDF></x:xmpmeta>
            """.trimIndent(),
        )!!

        assertEquals(-1.25f, read.edit.adjustments.exposure, 1e-5f)
        assertEquals(0.5f, read.edit.adjustments.contrast, 1e-5f)
        assertTrue(read.edit.monochrome)
        // In black and white the mix of the colours is the gray mixer's.
        assertEquals(-0.4f, read.edit.mixer.luminance[MixerBand.BLUE.ordinal], 1e-5f)
        assertNull(read.name)
    }

    @Test
    fun holdsValuesToTheRangeOfTheSliders() {
        val edit = parse("""<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" crs:Exposure2012="+5.0" crs:Contrast2012="-250" crs:GrainAmount="-5" crs:Sharpness="300"/></rdf:RDF>""")!!.edit

        assertEquals(3f, edit.adjustments.exposure, 0f)
        assertEquals(-1f, edit.adjustments.contrast, 0f)
        assertEquals(0f, edit.adjustments.grain, 0f)
        assertEquals(1f, edit.adjustments.sharpening, 0f)
    }

    @Test
    fun doesNotTakeFilesThatAreNotSettings() {
        assertNull(parse("not xml at all"))
        assertNull(parse("""<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about=""/></rdf:RDF></x:xmpmeta>"""))
        assertNull(parse(""))
        // A preset of nothing but zeroes is a preset, though an empty one.
        val empty = parse("""<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" crs:Contrast2012="0" crs:Name="x"/></rdf:RDF>""")
        assertNotNull(empty)
        assertEquals(0, empty!!.used)
        assertTrue(!empty.edit.changesColors)
    }
}
