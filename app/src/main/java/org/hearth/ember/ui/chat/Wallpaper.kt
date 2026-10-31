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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.toArgb
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

/** The picture behind a chat: the chat's own colour (or a wash of the accent, or a photo), doodles you can switch off, and dimming. */
object Wallpapers {
    const val DEFAULT = "default"
    const val ACCENT = "accent"
    const val PHOTO = "photo"
    val presets = listOf(DEFAULT to "Default", ACCENT to "Accent colour")

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

    fun label(choice: String, doodles: Boolean): String = when (choice) {
        PHOTO -> "Your photo"
        else -> (presets.firstOrNull { it.first == choice }?.second ?: "Default") + if (doodles) ", with doodles" else ", no doodles"
    }
}

/** The flat colour under the doodles: the chat's own colour, or the accent washed light, dark or darkest to suit the theme (the web app does the same). */
fun washColor(choice: String, accent: Color, bg: Color): Color {
    if (choice != Wallpapers.ACCENT) return bg
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
    val dark = bg.luminance() < 0.5f
    val black = bg == Color.Black
    return when {
        black -> Color.hsl(hsv[0], 0.35f, 0.10f)
        dark -> Color.hsl(hsv[0], 0.40f, 0.17f)
        else -> Color.hsl(hsv[0], 0.55f, 0.86f)
    }
}

/** Paints a wallpaper. [accent] is what the doodles take their colour from, [bg] the chat's own background, [dim] (0 to 70) darkens everything. */
fun DrawScope.paintWallpaper(choice: String, doodles: Boolean, photo: ImageBitmap?, dim: Int, accent: Color, bg: Color) {
    val dark = bg.luminance() < 0.5f
    val d = density
    if (choice == Wallpapers.PHOTO && photo != null) {
        val scale = max(size.width / photo.width, size.height / photo.height)
        val src = IntSize((size.width / scale).toInt().coerceAtLeast(1), (size.height / scale).toInt().coerceAtLeast(1))
        drawImage(photo, srcOffset = IntOffset((photo.width - src.width) / 2, (photo.height - src.height) / 2), srcSize = src, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
    } else {
        drawRect(washColor(choice, accent, bg))
        if (doodles) {
            val base = if (dark) 0.26f else 0.22f
            val widthW = size.width / d
            val heightW = size.height / d
            val tile = DoodleLayout.tile
            // The picture joins up with itself every tile, so any screen size is covered.
            for (ty in 0..(heightW / tile.height).toInt()) for (tx in 0..(widthW / tile.width).toInt()) {
                val ox = tx * tile.width
                val oy = ty * tile.height
                for (p in tile.bigs) if (p.x + ox - p.r * 1.5f < widthW && p.y + oy - p.r * 1.5f < heightW) doodleAt(bigPaths[p.kind], p, ox, oy, accent.copy(alpha = base * p.fade), 1.9f * d, d)
                for (p in tile.fills) if (p.x + ox - p.r * 1.5f < widthW && p.y + oy - p.r * 1.5f < heightW) doodleAt(fillPaths[p.kind], p, ox, oy, accent.copy(alpha = base * p.fade), 1.5f * d, d)
            }
        }
    }
    if (dim > 0) drawRect(Color.Black.copy(alpha = dim / 100f))
}

/** One doodle in place: where its centre is, how big (half its width), how far it is turned, which shape, and how faint. All in dp. */
private class Placed(val x: Float, val y: Float, val r: Float, val turn: Float, val kind: Int, val fade: Float)

private class Tile(val width: Float, val height: Float, val bigs: List<Placed>, val fills: List<Placed>)

/** Where every doodle goes. The same hash, spacing and rules as the web app, so both draw the same picture. */
private object DoodleLayout {
    private const val BIG_COLS = 22
    private const val BIG_ROWS = 20
    private const val BIG_CELL = 70f
    private const val BIG_ROW = 63f
    private const val FILL_CELL = 17.5f

    val tile: Tile by lazy { build() }

    /** A number from 0 up to 1 that is always the same for the same row, column and purpose (the web app computes the identical one). */
    private fun unit(row: Int, col: Int, salt: Int): Float {
        var h = row * 374761393 + col * 668265263 + salt * 1274126177.toInt()
        h = (h xor (h ushr 13)) * 1274126177.toInt()
        h = h xor (h ushr 16)
        return (h and 0x7fffffff) % 10000 / 10000f
    }

    private fun mod(n: Int, m: Int) = ((n % m) + m) % m

    /** Rows are staggered, then every icon is moved off its spot and pushed clear of those already placed; its kind is one not used near it. */
    private fun layoutBig(kinds: Int): List<Placed> {
        val placed = arrayOfNulls<Placed>(BIG_ROWS * BIG_COLS)
        val w = BIG_COLS * BIG_CELL
        val h = BIG_ROWS * BIG_ROW
        for (row in 0 until BIG_ROWS) for (col in 0 until BIG_COLS) {
            var x = (col + 0.5f + 0.5f * (row and 1)) * BIG_CELL + (unit(row, col, 2) - 0.5f) * 0.5f * BIG_CELL
            var y = (row + 0.5f) * BIG_ROW + (unit(row, col, 3) - 0.5f) * 0.5f * BIG_ROW
            val r = 12f + 4f * unit(row, col, 4)
            val used = HashSet<Int>()
            for (dr in -3..3) for (dc in -3..3) {
                val rr = row + dr
                val cc = col + dc
                val n = placed[mod(rr, BIG_ROWS) * BIG_COLS + mod(cc, BIG_COLS)] ?: continue
                used.add(n.kind)
                if (Math.abs(dr) > 2 || Math.abs(dc) > 2) continue
                val nx = n.x + Math.floorDiv(cc, BIG_COLS) * w
                val ny = n.y + Math.floorDiv(rr, BIG_ROWS) * h
                val dist = Math.hypot((x - nx).toDouble(), (y - ny).toDouble()).toFloat()
                val least = r + n.r + 5f
                if (dist < least) {
                    val a = unit(row, col, 9) * Math.PI.toFloat() * 2f
                    val ux = if (dist < 0.01f) Math.cos(a.toDouble()).toFloat() else (x - nx) / dist
                    val uy = if (dist < 0.01f) Math.sin(a.toDouble()).toFloat() else (y - ny) / dist
                    x += ux * (least - dist)
                    y += uy * (least - dist)
                }
            }
            val free = (0 until kinds).filter { it !in used }
            val pool = free.ifEmpty { (0 until kinds).toList() }
            placed[row * BIG_COLS + col] = Placed(
                x, y, r, (unit(row, col, 5) - 0.5f) * 70f,
                pool[Math.min(pool.size - 1, (unit(row, col, 6) * pool.size).toInt())], 0.8f + 0.2f * unit(row, col, 7),
            )
        }
        return placed.map { it!! }
    }

    private fun build(): Tile {
        val w = BIG_COLS * BIG_CELL
        val h = BIG_ROWS * BIG_ROW
        val bigs = layoutBig(DoodleShapes.big.size)
        // Every large icon (and its copies one tile away) by place, so a small shape only looks at the ones near it.
        val bucket = HashMap<Long, MutableList<Placed>>()
        fun key(cx: Int, cy: Int) = cx.toLong() * 100000L + cy
        for (b in bigs) for (dx in listOf(0f, -w, w)) for (dy in listOf(0f, -h, h)) {
            val q = Placed(b.x + dx, b.y + dy, b.r, b.turn, b.kind, b.fade)
            if (q.x < -BIG_CELL * 2 || q.x > w + BIG_CELL * 2 || q.y < -BIG_ROW * 2 || q.y > h + BIG_ROW * 2) continue
            bucket.getOrPut(key(Math.floor((q.x / BIG_CELL).toDouble()).toInt(), Math.floor((q.y / BIG_ROW).toDouble()).toInt())) { mutableListOf() }.add(q)
        }
        val fills = ArrayList<Placed>()
        val fillKinds = DoodleShapes.fill.size
        for (row in 0 until (h / FILL_CELL).toInt()) for (col in 0 until (w / FILL_CELL).toInt()) {
            val x = (col + 0.5f) * FILL_CELL + (unit(row, col, 12) - 0.5f) * 0.8f * FILL_CELL
            val y = (row + 0.5f) * FILL_CELL + (unit(row, col, 13) - 0.5f) * 0.8f * FILL_CELL
            val r = 2.5f + 3f * unit(row, col, 14)
            val cx = Math.floor((x / BIG_CELL).toDouble()).toInt()
            val cy = Math.floor((y / BIG_ROW).toDouble()).toInt()
            var clear = true
            loop@ for (i in -1..1) for (j in -1..1) for (b in bucket[key(cx + i, cy + j)] ?: emptyList<Placed>()) {
                if (Math.hypot((x - b.x).toDouble(), (y - b.y).toDouble()) < b.r * 1.3 + r + 2) { clear = false; break@loop }
            }
            if (!clear) continue
            val pick = unit(row, col, 16)
            val kind = if (pick < 0.35f) 0 else 1 + Math.min(fillKinds - 2, (unit(row, col, 17) * (fillKinds - 1)).toInt())
            fills.add(Placed(x, y, r, unit(row, col, 15) * 360f, kind, 0.7f + 0.3f * unit(row, col, 18)))
        }
        return Tile(w, h, bigs, fills)
    }
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

/** Draw a doodle where it was placed, [ox] and [oy] dp into the picture. [density] turns dp into pixels; the line stays the same width however big the shape. */
private fun DrawScope.doodleAt(path: Path, p: Placed, ox: Float, oy: Float, ink: Color, strokePx: Float, density: Float) {
    val r = p.r * density
    withTransform({
        translate((p.x + ox) * density, (p.y + oy) * density)
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
    val doodles by app.prefs.doodles.collectAsState()
    val dim by app.prefs.wallpaperDim.collectAsState()
    val version by app.prefs.wallpaperVersion.collectAsState()
    val bg = EmberTheme.colors.chatBackground
    val accent = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val photo = remember(choice, version) { if (choice == Wallpapers.PHOTO) BitmapFactory.decodeFile(Wallpapers.photoFile(context).path)?.asImageBitmap() else null }
    return this.background(bg).drawBehind { paintWallpaper(choice, doodles, photo, dim, accent, bg) }
}

/** The picker: small previews (drawn in the current theme and accent), a photo from the gallery, the doodles switch and the dimming. */
@Composable
fun WallpaperDialog(onPickPhoto: () -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.ember
    val context = LocalContext.current
    val choice by app.prefs.wallpaper.collectAsState()
    val doodles by app.prefs.doodles.collectAsState()
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
                val options = Wallpapers.presets + (if (photo != null) listOf(Wallpapers.PHOTO to "Your photo") else emptyList())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(options.size) { i ->
                        val (id, name) = options[i]
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(width = 72.dp, height = 108.dp).clip(RoundedCornerShape(12.dp)).background(bg)
                                    .drawBehind { paintWallpaper(id, doodles, photo, dim, accent, bg) }
                                    .border(if (choice == id) 3.dp else 1.dp, if (choice == id) accent else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                    .clickable { app.prefs.setWallpaper(id) },
                            )
                            Text(name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onPickPhoto) { Text(if (photo != null) "Choose another photo" else "Choose a photo") }
                if (choice != Wallpapers.PHOTO) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Doodles", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = doodles, onCheckedChange = { app.prefs.setDoodles(it) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Dimming: $dim%", style = MaterialTheme.typography.labelMedium)
                Slider(value = dim.toFloat(), onValueChange = { app.prefs.setWallpaperDim((it / 5).toInt() * 5) }, valueRange = 0f..70f)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { app.prefs.setWallpaper(Wallpapers.DEFAULT); app.prefs.setDoodles(true); app.prefs.setWallpaperDim(0) }) { Text("Reset") } },
    )
}
