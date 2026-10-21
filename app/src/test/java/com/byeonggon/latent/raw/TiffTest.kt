package com.byeonggon.latent.raw

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TiffTest {
    @Test
    fun rejectsATiffWithTheWrongMagicNumber() {
        val bytes = byteArrayOf(73, 73, 0, 0, 8, 0, 0, 0)
        assertThrows(IOException::class.java) { TiffDirectory.open(bytes) }
    }

    @Test
    fun rejectsAnIfdOutsideTheFileBeforeReadingEntries() {
        for (offset in listOf(-1, 7, Int.MAX_VALUE)) {
            assertThrows(IOException::class.java) { TiffDirectory.read(ByteBuffer.allocate(8), offset) }
        }
    }

    @Test
    fun rejectsAnEntryTableThatDoesNotFitTheFile() {
        val data = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply { putShort(0, 65535.toShort()) }
        assertThrows(IOException::class.java) { TiffDirectory.read(data, 0) }
    }

    @Test
    fun anUnusedPrivateValueDoesNotPreventReadingAValidTag() {
        val data = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0, 2)
            putShort(2, 256); putShort(4, 4); putInt(6, 2); putInt(10, Int.MAX_VALUE)
            putShort(14, 257); putShort(16, 4); putInt(18, 1); putInt(22, 24)
        }
        val directory = TiffDirectory.read(data, 0)
        assertEquals(24, directory.int(257))
        assertThrows(IOException::class.java) { directory.ints(256) }
    }

    @Test
    fun rejectsAValueRangeThatWrapsOrExtendsPastTheFile() {
        for ((count, position) in listOf(-1 to 0, 2 to -1, 2 to 15, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val data = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(0, 1); putShort(2, 256); putShort(4, 4); putInt(6, count); putInt(10, position)
            }
            assertThrows(IOException::class.java) { TiffDirectory.read(data, 0).ints(256) }
        }
    }
}
