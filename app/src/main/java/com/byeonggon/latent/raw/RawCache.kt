package com.byeonggon.latent.raw

import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Keeps the raw file that was decoded last. Decoding takes a moment and the result is large, and
 * the viewer and the editor both need it for the photo that is open.
 *
 * One file is decoded at a time, as two at once would need more memory than a phone gives the app. Clearing does
 * not wait for a decode under way, so that the main thread can do it: what that decode gives is then not kept.
 */
class RawCache(private val decode: (File) -> ArwImage = RawRenderer::read) {
    private val decoding = ReentrantLock()

    // Guarded by this object's monitor, which is only ever held for a moment.
    private var file: File? = null
    private var image: ArwImage? = null

    /** Counts the clears, so that a decode can tell whether the cache was cleared while it ran. */
    private var clears = 0

    /** Slow unless [file] is the one read last; must run off the main thread. */
    @Throws(IOException::class)
    fun read(file: File): ArwImage {
        cached(file)?.let { return it }
        decoding.withLock {
            // Decoded by the read before this one, while this one waited for it.
            cached(file)?.let { return it }
            // Let go of the previous file first: two decoded files at once are more than is needed.
            val clearsBefore = synchronized(this) {
                this.file = null
                image = null
                clears
            }
            val decoded = decode(file)
            synchronized(this) {
                if (clears == clearsBefore) {
                    this.file = file
                    image = decoded
                }
            }
            return decoded
        }
    }

    /** Lets go of the decoded file at once, also while one is being decoded. */
    fun clear() {
        synchronized(this) {
            file = null
            image = null
            clears++
        }
    }

    private fun cached(file: File): ArwImage? = synchronized(this) { image?.takeIf { file == this.file } }
}
