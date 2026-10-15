package com.byeonggon.latent.enhance

import android.opengl.GLES31
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.min

/**
 * [ToneRenderer.render] on the graphics processor. Every pixel goes through the same steps as on the
 * processor, looking things up in the same tables, which are handed over as they are; the picture is
 * handed over and back a band of rows at a time. Runs on the [Graphics] thread; returns false, having
 * written nothing, where it cannot be done there.
 */
internal object GpuTone {
    private const val TAG = "GpuTone"
    private const val BAND_ROWS = 512
    private const val GROUP = 16
    private const val STAGE_INTS = 16
    private const val LOCAL_INTS = 16

    /** What a picture comes as: the levels of its light, or finished pixels the camera rendered. */
    private class Input(val width: Int, val height: Int, val levels: LinearImage?, val displayed: IntArray?)

    fun render(
        renderer: ToneRenderer,
        source: LinearImage,
        output: IntArray,
        top: Int,
        frameHeight: Int,
        left: Int,
        frameWidth: Int,
    ): Boolean = render(renderer, Input(source.width, source.height, source, null), output, top, frameHeight, left, frameWidth)

    /**
     * Renders [pixels], [width] by [rows], as the camera made them, into themselves: the same as
     * rendering [LinearImage.fromArgb] of them, without making it.
     */
    fun renderDisplayed(renderer: ToneRenderer, pixels: IntArray, width: Int, rows: Int, top: Int, frameHeight: Int): Boolean =
        render(renderer, Input(width, rows, null, pixels), pixels, top, frameHeight, 0, width)

    private fun render(
        renderer: ToneRenderer,
        input: Input,
        output: IntArray,
        top: Int,
        frameHeight: Int,
        left: Int,
        frameWidth: Int,
    ): Boolean {
        if (!Graphics.available || input.width > Graphics.maxSize) return false
        return try {
            val tables = tablesFor(renderer, frameWidth, frameHeight)
            Graphics.run { run(renderer, tables, input, output, top, frameHeight, left, frameWidth) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Tones on the graphics processor failed; the processor does them", e)
            false
        }
    }

    /** The tables last made, and for what: a picture rendered in strips asks for the same ones for every strip. */
    private val made = java.util.WeakHashMap<ToneRenderer, Tables>()

    private fun tablesFor(renderer: ToneRenderer, frameWidth: Int, frameHeight: Int): Tables = synchronized(made) {
        made[renderer]?.takeIf { it.frameWidth == frameWidth && it.frameHeight == frameHeight }
            ?: Tables(renderer, frameWidth, frameHeight).also { made[renderer] = it }
    }

    /** Everything the shader looks up, laid out in three buffers, made off the graphics thread. */
    private class Tables(renderer: ToneRenderer, val frameWidth: Int, val frameHeight: Int) {
        private val values = ArrayList<FloatArray>()
        private var size = 0
        val stages: IntBuffer
        val locals: IntBuffer
        val floats: FloatBuffer
        var mapWidth = 1
        var mapHeight = 1
        val mixer: Int
        val grading: Int
        val vignette: Int
        val grain: Grain?

        private fun add(vararg arrays: FloatArray): Int {
            val at = size
            for (array in arrays) {
                values += array
                size += array.size
            }
            return at
        }

        init {
            val all = listOf(renderer.everywhere) + renderer.locals
            val stageInts = IntArray(all.size * STAGE_INTS)
            all.forEachIndexed { index, stage ->
                val base = index * STAGE_INTS
                fun map(map: GuidedMap?): Int {
                    if (map == null) return -1
                    mapWidth = map.width
                    mapHeight = map.height
                    return add(map.slope, map.offset)
                }
                stageInts[base] = add(stage.red, stage.green, stage.blue)
                stageInts[base + 1] = stage.tones?.let { add(it.gain) } ?: -1
                stageInts[base + 2] = stage.tones?.let { add(it.share) } ?: -1
                stageInts[base + 3] = stage.haze?.let { add(it.stretch) } ?: -1
                stageInts[base + 4] = map(stage.clearness)
                stageInts[base + 5] = map(stage.surroundings)
                stageInts[base + 6] = map(stage.nearSurroundings)
                stageInts[base + 7] = if (stage.colorful) 1 else 0
                val haze = stage.haze
                val floatsAt = floatArrayOf(
                    stage.saturation, stage.vibrance, stage.clarity, stage.evenHaze,
                    haze?.airRed ?: 0f, haze?.airGreen ?: 0f, haze?.airBlue ?: 0f, if (haze != null) 1f else 0f,
                )
                for (i in floatsAt.indices) stageInts[base + 8 + i] = java.lang.Float.floatToRawIntBits(floatsAt[i])
            }
            val localInts = IntArray(maxOf(1, renderer.localEdits.size) * LOCAL_INTS)
            renderer.localEdits.forEachIndexed { index, local ->
                val base = index * LOCAL_INTS
                val mask = local.mask
                localInts[base] = when (mask.shape) {
                    MaskShape.LINEAR -> 0
                    MaskShape.BRUSH -> 1
                    MaskShape.RADIAL -> 2
                    MaskShape.EVERYWHERE -> 3
                }
                localInts[base + 1] = if (mask.inverted) 1 else 0
                if (mask.shape == MaskShape.BRUSH) {
                    val placement = Placement(renderer.edit.framing, frameWidth, frameHeight)
                    val raster = BrushRaster.of(mask, placement.wholeWidth / placement.wholeHeight)
                    localInts[base + 2] = add(raster.coverage)
                    localInts[base + 3] = raster.width
                    localInts[base + 4] = raster.height
                }
                localInts[base + 5] = java.lang.Float.floatToRawIntBits(local.hue ?: 0f)
                localInts[base + 6] = java.lang.Float.floatToRawIntBits(local.hueSpread)
                localInts[base + 7] = if (local.hue != null) 1 else 0
                val floatsAt = floatArrayOf(mask.startX, mask.startY, mask.endX, mask.endY, mask.feather, local.darkest, local.brightest, 0f)
                for (i in floatsAt.indices) localInts[base + 8 + i] = java.lang.Float.floatToRawIntBits(floatsAt[i])
            }
            mixer = renderer.mixer?.let { add(it.hueShift, it.saturation, it.luminance) } ?: -1
            grading = renderer.grading?.let { add(it.red, it.green, it.blue) } ?: -1
            vignette = renderer.vignette?.let { add(it.factor) } ?: -1
            grain = if (renderer.adjustments.grain > 0f) Grain(renderer.adjustments.grain, frameWidth, frameHeight) else null

            floats = ByteBuffer.allocateDirect(maxOf(1, size) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (array in values) floats.put(array)
            floats.position(0)
            stages = direct(stageInts)
            locals = direct(localInts)
        }

        private fun direct(ints: IntArray): IntBuffer =
            ByteBuffer.allocateDirect(ints.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(ints).also { it.position(0) }
    }

    private fun run(
        renderer: ToneRenderer,
        tables: Tables,
        input: Input,
        output: IntArray,
        top: Int,
        frameHeight: Int,
        left: Int,
        frameWidth: Int,
    ) {
        val width = input.width
        val height = input.height
        val displayed = input.displayed
        val bandRows = min(BAND_ROWS, height)
        val buffers = IntArray(4)
        val planes = IntArray(3)
        val result = IntArray(1)
        val framebuffer = IntArray(1)
        GLES31.glGenBuffers(4, buffers, 0)
        GLES31.glGenTextures(3, planes, 0)
        GLES31.glGenTextures(1, result, 0)
        GLES31.glGenFramebuffers(1, framebuffer, 0)
        try {
            upload(buffers[0], tables.floats.capacity() * 4, tables.floats)
            upload(buffers[1], tables.stages.capacity() * 4, tables.stages)
            upload(buffers[2], tables.locals.capacity() * 4, tables.locals)
            if (displayed != null) {
                val levels = IntArray(256) { CameraCurve.levelOfDisplay[it].toInt() }
                upload(buffers[3], levels.size * 4, ByteBuffer.allocateDirect(levels.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(levels).also { it.position(0) })
            }
            for (index in 0 until 4) GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, index, buffers[index])
            for ((index, plane) in planes.withIndex()) {
                // Finished pixels need one texture, of bytes; levels of light three, one for each colour.
                if (displayed != null && index > 0) continue
                GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, plane)
                GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, if (displayed != null) GLES31.GL_RGBA8 else GLES31.GL_R16UI, width, bandRows)
                GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST)
                GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST)
            }
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, result[0])
            GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES31.GL_RGBA8, width, bandRows)
            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, framebuffer[0])
            GLES31.glFramebufferTexture2D(GLES31.GL_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0, GLES31.GL_TEXTURE_2D, result[0], 0)
            Graphics.check("making room")

            val program = if (displayed != null) {
                Graphics.program("toneDisplayed", ToneShader.source(displayed = true))
            } else {
                Graphics.program("tone", ToneShader.source(displayed = false))
            }
            GLES31.glUseProgram(program)
            fun location(name: String) = GLES31.glGetUniformLocation(program, name)
            listOf("redPlane", "greenPlane", "bluePlane").forEachIndexed { unit, name -> GLES31.glUniform1i(location(name), unit) }
            GLES31.glUniform1i(location("displayed"), 0)
            GLES31.glUniform1i(location("frameTop"), top)
            GLES31.glUniform1i(location("frameHeight"), frameHeight)
            GLES31.glUniform1i(location("frameLeft"), left)
            GLES31.glUniform1i(location("frameWidth"), frameWidth)
            GLES31.glUniform1i(location("mapWidth"), tables.mapWidth)
            GLES31.glUniform1i(location("mapHeight"), tables.mapHeight)
            GLES31.glUniform1i(location("localCount"), renderer.localEdits.size)
            GLES31.glUniform1i(location("monochrome"), if (renderer.monochrome) 1 else 0)
            GLES31.glUniform1i(location("mixer"), tables.mixer)
            GLES31.glUniform1i(location("grading"), tables.grading)
            GLES31.glUniform1i(location("vignette"), tables.vignette)
            GLES31.glUniform1i(location("grain"), if (tables.grain != null) 1 else 0)
            GLES31.glUniform1f(location("fleckSize"), tables.grain?.pixelsPerFleck ?: 1f)
            GLES31.glUniform1f(location("grainStrength"), tables.grain?.strength ?: 0f)
            if (renderer.localEdits.isNotEmpty()) {
                val placement = Placement(renderer.edit.framing, frameWidth, frameHeight)
                val view = placement.view
                GLES31.glUniform1f(location("placeLeft"), placement.framing.left)
                GLES31.glUniform1f(location("placeTop"), placement.framing.top)
                GLES31.glUniform1f(location("turnedWidth"), placement.turnedWidth)
                GLES31.glUniform1f(location("turnedHeight"), placement.turnedHeight)
                GLES31.glUniform1f(location("cosA"), view.cos)
                GLES31.glUniform1f(location("sinA"), view.sin)
                GLES31.glUniform1f(location("tiltAcross"), view.tiltAcross)
                GLES31.glUniform1f(location("tiltDown"), view.tiltDown)
                GLES31.glUniform1f(location("mirror"), view.mirror)
                GLES31.glUniform1f(location("wholeWidth"), placement.wholeWidth)
                GLES31.glUniform1f(location("wholeHeight"), placement.wholeHeight)
                GLES31.glUniform1i(location("turns"), placement.turns)
            }
            GLES31.glBindImageTexture(0, result[0], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES31.GL_RGBA8)
            Graphics.check("setting up")

            // Rows of an odd number of 16-bit levels do not end on a four-byte boundary.
            GLES31.glPixelStorei(GLES31.GL_UNPACK_ALIGNMENT, 2)
            val upload = ByteBuffer.allocateDirect(width * bandRows * if (displayed != null) 4 else 2).order(ByteOrder.nativeOrder())
            val download = ByteBuffer.allocateDirect(width * bandRows * 4).order(ByteOrder.nativeOrder())
            val levels = input.levels?.let { listOf(it.red, it.green, it.blue) }
            var bandTop = 0
            while (bandTop < height) {
                val rows = min(bandRows, height - bandTop)
                if (displayed != null) {
                    upload.clear()
                    upload.asIntBuffer().put(displayed, bandTop * width, rows * width)
                    GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
                    GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, planes[0])
                    GLES31.glTexSubImage2D(GLES31.GL_TEXTURE_2D, 0, 0, 0, width, rows, GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, upload)
                } else if (levels != null) {
                    for (index in 0 until 3) {
                        upload.clear()
                        upload.asShortBuffer().put(levels[index], bandTop * width, rows * width)
                        GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + index)
                        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, planes[index])
                        GLES31.glTexSubImage2D(GLES31.GL_TEXTURE_2D, 0, 0, 0, width, rows, GLES31.GL_RED_INTEGER, GLES31.GL_UNSIGNED_SHORT, upload)
                    }
                }
                GLES31.glUniform2i(location("size"), width, rows)
                GLES31.glUniform1i(location("bandTop"), bandTop)
                GLES31.glDispatchCompute((width + GROUP - 1) / GROUP, (rows + GROUP - 1) / GROUP, 1)
                GLES31.glMemoryBarrier(GLES31.GL_FRAMEBUFFER_BARRIER_BIT or GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
                download.clear()
                GLES31.glReadPixels(0, 0, width, rows, GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, download)
                Graphics.check("toning a band")
                download.asIntBuffer().get(output, bandTop * width, rows * width)
                bandTop += rows
            }
        } finally {
            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
            GLES31.glDeleteFramebuffers(1, framebuffer, 0)
            GLES31.glDeleteTextures(3, planes, 0)
            GLES31.glDeleteTextures(1, result, 0)
            GLES31.glDeleteBuffers(4, buffers, 0)
        }
    }

    private fun upload(buffer: Int, bytes: Int, data: java.nio.Buffer) {
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer)
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, data, GLES31.GL_STATIC_DRAW)
    }
}
