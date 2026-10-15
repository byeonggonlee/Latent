package com.byeonggon.latent.map

import com.byeonggon.latent.data.SettingsRepository
import kotlinx.coroutines.flow.first
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one place the app reaches the internet from. The app can use the internet only for the map,
 * and only after the person has turned online maps on: until then nothing here makes a request.
 */
class OnlineAccess(
    private val settings: SettingsRepository,
    private val download: (URL) -> ByteArray? = ::download,
) {
    /** The bytes at [url], or null if online maps are off or the address could not be read. */
    suspend fun get(url: String): ByteArray? {
        if (!settings.settings.first().onlineMaps) return null
        return download(URL(url))
    }

    private companion object {
        /** Map servers ask apps to say who they are, so that they can get in touch if one misbehaves. */
        const val USER_AGENT = "Latent/0.1 (+https://github.com/byeonggonlee/Latent)"
        const val TIMEOUT_MS = 10_000
        const val LARGEST = 2 * 1024 * 1024

        fun download(url: URL): ByteArray? = try {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                if (connection.responseCode != HttpURLConnection.HTTP_OK) null
                else connection.inputStream.use { input -> input.readNBytes(LARGEST + 1).takeIf { it.size <= LARGEST } }
            } finally {
                connection.disconnect()
            }
        } catch (e: java.io.IOException) {
            null
        }
    }
}
