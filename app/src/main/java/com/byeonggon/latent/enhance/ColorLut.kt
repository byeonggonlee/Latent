package com.byeonggon.latent.enhance

import kotlin.math.min

/**
 * A 3D colour lookup table: a cube of [size] points along each side, spread evenly over the colours
 * from [domainMin] to [domainMax], and the colour each point turns into. [table] holds red, green and
 * blue for every point, red changing fastest, then green, then blue, as a .cube file lists them.
 */
class ColorLut(
    val size: Int,
    val table: FloatArray,
    val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
) {
    init {
        require(size in SMALLEST..LARGEST) { "size $size" }
        require(table.size == size * size * size * 3) { "${table.size} values for a size of $size" }
        require((0..2).all { domainMin[it] < domainMax[it] }) { "empty domain" }
    }

    /** For each colour, what takes a value to its place along the cube's side: the value times [scale], plus [offset]. */
    internal val scale = FloatArray(3) { (size - 1) / (domainMax[it] - domainMin[it]) }
    internal val offset = FloatArray(3) { -domainMin[it] * scale[it] }

    /**
     * Writes to [out] what the colour ([red], [green], [blue]) turns into: the eight points of the cube
     * around it, mixed by how near it is to each (trilinear). Colours outside the domain take its edge.
     * The graphics processor's shader does the same, step by step.
     */
    fun lookup(red: Float, green: Float, blue: Float, out: FloatArray) {
        val last = (size - 1).toFloat()
        val x = (red * scale[0] + offset[0]).coerceIn(0f, last)
        val y = (green * scale[1] + offset[1]).coerceIn(0f, last)
        val z = (blue * scale[2] + offset[2]).coerceIn(0f, last)
        // At the far edge of the cube, the last cell's far side.
        val left = min(x.toInt(), size - 2)
        val near = min(y.toInt(), size - 2)
        val low = min(z.toInt(), size - 2)
        val across = x - left
        val down = y - near
        val deep = z - low
        val nextGreen = size * 3
        val nextBlue = size * size * 3
        val first = ((low * size + near) * size + left) * 3
        for (channel in 0..2) {
            val at = first + channel
            val c000 = table[at]
            val c100 = table[at + 3]
            val c010 = table[at + nextGreen]
            val c110 = table[at + nextGreen + 3]
            val c001 = table[at + nextBlue]
            val c101 = table[at + nextBlue + 3]
            val c011 = table[at + nextBlue + nextGreen]
            val c111 = table[at + nextBlue + nextGreen + 3]
            val c00 = c000 + (c100 - c000) * across
            val c10 = c010 + (c110 - c010) * across
            val c01 = c001 + (c101 - c001) * across
            val c11 = c011 + (c111 - c011) * across
            val c0 = c00 + (c10 - c00) * down
            val c1 = c01 + (c11 - c01) * down
            out[channel] = c0 + (c1 - c0) * deep
        }
    }

    companion object {
        const val SMALLEST = 2
        const val LARGEST = 65

        /** The table that changes nothing, of [size] points along each side. */
        fun identity(size: Int): ColorLut = of(size) { r, g, b -> floatArrayOf(r, g, b) }

        /** The table of [size] points along each side that turns each colour into what [colour] makes of it. */
        fun of(size: Int, colour: (red: Float, green: Float, blue: Float) -> FloatArray): ColorLut {
            val table = FloatArray(size * size * size * 3)
            val step = 1f / (size - 1)
            var at = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                val turned = colour(r * step, g * step, b * step)
                table[at++] = turned[0]
                table[at++] = turned[1]
                table[at++] = turned[2]
            }
            return ColorLut(size, table)
        }
    }
}
