package com.byeonggon.latent.raw

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertThrows
import org.junit.Test

class TiffTest {
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
    fun rejectsAValueRangeThatWrapsOrExtendsPastTheFile() {
        for ((count, position) in listOf(-1 to 0, 2 to -1, 2 to 15, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val data = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(0, 1); putShort(2, 256); putShort(4, 4); putInt(6, count); putInt(10, position)
            }
            assertThrows(IOException::class.java) { TiffDirectory.read(data, 0) }
        }
    }
}
