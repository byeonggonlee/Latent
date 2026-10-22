package com.byeonggon.latent.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.byeonggon.latent.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.room.Room

/**
 * Looks at the real MediaStore of the phone: which folders hold photos, and how long that takes. It only
 * reads; the app's own database is left alone (the scan runs against an empty one in memory).
 *
 * Run it with `adb shell am instrument`, not with Gradle's connectedAndroidTest, which uninstalls the app.
 * The app needs the media permissions first: `adb shell pm grant com.latentimage.latent.debug <permission>`.
 */
@RunWith(AndroidJUnit4::class)
class PhoneLibraryOnDeviceTest {
    @Test
    fun readsThePhonesFolders() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val library = PhoneLibrary(context, database)
            val startedAt = System.nanoTime()
            val folders = library.folders()
            Log.i(TAG, "on device: ${folders.size} folders in ${(System.nanoTime() - startedAt) / 1_000_000} ms")
            folders.take(10).forEach { Log.i(TAG, "on device: folder ${it.path} has ${it.count}") }

            // Listing the camera's folder into the scratch database, timed: nothing of the app's is written.
            val scanStartedAt = System.nanoTime()
            val camera = folders.map { it.path }.filter { it == PhoneLibrary.CAMERA_FOLDER }.toSet()
            val added = library.refresh(camera)
            Log.i(TAG, "on device: listed $added photos of $camera in ${(System.nanoTime() - scanStartedAt) / 1_000_000} ms")
            val items = database.mediaDao().all()
            Log.i(TAG, "on device: ${items.count { it.latitude != null }} of ${items.size} have a place")
            assertTrue(folders.none { folder -> PhoneLibrary.LIBRARY_FOLDERS.any(folder.path::startsWith) })
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TAG = "PhoneLibrary"
    }
}
