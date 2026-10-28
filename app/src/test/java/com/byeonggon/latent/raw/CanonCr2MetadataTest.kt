package com.byeonggon.latent.raw

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CanonCr2MetadataTest {
    internal fun fixture(): ByteArray {
        val b = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, 'I'.code.toByte()); b.put(1, 'I'.code.toByte()); b.putShort(2, 42); b.putInt(4, 16)
        b.put(8, 'C'.code.toByte()); b.put(9, 'R'.code.toByte()); b.put(10, 2); b.putInt(12, 4000)
        fun dir(at: Int, entries: List<IntArray>) {
            b.putShort(at, entries.size.toShort())
            entries.forEachIndexed { i, e -> val p = at + 2 + i * 12; b.putShort(p,e[0].toShort()); b.putShort(p+2,e[1].toShort()); b.putInt(p+4,e[2]); b.putInt(p+8,e[3]) }
        }
        dir(16, listOf(intArrayOf(0x10f,2,6,400),intArrayOf(0x110,2,13,410),intArrayOf(0x112,3,1,6),intArrayOf(0x8769,4,1,256)))
        "Canon\u0000".toByteArray().copyInto(b.array(),400)
        "Canon EOS 7D\u0000".toByteArray().copyInto(b.array(),410)
        dir(256,listOf(intArrayOf(0x927c,7,3300,512)))
        dir(512,listOf(intArrayOf(0xe0,3,17,1024),intArrayOf(0x4001,3,1337,1100)))
        val sensor = intArrayOf(34,12,8,1,1,0,0,11,7,0,0,0,0,0,0,0,0)
        sensor.forEachIndexed { i,v -> b.putShort(1024 + i*2,v.toShort()) }
        b.putShort(1100,7)
        intArrayOf(2048,1024,1024,1536).forEachIndexed { i,v -> b.putShort(1100+2*(63+i),v.toShort()) }
        dir(4000,listOf(intArrayOf(0x103,3,1,6),intArrayOf(0x111,4,1,6000),intArrayOf(0x117,4,1,20),intArrayOf(0xc640,3,3,4100)))
        intArrayOf(2,4,4).forEachIndexed { i,v -> b.putShort(4100+2*i,v.toShort()) }
        return b.array()
    }

    @Test fun readsSensorCropWhiteBalanceAndRawSliceDirectory() {
        val m = CanonCr2Metadata.read(fixture())
        assertEquals("Canon EOS 7D", m.model)
        assertEquals(12,m.width); assertEquals(8,m.height); assertEquals(6,m.orientation)
        assertArrayEquals(intArrayOf(0,0,12,8),m.crop)
        assertArrayEquals(floatArrayOf(2f,1f,1.5f),m.whiteBalance,0.0001f)
        assertArrayEquals(intArrayOf(2,4,4),m.slices)
        assertEquals(6000,m.offset); assertEquals(20,m.length)
    }

    @Test fun rejectsMakerNotePointersOutsideTheirDeclaredRange() {
        val bytes=fixture()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(512+2+12+8,7000)
        assertThrows(IOException::class.java) { CanonCr2Metadata.read(bytes) }
    }

    @Test fun rejectsDifferentGreenGainsUntilTheRendererCanRepresentThem() {
        val bytes=fixture()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(1100+2*65,1100)
        assertThrows(IOException::class.java) { CanonCr2Metadata.read(bytes) }
    }

    @Test fun rejectsMissingWhiteBalanceAndTruncatedRawData() {
        val zero = fixture()
        ByteBuffer.wrap(zero).order(ByteOrder.LITTLE_ENDIAN).putShort(1100+2*64,0)
        assertThrows(IOException::class.java) { CanonCr2Metadata.read(zero) }
        val bad = fixture()
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(4000+2+2*12+8,4000)
        assertThrows(IOException::class.java) { CanonCr2Metadata.read(bad) }
    }
}
