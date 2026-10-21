package com.byeonggon.latent.raw

import java.io.IOException

/**
 * Decodes lossless JPEG (ITU T.81, the SOF3 frame), in which most DNG files keep their sensor data: each
 * sample is predicted from the ones left of and above it, and only the Huffman-coded difference is stored.
 */
internal object LosslessJpeg {
    private const val MARKER_START = 0xD8
    private const val MARKER_FRAME = 0xC3
    private const val MARKER_HUFFMAN = 0xC4
    private const val MARKER_SCAN = 0xDA
    private const val MARKER_RESTART_INTERVAL = 0xDD
    private const val MARKER_END = 0xD9

    /**
     * Decodes the stream of [length] bytes at [offset] in [file] into [into], an image [width] by [height].
     * Its samples, taken in order (row by row, the components of a column side by side), fill a tile [tileWidth]
     * wide and at most [tileLength] long whose top left corner is at [left], [top]; samples beyond the image's
     * edges are dropped.
     */
    @Throws(IOException::class)
    fun decode(
        file: ByteArray, offset: Int, length: Int, into: ShortArray, width: Int, height: Int,
        left: Int, top: Int, tileWidth: Int, tileLength: Int, checkCancelled: () -> Unit = {},
    ) {
        val end = minOf(file.size.toLong(), offset.toLong() + length).toInt()
        if (offset < 0 || end - offset < 4 || u8(file, offset) != 0xFF || u8(file, offset + 1) != MARKER_START) {
            throw IOException("Not a lossless JPEG stream")
        }
        val tables = arrayOfNulls<IntArray>(4)
        var precision = 0
        var lines = 0
        var samplesPerLine = 0
        var componentIds = IntArray(0)
        var restartInterval = 0
        var at = offset + 2
        while (true) {
            if (at + 4 > end || u8(file, at) != 0xFF) throw IOException("Damaged lossless JPEG stream")
            val marker = u8(file, at + 1)
            if (marker == 0xFF) {
                at++ // fill byte
                continue
            }
            if (marker == MARKER_END) throw IOException("The lossless JPEG stream holds no scan")
            val segment = u16(file, at + 2)
            val body = at + 4
            val next = at + 2 + segment
            if (segment < 2 || next > end) throw IOException("Damaged lossless JPEG stream")
            when (marker) {
                MARKER_FRAME -> {
                    precision = u8(file, body)
                    lines = u16(file, body + 1)
                    samplesPerLine = u16(file, body + 3)
                    val count = u8(file, body + 5)
                    if (precision !in 2..16 || lines <= 0 || samplesPerLine <= 0 || count !in 1..4) {
                        throw IOException("Unsupported lossless JPEG frame")
                    }
                    // A frame larger than its tile would only be decoded to be thrown away, for as long as it says.
                    if (lines.toLong() * samplesPerLine * count > tileWidth.toLong() * tileLength) {
                        throw IOException("The lossless JPEG frame does not fit its tile")
                    }
                    componentIds = IntArray(count) { u8(file, body + 6 + 3 * it) }
                }
                MARKER_HUFFMAN -> {
                    var position = body
                    while (position < next) {
                        val id = u8(file, position) and 0x0F
                        val counts = IntArray(17) { if (it == 0) 0 else u8(file, position + it) }
                        val total = counts.sum()
                        if (id > 3 || position + 17 + total > next) throw IOException("Damaged Huffman table")
                        tables[id] = lookup(counts, IntArray(total) { u8(file, position + 17 + it) })
                        position += 17 + total
                    }
                }
                MARKER_RESTART_INTERVAL -> restartInterval = u16(file, body)
                MARKER_SCAN -> {
                    if (componentIds.isEmpty()) throw IOException("The lossless JPEG scan comes before its frame")
                    val count = u8(file, body)
                    if (count != componentIds.size) throw IOException("Unsupported lossless JPEG scan")
                    val componentTables = Array(count) { index ->
                        tables[u8(file, body + 2 + 2 * index) ushr 4] ?: throw IOException("Missing Huffman table")
                    }
                    val predictor = u8(file, body + 1 + 2 * count)
                    val pointTransform = u8(file, body + 3 + 2 * count) and 0x0F
                    if (predictor !in 1..7 || pointTransform >= precision) throw IOException("Unsupported lossless JPEG predictor")
                    // Only restarts at the start of a line are written in practice; anything else is refused rather than misread.
                    if (restartInterval != 0 && restartInterval % samplesPerLine != 0) {
                        throw IOException("Unsupported lossless JPEG restart interval")
                    }
                    val scan = Scan(
                        file, next, end, componentTables, precision, pointTransform, predictor, lines, samplesPerLine,
                        restartInterval / samplesPerLine, checkCancelled,
                    )
                    scan.decode(into, width, height, left, top, tileWidth)
                    return
                }
            }
            at = next
        }
    }

    /**
     * A table indexed by the next 16 bits of the stream: the length of the code they start with in the upper half,
     * the value it stands for in the lower. Zero where no code starts that way.
     */
    private fun lookup(counts: IntArray, values: IntArray): IntArray {
        val table = IntArray(1 shl 16)
        var code = 0
        var index = 0
        for (bits in 1..16) {
            repeat(counts[bits]) {
                if (code >= (1 shl bits)) throw IOException("Damaged Huffman table")
                val spread = 16 - bits
                val first = code shl spread
                table.fill((bits shl 16) or values[index], first, first + (1 shl spread))
                code++
                index++
            }
            code = code shl 1
        }
        return table
    }

    private class Scan(
        private val file: ByteArray,
        private var at: Int,
        private val end: Int,
        private val tables: Array<IntArray>,
        private val precision: Int,
        private val pointTransform: Int,
        private val predictor: Int,
        private val lines: Int,
        private val samplesPerLine: Int,
        /** Lines between restart markers; 0 when there are none. */
        private val restartLines: Int,
        private val checkCancelled: () -> Unit,
    ) {
        private var bits = 0L
        private var bitCount = 0

        fun decode(into: ShortArray, width: Int, height: Int, left: Int, top: Int, tileWidth: Int) {
            val components = tables.size
            val rowSize = samplesPerLine * components
            var above = IntArray(rowSize)
            var current = IntArray(rowSize)
            val initial = 1 shl (precision - pointTransform - 1)
            val mask = (1 shl precision) - 1
            var x = 0
            var y = 0
            for (line in 0 until lines) {
                checkCancelled()
                // The first line, and the first after a restart, has no line above it to predict from.
                val first = line == 0 || (restartLines != 0 && line % restartLines == 0)
                if (first && line != 0) restart()
                for (index in 0 until rowSize) {
                    val component = index % components
                    val prediction = when {
                        index < components -> if (first) initial else above[index]
                        first -> current[index - components]
                        else -> predict(current[index - components], above[index], above[index - components])
                    }
                    val value = (prediction + difference(tables[component])) and mask
                    current[index] = value
                    if (left + x < width && top + y < height) into[(top + y) * width + left + x] = (value shl pointTransform).toShort()
                    if (++x == tileWidth) {
                        x = 0
                        y++
                    }
                }
                val swap = above
                above = current
                current = swap
            }
        }

        private fun predict(left: Int, up: Int, upLeft: Int): Int = when (predictor) {
            1 -> left
            2 -> up
            3 -> upLeft
            4 -> left + up - upLeft
            5 -> left + ((up - upLeft) shr 1)
            6 -> up + ((left - upLeft) shr 1)
            else -> (left + up) ushr 1
        }

        private fun difference(table: IntArray): Int {
            fill()
            val entry = table[((bits ushr (bitCount - 16)) and 0xFFFF).toInt()]
            if (entry == 0) throw IOException("Damaged lossless JPEG data")
            bitCount -= entry ushr 16
            val size = entry and 0xFFFF
            return when (size) {
                0 -> 0
                16 -> 32768
                else -> {
                    fill()
                    val raw = ((bits ushr (bitCount - size)) and ((1L shl size) - 1)).toInt()
                    bitCount -= size
                    if (raw < 1 shl (size - 1)) raw - (1 shl size) + 1 else raw
                }
            }
        }

        /** Keeps at least 40 bits at hand: a code and the difference after it. A marker, or the end of the data, reads as zeros. */
        private fun fill() {
            while (bitCount < 40) {
                var byte = 0
                if (at < end) {
                    byte = u8(file, at)
                    if (byte == 0xFF) {
                        val next = if (at + 1 < end) u8(file, at + 1) else 0
                        if (next == 0) at += 2 else byte = 0 // a marker: stay before it
                    } else {
                        at++
                    }
                }
                bits = (bits shl 8) or byte.toLong()
                bitCount += 8
            }
        }

        /** Skips to the restart marker that has to come next, and starts reading afresh after it. */
        private fun restart() {
            bits = 0L
            bitCount = 0
            while (at + 1 < end && !(u8(file, at) == 0xFF && u8(file, at + 1) in 0xD0..0xD7)) at++
            if (at + 1 >= end) throw IOException("Missing restart marker")
            at += 2
        }
    }

    private fun u8(file: ByteArray, at: Int) = file[at].toInt() and 0xFF

    private fun u16(file: ByteArray, at: Int) = (u8(file, at) shl 8) or u8(file, at + 1)
}
