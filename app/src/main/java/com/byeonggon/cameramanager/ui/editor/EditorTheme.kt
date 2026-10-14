package com.byeonggon.cameramanager.ui.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The editor is dark whatever the system's theme, as photo editors are: bright surroundings make a
 * picture look darker than it is, and coloured ones pull its colours towards theirs.
 */
@Composable
internal fun EditorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = EditorColors, typography = MaterialTheme.typography, content = content)
}

private val EditorColors = darkColorScheme(
    primary = Color(0xFF7FA8FF),
    onPrimary = Color(0xFF0B1B3D),
    primaryContainer = Color(0xFF3B6EF0),
    onPrimaryContainer = Color.White,
    secondaryContainer = Color(0xFF3A3C42),
    onSecondaryContainer = Color(0xFFE8E8EA),
    background = Color(0xFF0E0F11),
    onBackground = Color(0xFFE8E8EA),
    surface = Color(0xFF17181B),
    onSurface = Color(0xFFE8E8EA),
    surfaceVariant = Color(0xFF222327),
    onSurfaceVariant = Color(0xFFA3A5AC),
    surfaceContainerLowest = Color(0xFF0E0F11),
    surfaceContainerLow = Color(0xFF141518),
    surfaceContainer = Color(0xFF17181B),
    surfaceContainerHigh = Color(0xFF1E1F23),
    surfaceContainerHighest = Color(0xFF26272B),
    outline = Color(0xFF45474D),
    outlineVariant = Color(0xFF2A2B30),
)

/** Behind the picture: a little lighter than black, so the edges of dark photos still show. */
internal val PictureBackground = Color(0xFF0E0F11)
