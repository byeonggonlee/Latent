package com.byeonggon.latent.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The pictures a map is put together from, OpenStreetMap's tiles. A tile is kept in memory while it is
 * on screen or near, and on disk for a week, so that looking at the same places again asks the server
 * for nothing; and no more than two are asked for at once, as the server's usage policy has it.
 */
class MapTiles(context: Context, private val online: OnlineAccess, private val urlOf: (Int, Int, Int) -> String = ::openStreetMap) {
    private val folder = File(context.cacheDir, "map-tiles")
    private val memory = LruCache<String, Bitmap>(MEMORY_TILES)
    private val asking = Semaphore(AT_ONCE)

    /** The tile at column [x], row [y] of zoom level [zoom]; null if it cannot be had, such as while online maps are off. */
    suspend fun tile(zoom: Int, x: Int, y: Int): Bitmap? {
        val key = "$zoom/$x/$y"
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = File(folder, "$key.png")
            val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < KEPT_MS
            val bitmap = if (fresh) decode(file.readBytes()) else null
            val loaded = bitmap ?: asking.withPermit { fetch(zoom, x, y, file) } ?: if (file.exists()) decode(file.readBytes()) else null
            loaded?.also { memory.put(key, it) }
        }
    }

    private suspend fun fetch(zoom: Int, x: Int, y: Int, file: File): Bitmap? {
        val bytes = online.get(urlOf(zoom, x, y)) ?: return null
        val bitmap = decode(bytes) ?: return null
        file.parentFile?.mkdirs()
        runCatching { file.writeBytes(bytes) }
        trimIfLarge()
        return bitmap
    }

    private fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    private var writes = 0

    /** Every so often, drops the oldest tiles once there are more than [DISK_BYTES] of them. */
    private fun trimIfLarge() {
        if (++writes % TRIM_EVERY != 0) return
        val files = folder.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toList()
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= DISK_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    private companion object {
        const val MEMORY_TILES = 96
        const val AT_ONCE = 2
        const val KEPT_MS = 7L * 24 * 60 * 60 * 1000
        const val DISK_BYTES = 60L * 1024 * 1024
        const val TRIM_EVERY = 50

        fun openStreetMap(zoom: Int, x: Int, y: Int) = "https://tile.openstreetmap.org/$zoom/$x/$y.png"
    }
}
