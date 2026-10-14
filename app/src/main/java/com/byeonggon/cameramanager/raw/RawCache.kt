package com.byeonggon.cameramanager.raw

import java.io.File
import java.io.IOException

/**
 * Keeps the raw file that was decoded last. Decoding takes a moment and the result is large, and
 * the viewer and the editor both need it for the photo that is open.
 */
class RawCache {
    private var file: File? = null
    private var image: ArwImage? = null

    /** Slow unless [file] is the one read last; must run off the main thread. */
    @Synchronized
    @Throws(IOException::class)
    fun read(file: File): ArwImage {
        image?.takeIf { file == this.file }?.let { return it }
        // Let go of the previous file first: two decoded files at once are more than is needed.
        clear()
        return RawRenderer.read(file).also {
            this.file = file
            image = it
        }
    }

    @Synchronized
    fun clear() {
        file = null
        image = null
    }
}
