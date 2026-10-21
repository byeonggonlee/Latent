package com.byeonggon.latent.raw

import kotlin.math.sqrt

/** Standard EXIF illuminants and Robertson's CIE 1960 uv inverse-temperature interpolation. */
internal object DngIlluminants {
    fun kelvin(light: Int?): Double? = when (light) {
        3, 17 -> 2850.0
        24 -> 3200.0
        23 -> 5000.0
        1, 4, 9, 18, 20 -> 5500.0
        10, 19, 21 -> 6500.0
        11, 22 -> 7500.0
        12 -> 6400.0
        13 -> 5050.0
        2, 14 -> 4150.0
        15 -> 3525.0
        16 -> 2925.0
        else -> null
    }

    /** Returns mired (1e6/K), or null for a nonphysical white coordinate. */
    fun mired(x: Double, y: Double): Double? {
        if (!x.isFinite() || !y.isFinite() || x <= 0 || y <= 0 || x + y >= 1) return null
        val denominator = 1.5 - x + 6 * y
        val u = 2 * x / denominator
        val v = 3 * y / denominator
        var previousDistance = 0.0
        for (index in 1..isotherms.lastIndex) {
            val point = isotherms[index]
            val distance = ((v - point[2]) - (u - point[1]) * point[3]) / sqrt(1 + point[3] * point[3])
            if (distance <= 0 || index == isotherms.lastIndex) {
                val below = -distance.coerceAtMost(0.0)
                val fraction = if (index == 1) 0.0 else below / (previousDistance + below)
                return isotherms[index - 1][0] * fraction + point[0] * (1 - fraction)
            }
            previousDistance = distance
        }
        return null
    }

    // Wyszecki & Stiles, Color Science (2nd ed.), p.228, also tabulated in DNG SDK dng_temperature.cpp.
    // Columns: mired, CIE 1960 u, v, isotherm slope. See decoder-execution.md for the SDK reference.
    private val isotherms = arrayOf(
        doubleArrayOf(0.0, 0.18006, 0.26352, -0.24341),
        doubleArrayOf(10.0, 0.18066, 0.26589, -0.25479),
        doubleArrayOf(20.0, 0.18133, 0.26846, -0.26876),
        doubleArrayOf(30.0, 0.18208, 0.27119, -0.28539),
        doubleArrayOf(40.0, 0.18293, 0.27407, -0.30470),
        doubleArrayOf(50.0, 0.18388, 0.27709, -0.32675),
        doubleArrayOf(60.0, 0.18494, 0.28021, -0.35156),
        doubleArrayOf(70.0, 0.18611, 0.28342, -0.37915),
        doubleArrayOf(80.0, 0.18740, 0.28668, -0.40955),
        doubleArrayOf(90.0, 0.18880, 0.28997, -0.44278),
        doubleArrayOf(100.0, 0.19032, 0.29326, -0.47888),
        doubleArrayOf(125.0, 0.19462, 0.30141, -0.58204),
        doubleArrayOf(150.0, 0.19962, 0.30921, -0.70471),
        doubleArrayOf(175.0, 0.20525, 0.31647, -0.84901),
        doubleArrayOf(200.0, 0.21142, 0.32312, -1.0182),
        doubleArrayOf(225.0, 0.21807, 0.32909, -1.2168),
        doubleArrayOf(250.0, 0.22511, 0.33439, -1.4512),
        doubleArrayOf(275.0, 0.23247, 0.33904, -1.7298),
        doubleArrayOf(300.0, 0.24010, 0.34308, -2.0637),
        doubleArrayOf(325.0, 0.24702, 0.34655, -2.4681),
        doubleArrayOf(350.0, 0.25591, 0.34951, -2.9641),
        doubleArrayOf(375.0, 0.26400, 0.35200, -3.5814),
        doubleArrayOf(400.0, 0.27218, 0.35407, -4.3633),
        doubleArrayOf(425.0, 0.28039, 0.35577, -5.3762),
        doubleArrayOf(450.0, 0.28863, 0.35714, -6.7262),
        doubleArrayOf(475.0, 0.29685, 0.35823, -8.5955),
        doubleArrayOf(500.0, 0.30505, 0.35907, -11.324),
        doubleArrayOf(525.0, 0.31320, 0.35968, -15.628),
        doubleArrayOf(550.0, 0.32129, 0.36011, -23.325),
        doubleArrayOf(575.0, 0.32931, 0.36038, -40.770),
        doubleArrayOf(600.0, 0.33724, 0.36051, -116.45),
    )
}
