package com.byeonggon.latent.enhance

import com.byeonggon.latent.allocatedBy
import com.byeonggon.latent.fuzz
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.DeflaterOutputStream

/**
 * The LUT tables and mask outlines a backup brings, as `LutStore` and `MaskStore` keep them: damaged ones are told
 * apart from whole ones without throwing, and a header cannot make either set aside more than its data fills.
 */
class StoredTablesFuzzTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var lutBytes: ByteArray
    private lateinit var maskBytes: ByteArray

    @Before
    fun setUp() {
        LutStore.directory = folder.newFolder("luts")
        MaskStore.directory = folder.newFolder("masks")
        LutStore.save(LUT_ID, ColorLut.of(3) { r, g, b -> floatArrayOf(g, b, r) })
        lutBytes = File(LutStore.directory, "$LUT_ID.lut").readBytes()
        MaskStore.save(MASK_ID, MaskRaster(4, 3, ByteArray(12) { (it * 20).toByte() }))
        maskBytes = File(MaskStore.directory, "$MASK_ID.mask").readBytes()
    }

    @After
    fun tearDown() {
        LutStore.directory = null
        MaskStore.directory = null
    }

    @Test
    fun theSeedsAreWhole() {
        assertTrue(LutStore.isTable(lutBytes))
        assertTrue(MaskStore.isOutline(maskBytes))
    }

    @Test
    fun aDamagedLutTableIsToldApart() = fuzz(seed = 21, cases = 5_000) { mutator ->
        val bytes = mutator.bytes(lutBytes)
        if (LutStore.isTable(bytes)) {
            // One that passes is one that can be used: kept, loaded and looked up.
            assertTrue(LutStore.restore(OTHER_ID, bytes))
            val lut = LutStore.load(OTHER_ID)!!
            val out = FloatArray(3)
            lut.lookup(0.3f, 0.6f, 0.9f, out)
            assertTrue("${out.toList()}", out.all { it.isFinite() })
            LutStore.delete(OTHER_ID)
        }
    }

    @Test
    fun aDamagedMaskOutlineIsToldApart() = fuzz(seed = 22, cases = 5_000) { mutator ->
        val bytes = mutator.bytes(maskBytes)
        if (MaskStore.isOutline(bytes)) {
            assertTrue(MaskStore.restore(OTHER_ID, bytes))
            val raster = MaskStore.load(OTHER_ID)!!
            assertTrue(raster.levels.size == raster.width * raster.height)
            MaskStore.delete(OTHER_ID)
        }
    }

    @Test
    fun aMaskHeaderClaimingTheLargestOutlineMakesNoRoomForIt() {
        // 8192 by 8192 cells said, a few bytes given: 64 MB would be set aside for every such entry of a backup.
        val header = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeInt(0x4D534B31)
                data.writeInt(8192)
                data.writeInt(8192)
                DeflaterOutputStream(data).use { it.write(ByteArray(100)) }
            }
        }.toByteArray()
        var outline = true
        val allocated = allocatedBy { outline = MaskStore.isOutline(header) }

        assertFalse(outline)
        assertTrue("$allocated bytes", allocated < 4_000_000)
    }

    @Test
    fun aLutHeaderClaimingTheLargestTableMakesNoRoomForIt() {
        val header = lutBytes.copyOf(32).also { it[7] = 65 }
        var table = true
        val allocated = allocatedBy { table = LutStore.isTable(header) }

        assertFalse(table)
        assertTrue("$allocated bytes", allocated < 1_000_000)
    }

    private companion object {
        const val LUT_ID = "0123456789abcdef0123456789abcdef"
        const val MASK_ID = "fedcba9876543210fedcba9876543210"
        const val OTHER_ID = "00000000000000000000000000000001"
    }
}
