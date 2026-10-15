package com.byeonggon.latent.ui.viewer

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * Plays the video at [path] in place, with the player's own controls. It starts at once and lets go of
 * everything when it leaves the screen. [onFailed] is called if the video cannot be played here, so that
 * another app can be asked to.
 */
@Composable
fun VideoPlayer(path: String, onFailed: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val failed by rememberUpdatedState(onFailed)
    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) = failed()
            })
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        update = { it.player = player },
        modifier = modifier.fillMaxSize(),
    )
}
