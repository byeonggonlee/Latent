package com.byeonggon.cameramanager.location

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import com.byeonggon.cameramanager.MainActivity
import com.byeonggon.cameramanager.Notifications
import com.byeonggon.cameramanager.forgetTheApplicationOfEarlierTests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingStoppedTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    private fun stoppedNotification() =
        notifications.allNotifications.firstOrNull { it.channelId == Notifications.CHANNEL_LOCATION_STOPPED }

    private fun restartPhone() =
        RecordingStoppedReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

    @Test
    fun tellsAfterARestartThatRecordingLeftOnHasStopped() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        LocationRecorderService.start(context)
        LocationRecorderService.stop(context)
        assertFalse(LocationRecorderService.isWanted(context))
        restartPhone()
        assertNull("recording was switched off", stoppedNotification())

        LocationRecorderService.start(context)
        // The phone restarting ends the service without the person switching recording off.
        shadowOf(context).clearStartedServices()
        restartPhone()
        val notification = stoppedNotification()
        assertNotNull("recording was left on", notification)
        assertEquals(
            LocationRecorderService.ACTION_RESUME,
            shadowOf(notification!!.contentIntent).savedIntent.action,
        )
    }

    @Test
    fun tappingTheNotificationStartsRecordingAgain() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        LocationRecorderService.start(context)
        shadowOf(context).clearStartedServices()

        ShadowEnvironment.addExternalDir("shared")
        forgetTheApplicationOfEarlierTests()
        Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(context, MainActivity::class.java).setAction(LocationRecorderService.ACTION_RESUME),
        ).setup()

        val started = shadowOf(context).nextStartedService
        assertEquals(LocationRecorderService::class.java.name, started?.component?.className)
        assertTrue(LocationRecorderService.isWanted(context))
        LocationRecorderService.stop(context)
    }
}
