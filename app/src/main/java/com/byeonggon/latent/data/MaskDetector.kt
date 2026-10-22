package com.byeonggon.latent.data

import android.util.Log
import com.byeonggon.latent.enhance.DetectedKind
import com.byeonggon.latent.enhance.Detection
import com.byeonggon.latent.enhance.LinearImage
import com.byeonggon.latent.enhance.MaskStore
import com.byeonggon.latent.enhance.Segmenter
import com.byeonggon.latent.enhance.SegmenterException
import com.byeonggon.latent.enhance.SubjectMasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** What came of asking for a mask of what a model finds. */
sealed interface DetectionResult {
    /** Found, and its outline kept under the detection's id. */
    data class Found(val detection: Detection) : DetectionResult

    /** The model ran, and nothing in the picture stands out. */
    data object NothingFound : DetectionResult

    /** The model ran but failed, or the outline could not be kept; trying again may help. */
    data object Failed : DetectionResult

    /** The model cannot run on this phone at all. */
    data object Unavailable : DetectionResult
}

/**
 * Finds subjects and skies in pictures and keeps their outlines in [MaskStore], one picture at a time. Each kind's model
 * is made the first time it is needed, never at start, and kept for the next picture. Where one cannot be made, as on a
 * phone the runtime does not support, its kind is [unavailable] for as long as the app runs.
 */
class MaskDetector(
    /** Makes the model for a kind; throws, with an exception or an error such as a missing native library, where it cannot run. */
    internal var makeSegmenter: (DetectedKind) -> Segmenter,
) {
    private val turn = Mutex()
    private val segmenters = HashMap<DetectedKind, Segmenter>()
    private val _unavailable = MutableStateFlow<Set<DetectedKind>>(emptySet())

    /** The kinds whose model cannot run here, as far as is known: those it failed to make. */
    val unavailable: StateFlow<Set<DetectedKind>> = _unavailable.asStateFlow()

    /** Finds the [kind] of thing in [picture], the whole upright picture as the camera made it, and keeps its outline. */
    suspend fun detect(picture: LinearImage, kind: DetectedKind): DetectionResult = turn.withLock {
        withContext(Dispatchers.Default) {
            val model = segmenters[kind] ?: try {
                makeSegmenter(kind).also { segmenters[kind] = it }
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "No memory to make the model", e)
                return@withContext DetectionResult.Failed
            } catch (e: Exception) {
                return@withContext unavailable(kind, e)
            } catch (e: LinkageError) {
                // Native code that cannot load shows up as an error, not an exception.
                return@withContext unavailable(kind, e)
            }
            try {
                val outline = SubjectMasks.outline(picture, model, kind) ?: return@withContext DetectionResult.NothingFound
                val id = MaskStore.newId()
                MaskStore.save(id, outline)
                DetectionResult.Found(Detection(kind, id, model.model, model.modelVersion))
            } catch (e: SegmenterException) {
                Log.w(TAG, "Finding the ${kind.name.lowercase()} failed", e)
                // Made afresh next time, on every accelerator again.
                segmenters.remove(kind)
                runCatching { model.close() }
                DetectionResult.Failed
            } catch (e: IOException) {
                Log.w(TAG, "The outline could not be kept", e)
                DetectionResult.Failed
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Finding the ${kind.name.lowercase()} ran out of memory", e)
                DetectionResult.Failed
            }
        }
    }

    private fun unavailable(kind: DetectedKind, cause: Throwable): DetectionResult {
        Log.w(TAG, "The ${kind.name.lowercase()} model cannot run on this phone", cause)
        _unavailable.value = _unavailable.value + kind
        return DetectionResult.Unavailable
    }

    private companion object {
        const val TAG = "SubjectMask"
    }
}
