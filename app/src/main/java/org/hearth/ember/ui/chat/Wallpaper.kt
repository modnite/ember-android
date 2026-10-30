package org.hearth.ember.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.max
import org.hearth.ember.ember
import org.hearth.ember.ui.theme.EmberTheme

/** The picture behind a chat: a pattern that takes the accent colour, a plain colour, or a photo from the gallery. */
object Wallpapers {
    const val DOODLE = "doodle"
    const val PLAIN = "plain"
    const val GRADIENT = "gradient"
    const val DOTS = "dots"
    const val PHOTO = "photo"
    val presets = listOf(DOODLE to "Doodles", GRADIENT to "Gradient", DOTS to "Dots", PLAIN to "Plain")

    fun photoFile(context: Context) = File(context.filesDir, "chat-wallpaper.jpg")

    /** Copies a picked photo into the app, no bigger than a phone screen needs. */
    fun savePhoto(context: Context, uri: Uri): Boolean = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1800) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })!!
        photoFile(context).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 86, it) }
        true
    }.getOrDefault(false)

    fun label(choice: String) = presets.firstOrNull { it.first == choice }?.second ?: if (choice == PHOTO) "Your photo" else "Doodles"
}

/** Paints a wallpaper. [accent] is what the pattern takes its colour from, [bg] the chat's own background, [dim] (0 to 70) darkens a photo. */
fun DrawScope.paintWallpaper(choice: String, photo: ImageBitmap?, dim: Int, accent: Color, bg: Color) {
    val dark = bg.luminance() < 0.5f
    val d = density
    when {
        choice == Wallpapers.PHOTO && photo != null -> {
            val scale = max(size.width / photo.width, size.height / photo.height)
            val src = IntSize((size.width / scale).toInt().coerceAtLeast(1), (size.height / scale).toInt().coerceAtLeast(1))
            drawImage(photo, srcOffset = IntOffset((photo.width - src.width) / 2, (photo.height - src.height) / 2), srcSize = src, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            if (dim > 0) drawRect(Color.Black.copy(alpha = dim / 100f))
        }
        choice == Wallpapers.PLAIN -> Unit
        choice == Wallpapers.GRADIENT -> drawRect(
            Brush.linearGradient(listOf(accent.copy(alpha = if (dark) 0.30f else 0.26f), accent.copy(alpha = if (dark) 0.04f else 0.03f)), start = Offset.Zero, end = Offset(size.width, size.height)),
        )
        choice == Wallpapers.DOTS -> {
            val gap = 22f * d
            val ink = accent.copy(alpha = if (dark) 0.22f else 0.20f)
            var y = gap / 2
            var row = 0
            while (y < size.height) {
                var x = if (row % 2 == 0) gap / 2 else gap
                while (x < size.width) { drawCircle(ink, radius = 1.6f * d, center = Offset(x, y)); x += gap }
                y += gap * 0.8f; row++
            }
        }
        else -> {
            // Doodles, as in the big messengers: small outlines scattered over a faint wash of the accent colour, turned a little each.
            drawRect(accent.copy(alpha = if (dark) 0.05f else 0.06f))
            val cell = 54f * d
            val ink = accent.copy(alpha = if (dark) 0.20f else 0.17f)
            val stroke = Stroke(width = 1.5f * d, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            var row = 0
            var y = cell / 2
            while (y < size.height + cell) {
                var col = 0
                var x = if (row % 2 == 0) cell / 2 else cell
                while (x < size.width + cell) {
                    val kind = (row * 3 + col * 5 + (row / 2)) % 7
                    val turn = ((row * 37 + col * 53) % 90 - 45).toFloat()
                    rotate(turn, Offset(x, y)) { doodle(kind, Offset(x, y), 7.5f * d, ink, stroke) }
                    x += cell; col++
                }
                y += cell * 0.9f; row++
            }
        }
    }
}

private fun DrawScope.doodle(kind: Int, c: Offset, r: Float, ink: Color, stroke: Stroke) {
    when (kind) {
        0 -> drawCircle(ink, r, c, style = stroke)
        1 -> { drawLine(ink, Offset(c.x - r, c.y), Offset(c.x + r, c.y), stroke.width, stroke.cap); drawLine(ink, Offset(c.x, c.y - r), Offset(c.x, c.y + r), stroke.width, stroke.cap) }
        2 -> drawPath(Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y + r * 0.8f); lineTo(c.x - r, c.y + r * 0.8f); close() }, ink, style = stroke)
        3 -> drawPath(Path().apply { moveTo(c.x - r * 1.2f, c.y); quadraticBezierTo(c.x - r * 0.6f, c.y - r, c.x, c.y); quadraticBezierTo(c.x + r * 0.6f, c.y + r, c.x + r * 1.2f, c.y) }, ink, style = stroke)
        4 -> drawPath(Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close() }, ink, style = stroke)
        5 -> drawPath(Path().apply {
            moveTo(c.x, c.y + r * 0.9f)
            cubicTo(c.x - r * 1.9f, c.y - r * 0.2f, c.x - r * 0.9f, c.y - r * 1.5f, c.x, c.y - r * 0.4f)
            cubicTo(c.x + r * 0.9f, c.y - r * 1.5f, c.x + r * 1.9f, c.y - r * 0.2f, c.x, c.y + r * 0.9f)
        }, ink, style = stroke)
        else -> drawRoundRect(ink, Offset(c.x - r, c.y - r * 0.7f), Size(r * 2, r * 1.4f), androidx.compose.ui.geometry.CornerRadius(r * 0.4f), style = stroke)
    }
}

/** The chat's background: its own colour with the chosen wallpaper over it. */
@Composable
fun Modifier.chatWallpaper(): Modifier {
    val app = LocalContext.current.ember
    val choice by app.prefs.wallpaper.collectAsState()
    val dim by app.prefs.wallpaperDim.collectAsState()
    val version by app.prefs.wallpaperVersion.collectAsState()
    val bg = EmberTheme.colors.chatBackground
    val accent = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val photo = remember(choice, version) { if (choice == Wallpapers.PHOTO) BitmapFactory.decodeFile(Wallpapers.photoFile(context).path)?.asImageBitmap() else null }
    return this.background(bg).drawBehind { paintWallpaper(choice, photo, dim, accent, bg) }
}

/** The picker: a row of small previews (each drawn in the current accent colour), a photo from the gallery and how dark a photo is. */
@Composable
fun WallpaperDialog(onPickPhoto: () -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.ember
    val context = LocalContext.current
    val choice by app.prefs.wallpaper.collectAsState()
    val dim by app.prefs.wallpaperDim.collectAsState()
    val version by app.prefs.wallpaperVersion.collectAsState()
    val bg = EmberTheme.colors.chatBackground
    val accent = MaterialTheme.colorScheme.primary
    val photo = remember(version) { BitmapFactory.decodeFile(Wallpapers.photoFile(context).path)?.asImageBitmap() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat wallpaper") },
        text = {
            Column {
                Text("The doodles take your accent colour, so they change when it does.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                val options = Wallpapers.presets + (if (photo != null) listOf(Wallpapers.PHOTO to "Your photo") else emptyList())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(options.size) { i ->
                        val (id, name) = options[i]
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(width = 72.dp, height = 108.dp).clip(RoundedCornerShape(12.dp)).background(bg)
                                    .drawBehind { paintWallpaper(id, photo, dim, accent, bg) }
                                    .border(if (choice == id) 3.dp else 1.dp, if (choice == id) accent else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                    .clickable { app.prefs.setWallpaper(id) },
                            )
                            Text(name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onPickPhoto) { Text(if (photo != null) "Choose another photo" else "Choose a photo") }
                if (choice == Wallpapers.PHOTO) {
                    Text("Darken the photo", style = MaterialTheme.typography.labelMedium)
                    Slider(value = dim.toFloat(), onValueChange = { app.prefs.setWallpaperDim(it.toInt()) }, valueRange = 0f..70f)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { app.prefs.setWallpaper(Wallpapers.DOODLE) }) { Text("Reset") } },
    )
}
