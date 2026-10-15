package com.byeonggon.latent.enhance

import android.content.res.AssetManager
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType

/** Where the model runs: the graphics processor, or the processor's cores (XNNPACK). */
enum class SegmenterAccelerator(internal val litert: Accelerator) {
    GPU(Accelerator.GPU),
    CPU(Accelerator.CPU),
}

/**
 * U²-Netp (Qin et al., Apache 2.0), a small salient object model, run by LiteRT from the app's own
 * assets: nothing is downloaded. See docs/AI_MASKS.md for where the file came from and how it was made.
 *
 * It is tried on each of [accelerators] in turn, the graphics processor first: where one cannot be set
 * up, or fails while running, the next one takes over for as long as this lives. Which one ran and how
 * long it took are logged under [LOG_TAG], for reading on a phone with `adb logcat -s SubjectMask`.
 */
class LiteRtSegmenter(
    private val assets: AssetManager,
    accelerators: List<SegmenterAccelerator> = DEFAULT_ACCELERATORS,
) : Segmenter {
    override val model = MODEL
    override val modelVersion = MODEL_VERSION

    private val untried = ArrayDeque(accelerators)

    /** The model as it runs now; null once it has failed on every accelerator, or was closed. */
    private var running: Running? = next() ?: throw SegmenterException("The model could not be set up on ${accelerators.joinToString()}")

    /** The accelerator the model runs on now. */
    val accelerator: SegmenterAccelerator? get() = running?.accelerator

    /** The picture size the model takes, read from the model itself. */
    val inputWidth: Int get() = running?.width ?: 0
    val inputHeight: Int get() = running?.height ?: 0

    /** The model compiled for one accelerator, with its input and output and what they hold. */
    private class Running(
        val accelerator: SegmenterAccelerator,
        val compiled: CompiledModel,
        val inputs: List<TensorBuffer>,
        val outputs: List<TensorBuffer>,
        val width: Int,
        val height: Int,
    ) {
        var runs = 0

        fun close() {
            inputs.forEach { it.close() }
            outputs.forEach { it.close() }
            compiled.close()
        }
    }

    /** Sets the model up on the next accelerator that works; null when none is left. */
    private fun next(): Running? {
        while (untried.isNotEmpty()) {
            val accelerator = untried.removeFirst()
            val started = System.nanoTime()
            try {
                val compiled = CompiledModel.create(assets, ASSET, CompiledModel.Options(accelerator.litert))
                try {
                    val inputType = compiled.getInputTensorType(INPUT)
                    val outputType = compiled.getOutputTensorType(OUTPUT)
                    val input = inputType.layout?.dimensions.orEmpty()
                    val output = outputType.layout?.dimensions.orEmpty()
                    // One picture of floats, rows, columns and red, green and blue in; one channel out, at the same size.
                    val floats = inputType.elementType == TensorType.ElementType.FLOAT && outputType.elementType == TensorType.ElementType.FLOAT
                    if (!floats || input.size != 4 || input[0] != 1 || input[3] != 3 || output != listOf(1, input[1], input[2], 1)) {
                        throw SegmenterException("The model takes $inputType and gives $outputType")
                    }
                    val running = Running(accelerator, compiled, compiled.createInputBuffers(), compiled.createOutputBuffers(), input[2], input[1])
                    Log.i(LOG_TAG, "$MODEL v$MODEL_VERSION on $accelerator: set up in ${millis(started)} ms, input $input, output $output")
                    return running
                } catch (e: Exception) {
                    compiled.close()
                    throw e
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "$MODEL cannot be set up on $accelerator after ${millis(started)} ms", e)
            } catch (e: LinkageError) {
                // The accelerator's native library is missing or will not load on this phone.
                Log.w(LOG_TAG, "$MODEL cannot be set up on $accelerator", e)
            }
        }
        return null
    }

    override fun segment(picture: ShownPicture): Segmentation {
        while (true) {
            val current = running ?: throw SegmenterException("$MODEL cannot run any more")
            val started = System.nanoTime()
            try {
                val values = run(current, picture)
                current.runs++
                Log.i(LOG_TAG, "$MODEL on ${current.accelerator}: ${if (current.runs == 1) "first (cold)" else "warm"} run in ${millis(started)} ms")
                return Segmentation(current.width, current.height, values)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "$MODEL failed on ${current.accelerator}", e)
                current.close()
                running = next()
                if (running == null) throw SegmenterException("$MODEL failed on every accelerator", e)
            }
        }
    }

    /** The model's answer for [picture], stretched to its input size and prepared as its authors' test script does. */
    private fun run(running: Running, picture: ShownPicture): FloatArray {
        val width = running.width
        val height = running.height
        val planes = listOf(picture.red, picture.green, picture.blue).map { resampled(it, picture.width, picture.height, width, height) }
        // Divided by the brightest value, then normalised with ImageNet's means and spreads.
        val brightest = planes.maxOf { plane -> plane.max() }.takeIf { it > 0f } ?: 1f
        val input = FloatArray(width * height * 3)
        for (index in 0 until width * height) {
            for (channel in 0 until 3) {
                input[index * 3 + channel] = (planes[channel][index] / brightest - MEAN[channel]) / SPREAD[channel]
            }
        }
        running.inputs[0].writeFloat(input)
        running.compiled.run(running.inputs, running.outputs)
        val output = running.outputs[0].readFloat()
        if (output.size != width * height) throw SegmenterException("The model gave ${output.size} values for $width by $height")
        // A graphics processor working in half precision can overflow; such an answer is no answer.
        if (output.any { !it.isFinite() }) throw SegmenterException("The model gave values that are not numbers")
        return output
    }

    override fun close() {
        running?.close()
        running = null
    }

    private fun millis(since: Long) = (System.nanoTime() - since) / 1_000_000

    companion object {
        /** Where to read about the model on a phone: `adb logcat -s SubjectMask`. */
        const val LOG_TAG = "SubjectMask"
        const val MODEL = "u2netp"
        const val MODEL_VERSION = 1

        /** The model file among the app's assets. */
        const val ASSET = "u2netp.tflite"

        /** The graphics processor first; the processor's cores where it cannot be used. */
        val DEFAULT_ACCELERATORS = listOf(SegmenterAccelerator.GPU, SegmenterAccelerator.CPU)

        /** The names of the model's input and output in its signature. */
        private const val INPUT = "args_0"
        private const val OUTPUT = "output_0"
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val SPREAD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
