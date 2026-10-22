package com.byeonggon.latent

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlin.random.Random

/**
 * Seeded mutations of valid inputs, for the `*FuzzTest` classes: the same seed always makes the same case, so a
 * failure names the seed that reproduces it.
 */
internal class Mutator(val seed: Long) {
    val random = Random(seed)

    /** [input] with one to four changes: bits flipped, bytes and length fields set to edge values, cut, doubled or dropped. */
    fun bytes(input: ByteArray): ByteArray {
        var bytes = input
        repeat(1 + random.nextInt(4)) { bytes = mutateOnce(bytes) }
        return bytes
    }

    private fun mutateOnce(input: ByteArray): ByteArray {
        if (input.isEmpty()) return byteArrayOf(random.nextInt(256).toByte())
        val bytes = input.copyOf()
        val at = random.nextInt(bytes.size)
        when (random.nextInt(9)) {
            0 -> bytes[at] = (bytes[at].toInt() xor (1 shl random.nextInt(8))).toByte()
            1 -> bytes[at] = EDGE_BYTES[random.nextInt(EDGE_BYTES.size)].toByte()
            // A 16- or 32-bit length or offset, in either byte order, set to 0, small, or huge.
            2, 3 -> {
                val width = if (random.nextBoolean()) 2 else 4
                val value = EDGE_INTS[random.nextInt(EDGE_INTS.size)]
                val bigEndian = random.nextBoolean()
                for (i in 0 until width) {
                    if (at + i >= bytes.size) break
                    val shift = if (bigEndian) 8 * (width - 1 - i) else 8 * i
                    bytes[at + i] = (value ushr shift).toByte()
                }
            }
            4 -> return input.copyOf(at)
            5 -> {
                // A piece doubled where it is, as a structure listed twice.
                val length = 1 + random.nextInt(minOf(64, bytes.size - at))
                return input.copyOfRange(0, at + length) + input.copyOfRange(at, input.size)
            }
            6 -> {
                val length = 1 + random.nextInt(minOf(64, bytes.size - at))
                return input.copyOfRange(0, at) + input.copyOfRange(at + length, input.size)
            }
            7 -> return input.copyOfRange(0, at) + random.nextBytes(1 + random.nextInt(16)) + input.copyOfRange(at, input.size)
            else -> for (i in at until minOf(bytes.size, at + 8)) bytes[i] = random.nextInt(256).toByte()
        }
        return bytes
    }

    /** [text] with one to three of: a JSON value replaced by one of the wrong kind or an edge value, a key dropped or doubled, a cut. */
    fun json(text: String): String {
        val tree = try {
            JsonParser.parseString(text)
        } catch (e: RuntimeException) {
            return String(bytes(text.toByteArray()), Charsets.UTF_8)
        }
        repeat(1 + random.nextInt(3)) { mutateTree(tree) }
        val written = tree.toString()
        // Now and then broken as text too: cut, or a byte changed.
        return when (random.nextInt(8)) {
            0 -> written.substring(0, random.nextInt(written.length + 1))
            1 -> String(bytes(written.toByteArray()), Charsets.UTF_8)
            else -> written
        }
    }

    private fun mutateTree(root: JsonElement) {
        val containers = ArrayList<JsonElement>()
        fun walk(element: JsonElement) {
            if (element.isJsonObject) {
                containers += element
                element.asJsonObject.entrySet().forEach { walk(it.value) }
            } else if (element.isJsonArray) {
                containers += element
                element.asJsonArray.forEach(::walk)
            }
        }
        walk(root)
        if (containers.isEmpty()) return
        val container = containers[random.nextInt(containers.size)]
        if (container.isJsonObject) {
            val obj = container.asJsonObject
            val keys = obj.keySet().toList()
            if (keys.isEmpty()) {
                obj.add("x", edgeValue(null))
                return
            }
            val key = keys[random.nextInt(keys.size)]
            when (random.nextInt(4)) {
                0 -> obj.remove(key)
                else -> obj.add(key, edgeValue(obj.get(key)))
            }
        } else {
            val array = container.asJsonArray
            if (array.size() == 0) {
                array.add(edgeValue(null))
                return
            }
            val index = random.nextInt(array.size())
            when (random.nextInt(5)) {
                0 -> array.remove(index)
                // Doubled, many times over: a structure listed again and again.
                1 -> repeat(1 + random.nextInt(50)) { array.add(array.get(index).deepCopy()) }
                else -> array.set(index, edgeValue(array.get(index)))
            }
        }
    }

    /** A value where [old] was: of another kind, an edge number, NaN or infinite, nested deep, or [old] wrapped. */
    fun edgeValue(old: JsonElement?): JsonElement = when (random.nextInt(18)) {
        0 -> JsonNull.INSTANCE
        1 -> JsonPrimitive("")
        2 -> JsonPrimitive("NaN")
        3 -> JsonPrimitive(Double.NaN)
        4 -> JsonPrimitive(if (random.nextBoolean()) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY)
        5 -> JsonPrimitive(if (random.nextBoolean()) 1e308 else -1e308)
        6 -> JsonPrimitive(EDGE_LONGS[random.nextInt(EDGE_LONGS.size)])
        7 -> JsonPrimitive(java.math.BigInteger("9".repeat(1 + random.nextInt(400))))
        8 -> JsonPrimitive(random.nextBoolean())
        9 -> JsonPrimitive("x".repeat(random.nextInt(2000)))
        10 -> JsonArray()
        11 -> JsonObject()
        12 -> JsonArray().apply { old?.let { add(it.deepCopy()) } }
        13 -> JsonObject().apply { old?.let { add("value", it.deepCopy()) } }
        14 -> {
            // Nested deeper than any reader should follow.
            var deep: JsonElement = JsonArray()
            repeat(1 + random.nextInt(400)) { deep = JsonArray().apply { add(deep) } }
            deep
        }
        15 -> JsonPrimitive(random.nextDouble(-1e6, 1e6))
        16 -> JsonPrimitive(old?.takeIf { it.isJsonPrimitive }?.asString?.let { it + it } ?: "\u0000￿\uD800")
        else -> JsonPrimitive(random.nextLong())
    }

    companion object {
        private val EDGE_BYTES = intArrayOf(0x00, 0x01, 0x7F, 0x80, 0xFE, 0xFF)
        private val EDGE_INTS = intArrayOf(0, 1, 2, 0x7F, 0xFF, 0x7FFF, 0x8000, 0xFFFF, 0x7FFFFFFF, Int.MIN_VALUE, -1, -2, 0x10000)
        private val EDGE_LONGS = longArrayOf(0, -1, 1, Long.MAX_VALUE, Long.MIN_VALUE, Int.MAX_VALUE.toLong(), Int.MIN_VALUE.toLong(), 0xFFFFFFFFL)
    }
}

/**
 * Runs [cases] cases of [block], each with its own seed derived from [seed], and fails naming the seed of the first case
 * that does, or of one that takes longer than [slowest] milliseconds.
 */
internal fun fuzz(seed: Long, cases: Int, slowest: Long = 2_000, block: (Mutator) -> Unit) {
    for (case in 0 until cases) {
        val caseSeed = seed * 1_000_003 + case
        val started = System.nanoTime()
        try {
            block(Mutator(caseSeed))
        } catch (e: Throwable) {
            throw AssertionError("Case $case (seed $caseSeed) failed: $e", e)
        }
        val took = (System.nanoTime() - started) / 1_000_000
        if (took > slowest) throw AssertionError("Case $case (seed $caseSeed) took $took ms")
    }
}

/** The bytes the current thread allocates while running [block], as HotSpot counts them. */
internal fun allocatedBy(block: () -> Unit): Long {
    val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    val before = threads.currentThreadAllocatedBytes
    block()
    return threads.currentThreadAllocatedBytes - before
}
