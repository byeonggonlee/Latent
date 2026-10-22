package com.byeonggon.latent.enhance

import androidx.annotation.StringRes
import com.byeonggon.latent.R
import java.io.IOException
import java.io.InputStream

/** Why a .cube file cannot be used, as it is told to the person. */
enum class LutProblem(@StringRes val label: Int) {
    UNREADABLE(R.string.lut_problem_unreadable),
    TOO_LARGE(R.string.lut_problem_too_large),
    NOT_A_LUT(R.string.lut_problem_not_a_lut),
    ONE_DIMENSIONAL(R.string.lut_problem_one_dimensional),
    NO_SIZE(R.string.lut_problem_no_size),
    BAD_SIZE(R.string.lut_problem_bad_size),
    BAD_DOMAIN(R.string.lut_problem_bad_domain),
    NOT_A_NUMBER(R.string.lut_problem_not_a_number),
    NOT_FINITE(R.string.lut_problem_not_finite),
    WRONG_COUNT(R.string.lut_problem_wrong_count),
}

/** A .cube file that cannot be used, and why; [line] is where in the file the trouble is, from 1, or 0 for the file as a whole. */
class UnusableLutException(val problem: LutProblem, val line: Int = 0) :
    Exception(if (line > 0) "$problem at line $line" else problem.name)

/**
 * Reads the 3D LUTs of Adobe's and Resolve's .cube files: a header of keywords (`TITLE`, `LUT_3D_SIZE`,
 * `DOMAIN_MIN`, `DOMAIN_MAX`, Resolve's `LUT_3D_INPUT_RANGE`), then one line of red, green and blue for
 * every point of the cube, red changing fastest; `#` starts a comment. Keywords of other programs in the
 * header are passed over. A 1D LUT, alone or as a shaper in front of a 3D one, is refused: it changes each
 * colour on its own, which the curves do already, and in front of a 3D table it is mostly for Log video.
 */
object CubeLut {
    /** The largest file read: a 65-point cube written out at length is about 9 MB. */
    const val LARGEST_FILE = 20 * 1024 * 1024

    /** What a .cube file holds: the table, and the [title] it gives itself, if any. */
    class Cube(val title: String?, val lut: ColorLut)

    /** Reads [input]; throws [UnusableLutException] when it is no 3D LUT that can be used. */
    fun read(input: InputStream): Cube = parse(bytesOf(input))

    /** The whole of [input], unless it is larger than a LUT can be; throws [UnusableLutException]. */
    fun bytesOf(input: InputStream): ByteArray {
        val bytes = try {
            input.readNBytes(LARGEST_FILE + 1)
        } catch (e: IOException) {
            throw UnusableLutException(LutProblem.UNREADABLE)
        }
        if (bytes.size > LARGEST_FILE) throw UnusableLutException(LutProblem.TOO_LARGE)
        return bytes
    }

    /** Reads the text of a .cube file; throws [UnusableLutException] when it is no 3D LUT that can be used. */
    fun parse(bytes: ByteArray): Cube {
        // Text has no zero bytes; a picture or other file picked by mistake does.
        if (bytes.any { it == ZERO }) throw UnusableLutException(LutProblem.NOT_A_LUT)
        var title: String? = null
        var size = 0
        val domainMin = floatArrayOf(0f, 0f, 0f)
        val domainMax = floatArrayOf(1f, 1f, 1f)
        var header = false
        var table: FloatArray? = null
        var points = 0
        val tokens = ArrayList<String>(4)

        var start = if (bytes.size >= 3 && bytes[0] == BOM[0] && bytes[1] == BOM[1] && bytes[2] == BOM[2]) 3 else 0
        var line = 0
        while (start < bytes.size) {
            var end = start
            while (end < bytes.size && bytes[end] != LF && bytes[end] != CR) end++
            line++
            tokenize(bytes, start, end, tokens)
            val next = if (end + 1 < bytes.size && bytes[end] == CR && bytes[end + 1] == LF) end + 2 else end + 1
            if (tokens.isEmpty()) {
                start = next
                continue
            }
            val first = tokens[0]
            val keyword = first[0].isLetter() && !isNonFinite(first)
            if (keyword && table != null) {
                // The table is under way; keywords belong before it.
                throw UnusableLutException(LutProblem.NOT_A_NUMBER, line)
            }
            if (keyword) {
                when (first) {
                    "TITLE" -> {
                        header = true
                        title = titleOf(String(bytes, start, end - start, Charsets.UTF_8))
                    }
                    "LUT_3D_SIZE" -> {
                        header = true
                        size = tokens.getOrNull(1)?.toIntOrNull()?.takeIf { tokens.size == 2 && it in ColorLut.SMALLEST..ColorLut.LARGEST }
                            ?: throw UnusableLutException(LutProblem.BAD_SIZE, line)
                    }
                    "LUT_1D_SIZE", "LUT_1D_INPUT_RANGE" -> throw UnusableLutException(LutProblem.ONE_DIMENSIONAL, line)
                    "DOMAIN_MIN", "DOMAIN_MAX" -> {
                        header = true
                        if (tokens.size != 4) throw UnusableLutException(LutProblem.BAD_DOMAIN, line)
                        val into = if (first == "DOMAIN_MIN") domainMin else domainMax
                        for (channel in 0..2) into[channel] = domainNumber(tokens[channel + 1], line)
                    }
                    "LUT_3D_INPUT_RANGE" -> {
                        header = true
                        if (tokens.size != 3) throw UnusableLutException(LutProblem.BAD_DOMAIN, line)
                        domainMin.fill(domainNumber(tokens[1], line))
                        domainMax.fill(domainNumber(tokens[2], line))
                    }
                    // Other programs' keywords, such as Resolve's video range flags, say nothing about the table.
                }
                start = next
                continue
            }

            // A line of the table.
            if (size == 0) {
                // Words where numbers are expected, before anything that makes this a LUT: some other file.
                if (!header && tokens.any { it.toFloatOrNullQuickly() == null && !isNonFinite(it) }) {
                    throw UnusableLutException(LutProblem.NOT_A_LUT, line)
                }
                throw UnusableLutException(LutProblem.NO_SIZE, line)
            }
            if (tokens.size != 3) throw UnusableLutException(LutProblem.NOT_A_NUMBER, line)
            val values = table ?: FloatArray(size * size * size * 3).also { table = it }
            if (points * 3 == values.size) throw UnusableLutException(LutProblem.WRONG_COUNT, line)
            for (channel in 0..2) values[points * 3 + channel] = number(tokens[channel], line)
            points++
            start = next
        }

        val values = table
        if (values == null) {
            throw UnusableLutException(if (size == 0 && !header) LutProblem.NOT_A_LUT else if (size == 0) LutProblem.NO_SIZE else LutProblem.WRONG_COUNT)
        }
        if (points * 3 != values.size) throw UnusableLutException(LutProblem.WRONG_COUNT)
        if ((0..2).any { domainMin[it] >= domainMax[it] }) throw UnusableLutException(LutProblem.BAD_DOMAIN)
        val lut = try {
            ColorLut(size, values, domainMin, domainMax)
        } catch (e: IllegalArgumentException) {
            // A domain too narrow, or too wide, to spread the points over.
            throw UnusableLutException(LutProblem.BAD_DOMAIN)
        }
        return Cube(title, lut)
    }

    /**
     * Whether a LUT by this name seems made for Log video (S-Log3, V-Log, F-Log, LogC...). Such a LUT
     * expects the flat picture of a Log recording, and makes an ordinary photo look wrong.
     */
    fun isForLogFootage(name: String): Boolean = LOG_NAME.containsMatchIn(name)

    /**
     * Splits the line from [start] to [end] into [tokens] at white space, up to a comment. A `#` inside a
     * number is not one: it is how some programs write what is not a number, such as `1.#INF`.
     */
    private fun tokenize(bytes: ByteArray, start: Int, end: Int, tokens: ArrayList<String>) {
        tokens.clear()
        var at = start
        while (at < end) {
            while (at < end && isSpace(bytes[at])) at++
            if (at >= end || bytes[at] == HASH) return
            val from = at
            while (at < end && !isSpace(bytes[at])) at++
            tokens += String(bytes, from, at - from, Charsets.ISO_8859_1)
            if (tokens.size == 1 && tokens[0] == "TITLE") return
        }
    }

    /** The quoted name after `TITLE`, or what follows it unquoted. */
    private fun titleOf(line: String): String? {
        val rest = line.trim().removePrefix("TITLE").trim()
        val quoted = if (rest.startsWith('"')) rest.substring(1).substringBefore('"') else rest.substringBefore('#').trim()
        return quoted.trim().takeIf { it.isNotEmpty() }
    }

    private fun number(token: String, line: Int): Float {
        val value = token.toFloatOrNullQuickly()
            ?: throw UnusableLutException(if (isNonFinite(token)) LutProblem.NOT_FINITE else LutProblem.NOT_A_NUMBER, line)
        if (!value.isFinite()) throw UnusableLutException(LutProblem.NOT_FINITE, line)
        return value
    }

    private fun domainNumber(token: String, line: Int): Float =
        token.toFloatOrNullQuickly()?.takeIf { it.isFinite() } ?: throw UnusableLutException(LutProblem.BAD_DOMAIN, line)

    /** Kotlin's own [String.toFloatOrNull] checks the text with a regular expression first: too slow for a quarter of a million lines. */
    private fun String.toFloatOrNullQuickly(): Float? = try {
        java.lang.Float.parseFloat(this)
    } catch (e: NumberFormatException) {
        null
    }

    private fun isNonFinite(token: String): Boolean = token.trimStart('+', '-').lowercase() in NON_FINITE

    private fun isSpace(byte: Byte) = byte == SPACE || byte == TAB || byte == CR || byte == FORM_FEED

    // "Log" as a word or after a maker's letter (S-Log3, VLog, LogC), not inside one such as "Analog".
    private val LOG_NAME = Regex("""(^|[^a-z]|[svfcnd]-?)log(\d|c|[^a-z]|$)""", RegexOption.IGNORE_CASE)
    private val NON_FINITE = setOf("nan", "inf", "infinity", "1.#inf", "1.#qnan", "1.#ind")
    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private const val ZERO: Byte = 0
    private const val LF = '\n'.code.toByte()
    private const val CR = '\r'.code.toByte()
    private const val SPACE = ' '.code.toByte()
    private const val TAB = '\t'.code.toByte()
    private const val FORM_FEED = 12.toByte()
    private const val HASH = '#'.code.toByte()
}
