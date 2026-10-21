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
    /** The installed version of the app, which the map servers are told along with its name. */
    version: String,
    private val download: (URL) -> ByteArray? = { download(it, userAgent(version)) },
) {
    /** The bytes at [url], or null if online maps are off or the address could not be read. */
    suspend fun get(url: String): ByteArray? {
        if (!settings.settings.first().onlineMaps) return null
        return download(URL(url))
    }

    internal companion object {
        /** Map servers ask apps to say who they are, and which version, so that they can get in touch if one misbehaves. */
        fun userAgent(version: String) = "Latent/${version.ifBlank { "unknown" }} (+https://github.com/byeonggonlee/Latent)"

        private const val TIMEOUT_MS = 10_000
        private const val LARGEST = 2 * 1024 * 1024

        private fun download(url: URL, userAgent: String): ByteArray? = try {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.setRequestProperty("User-Agent", userAgent)
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
