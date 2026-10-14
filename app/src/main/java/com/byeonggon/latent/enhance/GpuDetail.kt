package com.byeonggon.latent.enhance

import android.opengl.GLES31
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * [DetailFilter] worked out on the graphics processor, with compute shaders: the same steps, in the
 * same order, on bands of rows. The phone's processor takes seconds over a full-size picture; the
 * graphics processor a fraction of that.
 *
 * It runs on the [Graphics] thread. Where the device has no OpenGL ES 3.1, or anything goes wrong,
 * [instance] is null or [apply] returns false, and the processor does the work instead.
 */
internal class GpuDetail private constructor() {

    /**
     * Applies [filter] to [pixels] and hands the result to [write] band by band, as
     * [DetailFilter.apply] does; [glow], if any, is laid over each band before it is handed on.
     * Returns false, having written nothing, if the graphics processor could not do it.
     */
    fun apply(
        filter: DetailFilter,
        pixels: IntArray,
        width: Int,
        height: Int,
        afterBand: (band: IntArray, top: Int, rows: Int) -> Unit,
        write: (top: Int, rows: Int, band: IntArray) -> Unit,
    ): Boolean {
        if (width > Graphics.maxSize) return false
        return try {
            Graphics.run { run(filter, pixels, width, height, afterBand, write) }
        } catch (e: Exception) {
            Log.w(TAG, "Detail on the graphics processor failed; the processor does it", e)
            false
        }
    }

    private fun run(
        filter: DetailFilter,
        pixels: IntArray,
        width: Int,
        height: Int,
        afterBand: (band: IntArray, top: Int, rows: Int) -> Unit,
        write: (top: Int, rows: Int, band: IntArray) -> Unit,
    ): Boolean {
        val margin = filter.reach
        val bandRows = min(BAND_ROWS, height)
        val tallest = bandRows + 2 * margin
        val images = IntArray(IMAGES)
        val textures = IntArray(2)
        val framebuffer = IntArray(1)
        GLES31.glGenTextures(IMAGES, images, 0)
        GLES31.glGenTextures(2, textures, 0)
        GLES31.glGenFramebuffers(1, framebuffer, 0)
        try {
            for (image in images) storage(image, GLES31.GL_RGBA32F, width, tallest)
            val source = textures[0]
            val result = textures[1]
            storage(source, GLES31.GL_RGBA8, width, tallest)
            storage(result, GLES31.GL_RGBA8, width, bandRows)
            val upload = ByteBuffer.allocateDirect(width * tallest * 4).order(ByteOrder.nativeOrder())
            val download = ByteBuffer.allocateDirect(width * bandRows * 4).order(ByteOrder.nativeOrder())
            val band = IntArray(width * bandRows)
            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, framebuffer[0])
            GLES31.glFramebufferTexture2D(GLES31.GL_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0, GLES31.GL_TEXTURE_2D, result, 0)

            var top = 0
            while (top < height) {
                val rows = min(bandRows, height - top)
                val first = maxOf(0, top - margin)
                val last = min(height, top + rows + margin)
                val inputRows = last - first
                // An int holds a pixel as ARGB; in memory that is the bytes B, G, R, A, read back below as the same.
                upload.clear()
                upload.asIntBuffer().put(pixels, first * width, inputRows * width)
                GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, source)
                GLES31.glTexSubImage2D(GLES31.GL_TEXTURE_2D, 0, 0, 0, width, inputRows, GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, upload)
                process(filter, images, source, result, width, inputRows, top - first, rows)
                download.clear()
                GLES31.glReadPixels(0, 0, width, rows, GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, download)
                checkError("reading a band back")
                download.asIntBuffer().get(band, 0, rows * width)
                afterBand(band, top, rows)
                write(top, rows, band)
                top += rows
            }
            return true
        } finally {
            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
            GLES31.glDeleteFramebuffers(1, framebuffer, 0)
            GLES31.glDeleteTextures(IMAGES, images, 0)
            GLES31.glDeleteTextures(2, textures, 0)
        }
    }

    /**
     * Runs the steps of [filter] over a band of [rows] input rows, of which [kept] from [offset] are
     * kept, into [result]. One image holds brightness and colour as they were, another as they are
     * becoming; an image can be read or written in a step, not both, so each step that changes the
     * picture writes a free image, which takes over from the one it read.
     */
    private fun process(filter: DetailFilter, images: IntArray, source: Int, result: Int, width: Int, rows: Int, offset: Int, kept: Int) {
        val base = images[0]
        var work = images[1]
        fun free(vararg busy: Int) = images.first { it != base && it != work && it !in busy }
        val read = GLES31.GL_READ_ONLY
        val written = GLES31.GL_WRITE_ONLY

        dispatch("prepare", width, rows, listOf(base to written, work to written)) { bindSource(source) }

        if (filter.noiseReduction > 0f) {
            val noise = DetailFilter.NOISE_AT_FULL * filter.noiseReduction
            val stats = free()
            val spare = free(stats)
            dispatch("noiseStats", width, rows, listOf(base to read, stats to written))
            box(stats, spare, width, rows, filter.noiseReach)
            dispatch("selfCoefficients", width, rows, listOf(stats to read, spare to written)) { uniform("edge", noise * noise) }
            box(spare, stats, width, rows, filter.noiseReach)
            dispatch("noiseApply", width, rows, listOf(base to read, spare to read, work to written))
        }

        if (filter.colorNoiseReduction > 0f) {
            val first = free()
            val second = free(first)
            val coefficients = free(first, second)
            dispatch("colourStats", width, rows, listOf(base to read, first to written, second to written))
            box(first, coefficients, width, rows, filter.colorNoiseReach)
            box(second, coefficients, width, rows, filter.colorNoiseReach)
            dispatch("colourCoefficients", width, rows, listOf(first to read, second to read, coefficients to written)) {
                uniform("edge", DetailFilter.COLOR_EDGE_VARIANCE)
            }
            box(coefficients, first, width, rows, filter.colorNoiseReach)
            val next = free(coefficients)
            dispatch("colourApply", width, rows, listOf(base to read, coefficients to read, work to read, next to written)) {
                uniform("amount", filter.colorNoiseReduction)
            }
            work = next
        }

        if (filter.texturing) {
            val area = free()
            val next = free(area)
            dispatch("copyBrightness", width, rows, listOf(work to read, area to written))
            box(area, next, width, rows, filter.textureReach)
            box(area, next, width, rows, filter.textureReach)
            dispatch("textureApply", width, rows, listOf(area to read, work to read, next to written)) { uniform("textureAmount", filter.texture) }
            work = next
        }

        if (filter.sharpening > 0f) {
            val next = free()
            dispatch("sharpen", width, rows, listOf(work to read, next to written)) {
                uniform("sharpening", filter.sharpening)
                uniform("faintest", DetailFilter.FAINTEST_EDGE)
            }
            work = next
        }

        dispatch("compose", width, kept, listOf(base to read, work to read)) {
            bindSource(source)
            GLES31.glBindImageTexture(2, result, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES31.GL_RGBA8)
            uniform("rowOffset", offset)
            uniform("colour", if (filter.colorNoiseReduction > 0f) 1 else 0)
        }
    }

    /** The average of [image] over [reach] pixels in each direction, back into [image]; [spare] is for the way. */
    private fun box(image: Int, spare: Int, width: Int, rows: Int, reach: Int) {
        dispatch("boxAcross", width, rows, listOf(image to GLES31.GL_READ_ONLY, spare to GLES31.GL_WRITE_ONLY)) { uniform("reach", reach) }
        dispatch("boxDown", width, rows, listOf(spare to GLES31.GL_READ_ONLY, image to GLES31.GL_WRITE_ONLY)) { uniform("reach", reach) }
    }

    private var current = 0

    private fun dispatch(
        name: String,
        width: Int,
        rows: Int,
        images: List<Pair<Int, Int>>,
        setUp: () -> Unit = {},
    ) {
        current = Graphics.program(name, DetailShaders.HEADER + DetailShaders.ALL.getValue(name))
        GLES31.glUseProgram(current)
        images.forEachIndexed { unit, (image, access) ->
            GLES31.glBindImageTexture(unit, image, 0, false, 0, access, GLES31.GL_RGBA32F)
        }
        uniform("size", width, rows)
        setUp()
        GLES31.glDispatchCompute((width + GROUP - 1) / GROUP, (rows + GROUP - 1) / GROUP, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_FRAMEBUFFER_BARRIER_BIT)
        checkError(name)
    }

    private fun bindSource(source: Int) {
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, source)
        uniform("source", 0)
    }

    private fun uniform(name: String, value: Float) = GLES31.glUniform1f(GLES31.glGetUniformLocation(current, name), value)

    private fun uniform(name: String, value: Int) = GLES31.glUniform1i(GLES31.glGetUniformLocation(current, name), value)

    private fun uniform(name: String, x: Int, y: Int) = GLES31.glUniform2i(GLES31.glGetUniformLocation(current, name), x, y)

    private fun storage(texture: Int, format: Int, width: Int, height: Int) {
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, texture)
        GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, format, width, height)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST)
        checkError("making room")
    }

    private fun checkError(what: String) = Graphics.check(what)

    companion object {
        private const val TAG = "GpuDetail"
        private const val IMAGES = 5
        private const val BAND_ROWS = 256
        private const val GROUP = 16

        private val made: GpuDetail? by lazy { if (Graphics.available) GpuDetail() else null }

        /** The graphics processor's way of working out detail, or null where there is none to use. */
        fun instance(): GpuDetail? = made
    }
}
