package com.byeonggon.latent.ui.viewer

import android.net.Uri
import android.view.LayoutInflater
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.byeonggon.latent.R
import java.io.File
import kotlinx.coroutines.delay

/** How long the view stays hidden for the size of the picture before it is shown anyway. */
private const val SIZE_WAITS_MS = 1_500L

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
    // Until the player knows how large the picture is, its view fills the space and the first frames are stretched to it.
    // The view stays hidden (the poster behind it shows) until the size is known and the layout has followed.
    var sized by remember(player) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) sized = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // A video without a picture, or one that is slow to say, must not leave the controls hidden.
    LaunchedEffect(player) {
        delay(SIZE_WAITS_MS)
        sized = true
    }
    AndroidView(
        factory = { (LayoutInflater.from(it).inflate(R.layout.video_player_view, null, false) as PlayerView).apply { this.player = player } },
        update = { it.player = player },
        modifier = modifier.fillMaxSize().alpha(if (sized) 1f else 0f),
    )
}
