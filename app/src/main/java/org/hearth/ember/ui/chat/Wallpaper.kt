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
import androidx.compose.ui.graphics.drawscope.withTransform
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
            // Doodles in the way of the big messengers: larger outline icons, with small dots, rings and sparkles filling every gap between them.
            // Where each one goes comes from a fixed hash of its place, so the picture is the same every time and the same on the web.
            drawRect(accent.copy(alpha = if (dark) 0.05f else 0.06f))
            val base = if (dark) 0.26f else 0.22f
            val widthW = size.width / d
            val heightW = size.height / d
            val bigRows = (heightW / DOODLE_BIG_CELL).toInt() + 2
            val bigCols = (widthW / DOODLE_BIG_CELL).toInt() + 2
            for (row in 0 until bigRows) for (col in 0 until bigCols) {
                val p = bigDoodle(row, col)
                doodleAt(bigPaths[p.kind], p, accent.copy(alpha = base * p.fade), 1.9f * d, d)
            }
            for (row in 0 until (heightW / DOODLE_FILL_CELL).toInt() + 2) for (col in 0 until (widthW / DOODLE_FILL_CELL).toInt() + 2) {
                val p = fillDoodle(row, col) ?: continue
                doodleAt(fillPaths[p.kind], p, accent.copy(alpha = base * p.fade), 1.5f * d, d)
            }
        }
    }
}

/** One doodle in place: where its centre is, how big (half its width), how far it is turned, which shape, and how faint. All in dp. */
private class Placed(val x: Float, val y: Float, val r: Float, val turn: Float, val kind: Int, val fade: Float)

private const val DOODLE_BIG_CELL = 66f
private const val DOODLE_FILL_CELL = 16.5f

/** A number from 0 up to 1 that is always the same for the same row, column and purpose (the web app computes the identical one). */
private fun doodleUnit(row: Int, col: Int, salt: Int): Float {
    var h = row * 374761393 + col * 668265263 + salt * 1274126177.toInt()
    h = (h xor (h ushr 13)) * 1274126177.toInt()
    h = h xor (h ushr 16)
    return (h and 0x7fffffff) % 10000 / 10000f
}

/** The large icon of a place: a little off its spot, a little different in size, tipped a little either way. */
private fun bigDoodle(row: Int, col: Int) = Placed(
    x = (col + 0.5f) * DOODLE_BIG_CELL + (doodleUnit(row, col, 2) - 0.5f) * 0.36f * DOODLE_BIG_CELL,
    y = (row + 0.5f) * DOODLE_BIG_CELL + (doodleUnit(row, col, 3) - 0.5f) * 0.36f * DOODLE_BIG_CELL,
    r = 12f + 5f * doodleUnit(row, col, 4),
    turn = (doodleUnit(row, col, 5) - 0.5f) * 70f,
    kind = (doodleUnit(row, col, 6) * DoodleShapes.big.size).toInt().coerceIn(0, DoodleShapes.big.size - 1),
    fade = 0.8f + 0.2f * doodleUnit(row, col, 7),
)

/** The small shape of a place (most are dots), or null when a large icon is too close. */
private fun fillDoodle(row: Int, col: Int): Placed? {
    val x = (col + 0.5f) * DOODLE_FILL_CELL + (doodleUnit(row, col, 12) - 0.5f) * 0.7f * DOODLE_FILL_CELL
    val y = (row + 0.5f) * DOODLE_FILL_CELL + (doodleUnit(row, col, 13) - 0.5f) * 0.7f * DOODLE_FILL_CELL
    val r = 2.5f + 3f * doodleUnit(row, col, 14)
    val bigRow = Math.floor((y / DOODLE_BIG_CELL).toDouble()).toInt()
    val bigCol = Math.floor((x / DOODLE_BIG_CELL).toDouble()).toInt()
    for (dr in -1..1) for (dc in -1..1) {
        val b = bigDoodle(bigRow + dr, bigCol + dc)
        if (Math.hypot((x - b.x).toDouble(), (y - b.y).toDouble()) < b.r * 1.3 + r + 2) return null
    }
    val pick = doodleUnit(row, col, 16)
    val kind = if (pick < 0.4f) 0 else 1 + (doodleUnit(row, col, 17) * (DoodleShapes.fill.size - 1)).toInt().coerceIn(0, DoodleShapes.fill.size - 2)
    return Placed(x, y, r, doodleUnit(row, col, 15) * 360f, kind, 0.7f + 0.3f * doodleUnit(row, col, 18))
}

private val bigPaths: List<Path> by lazy { DoodleShapes.big.map(::unitPath) }
private val fillPaths: List<Path> by lazy { DoodleShapes.fill.map(::unitPath) }

/** The shapes are written as SVG path data (only M, L, H, V, C, Q and Z) in a box from -1 to 1. */
private fun unitPath(d: String): Path {
    val path = Path()
    val tokens = Regex("[MLHVCQZ]|-?\\d*\\.?\\d+").findAll(d).map { it.value }.toList()
    var i = 0
    var cmd = 'M'
    var x = 0f
    var y = 0f
    fun num() = tokens[i++].toFloat()
    while (i < tokens.size) {
        if (tokens[i][0].isLetter()) {
            cmd = tokens[i][0]
            i++
            if (cmd == 'Z') { path.close(); continue }
        }
        when (cmd) {
            'M' -> { x = num(); y = num(); path.moveTo(x, y); cmd = 'L' }
            'L' -> { x = num(); y = num(); path.lineTo(x, y) }
            'H' -> { x = num(); path.lineTo(x, y) }
            'V' -> { y = num(); path.lineTo(x, y) }
            'C' -> { val x1 = num(); val y1 = num(); val x2 = num(); val y2 = num(); x = num(); y = num(); path.cubicTo(x1, y1, x2, y2, x, y) }
            'Q' -> { val x1 = num(); val y1 = num(); x = num(); y = num(); path.quadraticBezierTo(x1, y1, x, y) }
        }
    }
    return path
}

/** Draw a doodle where it was placed. [density] turns dp into pixels; the line stays the same width however big the shape. */
private fun DrawScope.doodleAt(path: Path, p: Placed, ink: Color, strokePx: Float, density: Float) {
    val r = p.r * density
    withTransform({
        translate(p.x * density, p.y * density)
        rotate(p.turn, Offset.Zero)
        scale(r, r, Offset.Zero)
    }) {
        drawPath(path, ink, style = Stroke(width = strokePx / r, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
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
