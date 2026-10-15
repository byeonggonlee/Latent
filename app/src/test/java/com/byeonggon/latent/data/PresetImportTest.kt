package com.byeonggon.latent.data

import com.byeonggon.latent.appContainer
import com.byeonggon.latent.enhance.Unsupported
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PresetImportTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer

    private fun xmp(attributes: String, name: String? = "Moody") = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
         <rdf:Description rdf:about="" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" $attributes>
          ${if (name != null) "<crs:Name><rdf:Alt><rdf:li xml:lang=\"x-default\">$name</rdf:li></rdf:Alt></crs:Name>" else ""}
         </rdf:Description></rdf:RDF></x:xmpmeta>
    """.trimIndent()

    @Test
    fun addsAPresetToTheUsersFiltersUnderItsNameAndSaysWhatWasLeftOut() = runBlocking {
        val preset = container.libraryEdits.importPreset(
            xmp("""crs:Contrast2012="+30" crs:Saturation="-20" crs:Temperature="6000" crs:LensProfileEnable="1"""").byteInputStream(),
            "fallback",
        )!!

        assertEquals(setOf(Unsupported.WHITE_BALANCE, Unsupported.LENS), preset.skipped)
        val filter = container.libraryEdits.filters.first().single { it.custom }
        assertEquals("Moody", filter.name)
        assertEquals(0.3f, filter.look.adjustments.contrast, 1e-5f)
        assertEquals(-0.2f, filter.look.adjustments.saturation, 1e-5f)
    }

    @Test
    fun namesAPresetAfterItsFileWhenItHasNoNameOfItsOwn() = runBlocking {
        container.libraryEdits.importPreset(xmp("""crs:Contrast2012="+30"""", name = null).byteInputStream(), "Sunset 1")

        assertEquals("Sunset 1", container.libraryEdits.filters.first().single { it.custom }.name)
    }

    @Test
    fun takesNothingFromFilesThatAreNoPresetOrHaveNoLookInThem() = runBlocking {
        assertNull(container.libraryEdits.importPreset("plain text".byteInputStream(), "x"))
        // Only a lens correction and a crop: nothing of tones or colours.
        assertNull(container.libraryEdits.importPreset(xmp("""crs:LensProfileEnable="1" crs:CropAngle="3"""").byteInputStream(), "x"))
        assertTrue(container.libraryEdits.filters.first().none { it.custom })
    }
}
