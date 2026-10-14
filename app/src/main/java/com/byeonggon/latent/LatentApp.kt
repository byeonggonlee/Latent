package com.byeonggon.latent

import android.app.Application
import android.content.Context
import androidx.room.Room
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.byeonggon.latent.camera.SyncEngine
import com.byeonggon.latent.camera.UsbCameraConnector
import com.byeonggon.latent.data.EditSaver
import com.byeonggon.latent.data.LibraryEdits
import com.byeonggon.latent.data.Thumbnails
import com.byeonggon.latent.data.LibraryIndex
import com.byeonggon.latent.data.LibraryStorage
import com.byeonggon.latent.data.SettingsRepository
import com.byeonggon.latent.data.Trash
import com.byeonggon.latent.data.db.AppDatabase
import com.byeonggon.latent.location.Geotagger
import com.byeonggon.latent.location.TimelineImporter
import com.byeonggon.latent.raw.RawCache
import com.byeonggon.latent.ui.common.ThumbnailFetcher

class LatentApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
                add(ThumbnailFetcher.Factory(container.thumbnails))
            }
            .build()
}

/** App-wide singletons, wired by hand. */
class AppContainer(context: Context) {
    val database: AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "latent.db")
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
    val settings = SettingsRepository(context)
    val storage = LibraryStorage(context)
    val libraryIndex = LibraryIndex(storage, database) { editSaver.saving.value }
    val trash = Trash(storage, database.mediaDao())
    val usb = UsbCameraConnector(context)
    val geotagger = Geotagger(database.locationDao(), database.mediaDao(), storage)
    val rawCache = RawCache()
    val thumbnails = Thumbnails(context)
    val libraryEdits = LibraryEdits(storage, database.mediaDao(), database.filterDao(), geotagger, rawCache)
    val editSaver = EditSaver(context, libraryEdits)
    val syncEngine = SyncEngine(storage, database, settings, geotagger)
    val timelineImporter = TimelineImporter(context, database.locationDao())
}

val Context.appContainer: AppContainer
    get() = (applicationContext as LatentApp).container
