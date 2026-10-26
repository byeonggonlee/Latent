package com.byeonggon.latent.ui.common

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Latent's own colours (docs/UI_GUIDE.md §3), in place of the wallpaper's: the amber of the app icon for what is
 * active or chosen, and greys with no hue at all (red, green and blue equal), since a tinted grey next to a photo
 * shifts how its colours look. Every role Material has is set, so none falls back to Material's own purple.
 */

/** The app's screens in the light theme. */
internal val LatentLight = lightColorScheme(
    primary = Color(0xFF8A5800),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDB0),
    onPrimaryContainer = Color(0xFF2B1700),
    inversePrimary = Color(0xFFF2B95C),
    // One accent: what Material shows in its second and third colours (a chosen chip, the tab under the finger) is the amber too.
    secondary = Color(0xFF4A4A4A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDDB0),
    onSecondaryContainer = Color(0xFF2B1700),
    tertiary = Color(0xFF8A5800),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB0),
    onTertiaryContainer = Color(0xFF2B1700),
    background = Color(0xFFF7F7F7),
    onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFF7F7F7),
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFE6E6E6),
    onSurfaceVariant = Color(0xFF4A4A4A),
    // Raised surfaces are a grey a step darker, not tinted with the accent.
    surfaceTint = Color(0xFF1B1B1B),
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = Color(0xFFF1F1F1),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF7A7A7A),
    outlineVariant = Color(0xFFD4D4D4),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7F7F7),
    surfaceDim = Color(0xFFDADADA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3F3),
    surfaceContainer = Color(0xFFEFEFEF),
    surfaceContainerHigh = Color(0xFFE6E6E6),
    surfaceContainerHighest = Color(0xFFE0E0E0),
)

/** The app's screens in the dark theme. */
internal val LatentDark = darkColorScheme(
    primary = Color(0xFFF2B95C),
    onPrimary = Color(0xFF432C00),
    primaryContainer = Color(0xFF5E3F00),
    onPrimaryContainer = Color(0xFFFFDDB0),
    inversePrimary = Color(0xFF8A5800),
    secondary = Color(0xFFB4B4B4),
    onSecondary = Color(0xFF1F1F1F),
    secondaryContainer = Color(0xFF5E3F00),
    onSecondaryContainer = Color(0xFFFFDDB0),
    tertiary = Color(0xFFF2B95C),
    onTertiary = Color(0xFF432C00),
    tertiaryContainer = Color(0xFF5E3F00),
    onTertiaryContainer = Color(0xFFFFDDB0),
    background = Color(0xFF131313),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF131313),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB4B4B4),
    surfaceTint = Color(0xFFE6E6E6),
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF303030),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF8E8E8E),
    outlineVariant = Color(0xFF3A3A3A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF393939),
    surfaceDim = Color(0xFF131313),
    surfaceContainerLowest = Color(0xFF0E0E0E),
    surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF1F1F1F),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF353535),
)

/**
 * The viewer, the editor and comparing: dark and neutral whatever the theme, with the amber kept to the control in
 * use. The surround is [PictureBackground]; panels and bars are `surface`.
 */
internal val WorkspaceColors = darkColorScheme(
    primary = Color(0xFFF2B95C),
    onPrimary = Color(0xFF432C00),
    primaryContainer = Color(0xFF5E3F00),
    onPrimaryContainer = Color(0xFFFFDDB0),
    inversePrimary = Color(0xFF8A5800),
    secondary = Color(0xFFA6A6A6),
    onSecondary = Color(0xFF1A1A1A),
    // A chosen tool's backing stays grey: next to a photo, a large patch of amber would shift how it looks.
    secondaryContainer = Color(0xFF3A3A3A),
    onSecondaryContainer = Color(0xFFEDEDED),
    tertiary = Color(0xFFF2B95C),
    onTertiary = Color(0xFF432C00),
    tertiaryContainer = Color(0xFF3A3A3A),
    onTertiaryContainer = Color(0xFFEDEDED),
    background = Color(0xFF0F0F0F),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF232323),
    onSurfaceVariant = Color(0xFFA6A6A6),
    surfaceTint = Color(0xFFEDEDED),
    inverseSurface = Color(0xFFEDEDED),
    inverseOnSurface = Color(0xFF1A1A1A),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF6E6E6E),
    outlineVariant = Color(0xFF2B2B2B),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2E2E2E),
    surfaceDim = Color(0xFF0F0F0F),
    surfaceContainerLowest = Color(0xFF0F0F0F),
    surfaceContainerLow = Color(0xFF151515),
    surfaceContainer = Color(0xFF1A1A1A),
    surfaceContainerHigh = Color(0xFF212121),
    surfaceContainerHighest = Color(0xFF2A2A2A),
)

/**
 * What is laid over a photo itself, in any theme (UI_GUIDE §3.3): a backing at least this dark keeps white text
 * readable even over a white sky, where a lighter one does not.
 */
object OverPhoto {
    /** The backing of a badge or a control over a photo. */
    val Scrim = Color.Black.copy(alpha = 0.6f)

    /** The only colour for text and icons on [Scrim]. */
    val Content = Color.White

    /** A failure shown over a photo: white on this, with a white edge. */
    val Failure = Color(0xFFB3261E)
}
