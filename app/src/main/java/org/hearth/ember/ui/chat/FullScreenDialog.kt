package org.hearth.ember.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput

/** One full screen layer: what it shows and what the back button does. */
class OverlayLayer(val content: @Composable () -> Unit, val onDismiss: () -> Unit)

/** The layers drawn over the whole app, in order. The activity shows them above everything, behind the status and navigation bars too. */
class OverlayHost {
    val layers = mutableStateListOf<OverlayLayer>()
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHost?> { null }

/** Draws the host's layers over the app. Put this at the root, after the app's own content, inside the theme. */
@Composable
fun OverlayLayers(host: OverlayHost) {
    // The bars' icons are dark on a light app; over black they have to turn light, and go back afterwards.
    val view = androidx.compose.ui.platform.LocalView.current
    val open = host.layers.isNotEmpty()
    DisposableEffect(open) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val (status, nav) = (controller?.isAppearanceLightStatusBars ?: false) to (controller?.isAppearanceLightNavigationBars ?: false)
        if (open) controller?.apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
        onDispose { if (open) controller?.apply { isAppearanceLightStatusBars = status; isAppearanceLightNavigationBars = nav } }
    }
    for (layer in host.layers) key(layer) {
        BackHandler(onBack = layer.onDismiss)
        // Black, and it swallows touches so the chat underneath cannot be tapped through it.
        Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }) { layer.content() }
    }
}

/**
 * Something that covers the whole screen, in either orientation: behind the status and navigation bars and the camera cut-out. A Compose
 * dialog window is always fitted inside the system bars, which left a margin showing the chat around a video, so this is drawn by the
 * activity itself instead. Pad the content with `WindowInsets.safeDrawing` to keep controls clear of the bars.
 */
@Composable
fun FullScreenDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val host = LocalOverlayHost.current ?: return
    val latestContent by rememberUpdatedState(content)
    val latestDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(host) {
        val layer = OverlayLayer({ latestContent() }, { latestDismiss() })
        host.layers.add(layer)
        onDispose { host.layers.remove(layer) }
    }
}
