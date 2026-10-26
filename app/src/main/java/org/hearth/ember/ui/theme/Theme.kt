package org.hearth.ember.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Ember's own colours: a warm ember orange on neutral surfaces, the way WhatsApp and Signal keep chrome quiet and let one colour speak. */
private val EmberOrange = Color(0xFFE8590C)

private val Light = lightColorScheme(
    primary = EmberOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCB),
    onPrimaryContainer = Color(0xFF390C00),
    secondary = Color(0xFF77574A),
    secondaryContainer = Color(0xFFFFDBCE),
    onSecondaryContainer = Color(0xFF2C150C),
    tertiary = Color(0xFF6B5E2F),
    background = Color(0xFFFFFBF8),
    onBackground = Color(0xFF201A17),
    surface = Color(0xFFFFFBF8),
    onSurface = Color(0xFF201A17),
    surfaceVariant = Color(0xFFF5DED5),
    onSurfaceVariant = Color(0xFF53433D),
    surfaceContainer = Color(0xFFF8EEE9),
    surfaceContainerHigh = Color(0xFFF2E7E1),
    surfaceContainerHighest = Color(0xFFECE0DA),
    outline = Color(0xFF85736C),
    outlineVariant = Color(0xFFD8C2B9),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFF8A55),
    onPrimary = Color(0xFF5A1D00),
    primaryContainer = Color(0xFF7D2D00),
    onPrimaryContainer = Color(0xFFFFDBCB),
    secondary = Color(0xFFE7BDAE),
    secondaryContainer = Color(0xFF5D4034),
    onSecondaryContainer = Color(0xFFFFDBCE),
    tertiary = Color(0xFFD7C68D),
    background = Color(0xFF16110E),
    onBackground = Color(0xFFECE0DA),
    surface = Color(0xFF16110E),
    onSurface = Color(0xFFECE0DA),
    surfaceVariant = Color(0xFF53433D),
    onSurfaceVariant = Color(0xFFD8C2B9),
    surfaceContainer = Color(0xFF231B17),
    surfaceContainerHigh = Color(0xFF2E2521),
    surfaceContainerHighest = Color(0xFF3A302B),
    outline = Color(0xFFA08D85),
    outlineVariant = Color(0xFF53433D),
    error = Color(0xFFFFB4AB),
)

/** Colours the message bubbles and the call screen use that Material 3 has no name for. */
@Immutable
data class EmberColors(
    val bubbleMine: Color,
    val onBubbleMine: Color,
    val bubbleTheirs: Color,
    val onBubbleTheirs: Color,
    val online: Color,
    val chatBackground: Color,
    val callBackground: Color,
    val callDanger: Color,
    val tick: Color,
    val tickRead: Color,
)

private val LightEmber = EmberColors(
    bubbleMine = Color(0xFFFFE3D3),
    onBubbleMine = Color(0xFF2B1810),
    bubbleTheirs = Color(0xFFFFFFFF),
    onBubbleTheirs = Color(0xFF201A17),
    online = Color(0xFF2E9E4F),
    chatBackground = Color(0xFFF7EFE9),
    callBackground = Color(0xFF14100E),
    callDanger = Color(0xFFD93025),
    tick = Color(0xFF8F7F78),
    tickRead = Color(0xFF2B8FD6),
)

private val DarkEmber = EmberColors(
    bubbleMine = Color(0xFF6B2A0C),
    onBubbleMine = Color(0xFFFFEDE4),
    bubbleTheirs = Color(0xFF2E2521),
    onBubbleTheirs = Color(0xFFECE0DA),
    online = Color(0xFF4CC36F),
    chatBackground = Color(0xFF110D0B),
    callBackground = Color(0xFF0B0807),
    callDanger = Color(0xFFFF5A4D),
    tick = Color(0xFFA08D85),
    tickRead = Color(0xFF58B4F5),
)

val LocalEmberColors = staticCompositionLocalOf { LightEmber }

object EmberTheme {
    val colors: EmberColors @Composable get() = LocalEmberColors.current
}

private val EmberShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private fun hsl(h: Float, s: Float, l: Float) = Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))
private fun onColor(c: Color) = if (androidx.core.graphics.ColorUtils.calculateLuminance(c.toArgb()) > 0.45) Color(0xFF1B1411) else Color.White

/** A whole colour scheme grown from one accent colour: the surfaces take a faint tint of it, the bubbles and buttons use it. */
private fun accentScheme(accent: Color, dark: Boolean): ColorScheme {
    val hs = FloatArray(3).also { androidx.core.graphics.ColorUtils.colorToHSL(accent.toArgb(), it) }
    val h = hs[0]
    val s = hs[1].coerceAtLeast(0.35f)
    return if (!dark) lightColorScheme(
        primary = accent, onPrimary = onColor(accent),
        primaryContainer = hsl(h, 0.85f, 0.90f), onPrimaryContainer = hsl(h, 0.9f, 0.14f),
        secondary = hsl(h, 0.25f, 0.38f), secondaryContainer = hsl(h, 0.55f, 0.90f), onSecondaryContainer = hsl(h, 0.6f, 0.14f),
        tertiary = hsl((h + 40f) % 360f, 0.35f, 0.38f),
        background = hsl(h, 0.30f, 0.975f), onBackground = hsl(h, 0.2f, 0.10f),
        surface = hsl(h, 0.30f, 0.975f), onSurface = hsl(h, 0.2f, 0.10f),
        surfaceVariant = hsl(h, 0.25f, 0.90f), onSurfaceVariant = hsl(h, 0.15f, 0.30f),
        surfaceContainer = hsl(h, 0.28f, 0.94f), surfaceContainerHigh = hsl(h, 0.27f, 0.92f), surfaceContainerHighest = hsl(h, 0.26f, 0.89f),
        outline = hsl(h, 0.10f, 0.48f), outlineVariant = hsl(h, 0.20f, 0.82f), error = Color(0xFFBA1A1A),
    ) else {
        val primary = hsl(h, s.coerceAtMost(0.95f), 0.68f)
        darkColorScheme(
            primary = primary, onPrimary = onColor(primary),
            primaryContainer = hsl(h, 0.6f, 0.26f), onPrimaryContainer = hsl(h, 0.9f, 0.90f),
            secondary = hsl(h, 0.4f, 0.80f), secondaryContainer = hsl(h, 0.3f, 0.25f), onSecondaryContainer = hsl(h, 0.6f, 0.90f),
            tertiary = hsl((h + 40f) % 360f, 0.4f, 0.75f),
            background = hsl(h, 0.16f, 0.07f), onBackground = hsl(h, 0.1f, 0.90f),
            surface = hsl(h, 0.16f, 0.07f), onSurface = hsl(h, 0.1f, 0.90f),
            surfaceVariant = hsl(h, 0.12f, 0.22f), onSurfaceVariant = hsl(h, 0.12f, 0.78f),
            surfaceContainer = hsl(h, 0.16f, 0.11f), surfaceContainerHigh = hsl(h, 0.15f, 0.14f), surfaceContainerHighest = hsl(h, 0.14f, 0.18f),
            outline = hsl(h, 0.08f, 0.58f), outlineVariant = hsl(h, 0.10f, 0.26f), error = Color(0xFFFFB4AB),
        )
    }
}

/** The same dark scheme on pure black, as on an OLED screen. */
private fun blackened(c: ColorScheme) = c.copy(
    background = Color.Black, surface = Color.Black,
    surfaceContainer = Color(0xFF0C0C0C), surfaceContainerHigh = Color(0xFF151515), surfaceContainerHighest = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF242424),
)

@Composable
fun EmberTheme(dark: Boolean = isSystemInDarkTheme(), dynamic: Boolean = false, accent: Int? = null, black: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val custom = accent?.let { Color(it) }
    var scheme: ColorScheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        custom != null -> accentScheme(custom, dark)
        dark -> Dark
        else -> Light
    }
    if (dark && black) scheme = blackened(scheme)
    val base = if (dark) DarkEmber else LightEmber
    val h = custom?.let { c -> FloatArray(3).also { androidx.core.graphics.ColorUtils.colorToHSL(c.toArgb(), it) }[0] }
    var colors = if (h == null || dynamic) base else if (dark) base.copy(
        bubbleMine = hsl(h, 0.55f, 0.22f), onBubbleMine = hsl(h, 0.9f, 0.93f), bubbleTheirs = hsl(h, 0.14f, 0.15f), onBubbleTheirs = hsl(h, 0.1f, 0.90f),
        chatBackground = hsl(h, 0.16f, 0.06f), callBackground = hsl(h, 0.16f, 0.04f),
    ) else base.copy(
        bubbleMine = hsl(h, 0.85f, 0.89f), onBubbleMine = hsl(h, 0.6f, 0.10f), bubbleTheirs = Color.White, onBubbleTheirs = hsl(h, 0.2f, 0.10f),
        chatBackground = hsl(h, 0.30f, 0.95f),
    )
    if (dark && black) colors = colors.copy(chatBackground = Color.Black, callBackground = Color.Black, bubbleTheirs = Color(0xFF1A1A1A))
    CompositionLocalProvider(LocalEmberColors provides colors) {
        MaterialTheme(colorScheme = scheme, shapes = EmberShapes, content = content)
    }
}
