package com.byeonggon.latent.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The viewer and the editor are dark whatever the system's theme, as photo apps are: bright
 * surroundings make a picture look darker than it is, and coloured ones pull its colours towards theirs.
 */
@Composable
fun PhotoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = WorkspaceColors, typography = MaterialTheme.typography, content = content)
}

/** Behind the picture: a little lighter than black, so the edges of dark photos still show. */
val PictureBackground = Color(0xFF0F0F0F)
