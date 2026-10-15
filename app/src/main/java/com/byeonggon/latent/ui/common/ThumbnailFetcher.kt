package com.byeonggon.latent.ui.common

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.byeonggon.latent.data.Thumbnails
import okio.Path.Companion.toOkioPath
import java.io.File

/** A library photo as the grid shows it: [sizeBytes] tells an edited photo from the one before; [edit] is kept as settings and shown too. */
data class Thumbnail(val path: String, val sizeBytes: Long, val edit: String? = null)

/** Hands the image loader a photo's stored thumbnail, made on the way if need be, or else the photo. */
class ThumbnailFetcher(private val data: Thumbnail, private val options: Options, private val thumbnails: Thumbnails) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val photo = File(data.path)
        val file = thumbnails.of(photo, data.sizeBytes, data.edit) ?: photo
        return SourceFetchResult(
            source = ImageSource(file.toOkioPath(), options.fileSystem),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val thumbnails: Thumbnails) : Fetcher.Factory<Thumbnail> {
        override fun create(data: Thumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            ThumbnailFetcher(data, options, thumbnails)
    }
}
