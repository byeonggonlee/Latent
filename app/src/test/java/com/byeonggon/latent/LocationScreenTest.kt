package com.byeonggon.latent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.exifinterface.media.ExifInterface
import com.byeonggon.latent.data.db.LocationPointEntity
import com.byeonggon.latent.data.db.LocationSource
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.location.LocationRecorderService
import com.byeonggon.latent.ui.location.LocationScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment
import java.io.File
import java.time.OffsetDateTime

/**
 * The location screen: recording starts only with the location permission, a Timeline export is read from the file the
 * person picked, and photos without a place are given one from the places known, written into the library's own files
 * only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocationScreenTest {
    private val application get() = RuntimeEnvironment.getApplication()
    private val container get() = application.appContainer

    @get:Rule(order = 0)
    val files = TemporaryFolder()

    @get:Rule(order = 1)
    val environment = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
            grantLibraryAccess()
        }
    }

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun millis(text: String) = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.waitForIdle()
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun show() {
        compose.setContent { LocationScreen() }
        compose.waitForIdle()
    }

    /** Switches recording on and answers the system's permission dialog, having [granted] location or not. */
    private fun switchRecordingOn(granted: Boolean) {
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        val asked = shadowOf(compose.activity).lastRequestedPermission
        assertNotNull("No permission was asked for", asked)
        assertTrue(asked.requestedPermissions.toList().containsAll(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)))
        if (granted) shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val result = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        // Answered past the framework's own bookkeeping of the request, so a test can answer only one.
        @Suppress("DEPRECATION")
        compose.activity.onRequestPermissionsResult(asked.requestCode, asked.requestedPermissions, IntArray(asked.requestedPermissions.size) { result })
        compose.waitForIdle()
    }

    /** What services were started, by class name. */
    private fun startedServices(): List<String> = generateSequence { shadowOf(application).nextStartedService }.map { it.component!!.className }.toList()

    @Test
    fun recordsNothingWhenLocationIsRefused() {
        show()

        switchRecordingOn(granted = false)

        compose.onNodeWithText("Recording cannot start without location access.").assertIsDisplayed()
        assertTrue(LocationRecorderService::class.java.name !in startedServices())
        assertFalse(LocationRecorderService.isWanted(application))
    }

    @Test
    fun startsRecordingOnceLocationIsAllowed() {
        show()

        switchRecordingOn(granted = true)

        assertEquals(0, compose.onAllNodesWithText("Recording cannot start without location access.").fetchSemanticsNodes().size)
        assertTrue(LocationRecorderService::class.java.name in startedServices())
        assertTrue(LocationRecorderService.isWanted(application))
    }

    @Test
    fun readsTheTimelineFromTheFileThePersonPicked() {
        val timeline = File(files.root, "Timeline.json").apply {
            writeText(
                """
                {"semanticSegments": [{
                  "startTime": "2026-10-01T09:00:00.000+09:00",
                  "endTime": "2026-10-01T11:00:00.000+09:00",
                  "timelinePath": [
                    {"point": "37.5665°, 126.9780°", "time": "2026-10-01T09:05:00.000+09:00"},
                    {"point": "37.5700°, 126.9820°", "time": "2026-10-01T09:20:00.000+09:00"}
                  ]
                }]}
                """.trimIndent(),
            )
        }
        show()

        compose.onNodeWithText("Choose Timeline.json").performScrollTo().performClick()
        val asked = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, asked.intent.action)
        shadowOf(compose.activity).receiveResult(asked.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(timeline)))

        waitForText("Added 2 new of 2 locations")
        waitForText("2 locations stored")
        assertEquals(2, runBlocking { container.database.locationDao().count() })
    }

    @Test
    fun saysSoWhenThePickedFileIsNoTimeline() {
        val other = File(files.root, "notes.json").apply { writeText("[1, 2, 3]") }
        show()

        compose.onNodeWithText("Choose Timeline.json").performScrollTo().performClick()
        val asked = shadowOf(compose.activity).nextStartedActivityForResult
        shadowOf(compose.activity).receiveResult(asked.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(other)))

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle()
            listOf("This is not a Timeline export.", "No locations found.", "Could not read the file").any {
                compose.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
        assertEquals(0, runBlocking { container.database.locationDao().count() })
    }

    @Test
    fun placesPhotosFromTheKnownPlacesAndWritesOnlyIntoTheLibrarysOwnFiles() {
        val takenAt = millis("2026-10-01T09:10:00+09:00")
        val folder = File(container.storage.root, "2026/2026-10-01").apply { mkdirs() }
        val own = File(folder, "DSC00001.JPG")
        val phone = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera/PXL_0001.jpg").apply { parentFile!!.mkdirs() }
        for (file in listOf(own, phone)) {
            file.outputStream().use { Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        val phoneBytes = phone.readBytes()
        val ids = runBlocking {
            container.database.locationDao().addNew(
                listOf(
                    LocationPointEntity(timestamp = takenAt - 60_000, latitude = 37.5665, longitude = 126.9780, source = LocationSource.TIMELINE),
                    LocationPointEntity(timestamp = takenAt + 60_000, latitude = 37.5665, longitude = 126.9780, source = LocationSource.TIMELINE),
                ),
            )
            listOf(own to MediaOrigin.LIBRARY, phone to MediaOrigin.PHONE).map { (file, origin) ->
                container.database.mediaDao().insert(
                    MediaItemEntity(path = file.path, fileName = file.name, kind = MediaKind.PHOTO, sizeBytes = file.length(), capturedAt = takenAt, importedAt = 1, origin = origin),
                )
            }
        }
        show()
        waitForText("2 items without a location")

        compose.onNodeWithText("Match items without a location again").performScrollTo().performClick()
        waitForText("Added locations to 2 items.")

        for (id in ids) {
            val item = runBlocking { container.database.mediaDao().byId(id)!! }
            assertEquals(37.5665, item.latitude!!, 1e-4)
            assertEquals(126.9780, item.longitude!!, 1e-4)
        }
        val written = ExifInterface(own).latLong
        assertNotNull("The library's photo was not tagged", written)
        assertEquals(37.5665, written!![0], 1e-4)
        // A photo another app made keeps its file as it was; its place is in the database only.
        assertArrayEquals(phoneBytes, phone.readBytes())
        assertNull(ExifInterface(phone).latLong)
        waitForText("0 items without a location")
        compose.onNodeWithText("Match items without a location again").assertIsNotEnabled()
    }
}
