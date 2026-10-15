package com.byeonggon.latent.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plays a real video of the phone the way the viewer does, by its path, and checks that the player
 * gets as far as being ready to play it. Pass the file with `-e videoSample <path>`; it is only read.
 * Run it with `adb shell am instrument`, not with Gradle's connectedAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class VideoPlaybackOnDeviceTest {
    @Test
    fun getsAVideoOfThePhoneReadyToPlay() {
        val sample = InstrumentationRegistry.getArguments().getString("videoSample")?.let(::File)
        assumeTrue(sample != null && sample.isFile)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CountDownLatch(1)
        var failure: String? = null
        var durationMs = 0L
        val startedAt = System.nanoTime()
        Handler(Looper.getMainLooper()).post {
            val player = ExoPlayer.Builder(context).build()
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        durationMs = player.duration
                        player.release()
                        ready.countDown()
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    failure = error.errorCodeName
                    player.release()
                    ready.countDown()
                }
            })
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(sample)))
            player.prepare()
        }
        assertTrue("timed out", ready.await(30, TimeUnit.SECONDS))
        Log.i("VideoPlayback", "on device: ${sample!!.name} ready in ${(System.nanoTime() - startedAt) / 1_000_000} ms, ${durationMs / 1000} s long, failure=$failure")
        assertTrue("player failed with $failure", failure == null)
    }
}
