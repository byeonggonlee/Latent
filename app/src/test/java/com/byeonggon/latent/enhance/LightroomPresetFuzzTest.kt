package com.byeonggon.latent.enhance

import com.byeonggon.latent.Mutator
import com.byeonggon.latent.fuzz
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Damaged and odd Lightroom presets and sidecars: each is read into a look, or is no preset (null); nothing is thrown,
 * and the look holds numbers only, never NaN or infinity, which the renderers would spread over the whole picture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LightroomPresetFuzzTest {
    private val seed = """
        <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
         <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <rdf:Description rdf:about="" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
           crs:Exposure2012="+0.40" crs:Contrast2012="+25" crs:Highlights2012="-30" crs:Vibrance="+20"
           crs:HueAdjustmentOrange="+10" crs:SplitToningShadowHue="220" crs:SplitToningShadowSaturation="30"
           crs:ConvertToGrayscale="False" crs:GrayMixerRed="-10" crs:Temperature="5500" crs:LensProfileEnable="1">
           <crs:Name><rdf:Alt><rdf:li xml:lang="x-default">Warm Sunset</rdf:li></rdf:Alt></crs:Name>
           <crs:Clarity2012>+15</crs:Clarity2012>
           <crs:ToneCurvePV2012><rdf:Seq><rdf:li>0, 0</rdf:li><rdf:li>64, 51</rdf:li><rdf:li>255, 255</rdf:li></rdf:Seq></crs:ToneCurvePV2012>
          </rdf:Description>
         </rdf:RDF>
        </x:xmpmeta>
        <?xpacket end="w"?>
    """.trimIndent()

    private fun parse(text: String) = LightroomPreset.parse(text.byteInputStream())

    private fun check(text: String) {
        val preset = parse(text) ?: return
        val stored = preset.edit.toJson()
        assertFalse(stored, "NaN" in stored || "Infinity" in stored)
    }

    @Test
    fun theSeedIsAPreset() {
        assertNotNull(parse(seed))
    }

    @Test
    fun damagedFilesAreReadOrAreNoPreset() = fuzz(seed = 41, cases = 2_000) { mutator ->
        check(String(mutator.bytes(seed.toByteArray()), Charsets.UTF_8))
    }

    @Test
    fun wellFormedFilesOfAnyShapeAreReadOrAreNoPreset() = fuzz(seed = 42, cases = 3_000) { mutator ->
        check(document(mutator))
    }

    @Test
    fun anElementInsideAListItemIsNoCrash() {
        // Well formed: a setting written as an element inside an item of another setting's list.
        check(
            """
            <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
             <rdf:Description xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" crs:Exposure2012="1">
              <crs:ToneCurvePV2012><rdf:Seq><rdf:li><crs:Contrast2012>10</crs:Contrast2012></rdf:li></rdf:Seq></crs:ToneCurvePV2012>
             </rdf:Description>
            </rdf:RDF></x:xmpmeta>
            """.trimIndent(),
        )
    }

    @Test
    fun aNumberThatIsNoneIsLeftOut() {
        val preset = parse(seed.replace("crs:Exposure2012=\"+0.40\"", "crs:Exposure2012=\"NaN\" crs:Saturation=\"Infinity\" crs:Dehaze=\"-1e999\""))!!

        assertFalse(preset.edit.adjustments.exposure.isNaN())
        check(seed.replace("+0.40", "NaN").replace("64, 51", "NaN, 51"))
    }

    /** A document of settings, lists and items nested every which way, with values of every kind. */
    private fun document(mutator: Mutator): String {
        val random = mutator.random
        val names = listOf("Exposure2012", "Contrast2012", "ToneCurvePV2012", "ToneCurvePV2012Red", "Name", "HueAdjustmentRed", "ConvertToGrayscale", "GrayMixerRed", "SplitToningShadowHue", "SplitToningShadowSaturation", "Look")
        val values = listOf("0", "+25", "-100", "NaN", "Infinity", "-Infinity", "1e999", "1e-999", "", " ", "True", "x", "0, 0", "255,255", "NaN, NaN", "1e30, -1e30", "64,", ",", "1,2,3", "&amp;")
        fun value() = values[random.nextInt(values.size)]
        fun element(depth: Int): String {
            if (depth > 6) return value()
            return when (random.nextInt(6)) {
                0 -> names[random.nextInt(names.size)].let { name -> "<crs:$name>${element(depth + 1)}</crs:$name>" }
                1 -> "<rdf:Seq>" + (0 until random.nextInt(5)).joinToString("") { element(depth + 1) } + "</rdf:Seq>"
                2 -> "<rdf:li>" + element(depth + 1) + "</rdf:li>"
                3 -> "<rdf:Alt><rdf:li xml:lang=\"x-default\">${value()}</rdf:li></rdf:Alt>"
                4 -> (0 until random.nextInt(4)).joinToString("") { element(depth + 1) }
                else -> value()
            }
        }
        val attributes = names.shuffled(random).take(random.nextInt(6)).joinToString(" ") { "crs:$it=\"${value()}\"" }
        val prefix = if (random.nextInt(5) == 0) "lr" else "crs"
        val body = (0 until 1 + random.nextInt(5)).joinToString("\n") { element(0) }.replace("crs:", "$prefix:")
        return """
            <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
             <rdf:Description xmlns:$prefix="http://ns.adobe.com/camera-raw-settings/1.0/" ${attributes.replace("crs:", "$prefix:")}>
            $body
             </rdf:Description>
            </rdf:RDF></x:xmpmeta>
        """.trimIndent()
    }
}
