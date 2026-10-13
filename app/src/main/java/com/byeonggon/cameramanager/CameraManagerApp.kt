package com.byeonggon.cameramanager

import android.app.Application
import android.content.Context
import androidx.room.Room
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.byeonggon.cameramanager.camera.SyncEngine
import com.byeonggon.cameramanager.camera.UsbCameraConnector
import com.byeonggon.cameramanager.data.LibraryStorage
import com.byeonggon.cameramanager.data.SettingsRepository
import com.byeonggon.cameramanager.data.db.AppDatabase
import com.byeonggon.cameramanager.location.Geotagger
import com.byeonggon.cameramanager.location.TimelineImporter

class CameraManagerApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}

/** App-wide singletons, wired by hand. */
class AppContainer(context: Context) {
    val database: AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "camera-manager.db").build()
    val settings = SettingsRepository(context)
    val storage = LibraryStorage(context)
    val usb = UsbCameraConnector(context)
    val geotagger = Geotagger(database.locationDao(), database.mediaDao(), storage)
    val syncEngine = SyncEngine(storage, database, settings, geotagger)
    val timelineImporter = TimelineImporter(context, database.locationDao())
}

val Context.appContainer: AppContainer
    get() = (applicationContext as CameraManagerApp).container
