package com.byeonggon.latent.enhance

import java.io.File
import java.io.IOException

/** How an edited photo and its original are put in place. */
interface PhotoFiles {
    fun exists(file: File): Boolean

    /** Moves [from] to [to], in place of [to] if that exists. */
    @Throws(IOException::class)
    fun replace(from: File, to: File)

    /** Straight through the paths, for files the app may change that way. */
    object Direct : PhotoFiles {
        override fun exists(file: File) = file.exists()

        override fun replace(from: File, to: File) {
            if (!from.renameTo(to)) throw IOException("Cannot move ${from.name}")
        }
    }
}
