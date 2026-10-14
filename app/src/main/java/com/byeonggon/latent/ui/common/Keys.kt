package com.byeonggon.latent.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Lets a screen take keys from a keyboard, as on a laptop or a tablet with a keyboard attached:
 * [onKey] is given each key that is pressed, and says whether it did something with it.
 */
@Composable
fun Modifier.keys(onKey: (KeyEvent) -> Boolean): Modifier {
    val focus = remember { FocusRequester() }
    // Without focus nothing receives keys; the screen takes it as it opens.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    return this
        .focusRequester(focus)
        .onKeyEvent { event -> event.type == KeyEventType.KeyDown && onKey(event) }
        // A focus target, not a focusable: the latter would merge the whole screen into one element for TalkBack.
        .focusTarget()
}
