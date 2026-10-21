package com.byeonggon.latent.location

import android.content.Context
import android.net.Uri
import com.byeonggon.latent.data.db.LocationDao
import com.byeonggon.latent.data.db.LocationPointEntity
import com.byeonggon.latent.data.db.LocationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

class TimelineImporter(private val context: Context, private val locationDao: LocationDao) {
    data class Result(val parsed: Int, val added: Int, val from: Long?, val to: Long?)

    suspend fun import(uri: Uri): Result = withContext(Dispatchers.IO) {
        var parsed = 0
        var added = 0
        var from: Long? = null
        var to: Long? = null
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        stream.bufferedReader().use { reader ->
            for (chunk in TimelineParser.parse(reader).chunked(BATCH_SIZE)) {
                ensureActive()
                val rows = chunk.map {
                    LocationPointEntity(
                        timestamp = it.timestamp,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        altitude = it.altitude,
                        accuracy = it.accuracy,
                        source = LocationSource.TIMELINE,
                    )
                }
                added += locationDao.addNew(rows)
                parsed += chunk.size
                from = minOf(from ?: Long.MAX_VALUE, chunk.minOf { it.timestamp })
                to = maxOf(to ?: Long.MIN_VALUE, chunk.maxOf { it.timestamp })
            }
        }
        Result(parsed, added, from, to)
    }

    private companion object {
        const val BATCH_SIZE = 2_000
    }
}
