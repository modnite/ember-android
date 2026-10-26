package org.hearth.ember.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Stickers or GIFs kept on this phone for sending again: a folder of picture files, newest first. A sticker is a 512 pixel WebP named
 * sticker-….webp, which is how every client knows to draw it without a bubble; a GIF keeps its own bytes and is named gif-….gif.
 */
class Favourites(context: Context, private val kind: Kind) {
    enum class Kind(val dir: String, val prefix: String, val ext: String, val mime: String) {
        Sticker("stickers", "sticker-", "webp", "image/webp"),
        Gif("gifs", "gif-", "gif", "image/gif"),
    }

    private val folder = File(context.filesDir, kind.dir).also { it.mkdirs() }
    private val resolver = context.contentResolver
    private val _items = MutableStateFlow(list())
    val items: StateFlow<List<File>> = _items.asStateFlow()

    private fun list(): List<File> = folder.listFiles { f -> f.name.endsWith("." + kind.ext) }.orEmpty().sortedByDescending { it.lastModified() }

    private fun nameFor(bytes: ByteArray): String =
        kind.prefix + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(12) + "." + kind.ext

    /** What an attachment of this kind would be called once saved, so a picture already in the favourites is recognised. */
    fun has(bytes: ByteArray): Boolean = File(folder, nameFor(bytes)).exists()

    fun add(bytes: ByteArray): File {
        val file = File(folder, nameFor(bytes))
        file.writeBytes(bytes)
        file.setLastModified(System.currentTimeMillis())
        _items.value = list()
        return file
    }

    fun remove(bytes: ByteArray) {
        File(folder, nameFor(bytes)).delete()
        _items.value = list()
    }

    fun remove(file: File) {
        file.delete()
        _items.value = list()
    }

    /** Make a sticker out of any picture: scaled to fit a 512 pixel square (see-through where the picture is), saved as WebP. */
    suspend fun addStickerFrom(uri: Uri): File? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIDE * 2) sample *= 2
        val source = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return@withContext null
        add(stickerBytes(source))
    }

    /** Keep a GIF file as it is. Returns null when the file is not a GIF. */
    suspend fun addFrom(uri: Uri): File? = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        if (bytes.size < 6 || String(bytes, 0, 4, Charsets.US_ASCII) != "GIF8") null else add(bytes)
    }

    companion object {
        const val SIDE = 512

        /** The picture fitted inside a SIDE x SIDE transparent canvas and encoded as WebP (lossless where the phone can, else high quality). */
        fun stickerBytes(source: Bitmap): ByteArray {
            val k = SIDE.toFloat() / maxOf(source.width, source.height)
            val w = (source.width * k).toInt().coerceAtLeast(1)
            val h = (source.height * k).toInt().coerceAtLeast(1)
            val canvasBitmap = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
            Canvas(canvasBitmap).drawBitmap(Bitmap.createScaledBitmap(source, w, h, true), ((SIDE - w) / 2).toFloat(), ((SIDE - h) / 2).toFloat(), null)
            val out = java.io.ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            val format = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
            canvasBitmap.compress(format, 95, out)
            return out.toByteArray()
        }
    }
}
