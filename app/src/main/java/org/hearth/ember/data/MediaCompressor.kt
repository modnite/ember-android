package org.hearth.ember.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** How a picture or video is sent: Standard is smaller and sends faster, HD keeps much more detail. Neither is the untouched original. */
enum class Quality { Standard, HD }

/** What goes to the server. */
class Prepared(val bytes: ByteArray, val name: String, val mime: String)

/** The numbers behind each quality, kept in one place and free of Android so they are tested on their own. */
object MediaPlan {
    /** Longest side of a photo, in pixels. (WhatsApp's HD is 4096.) */
    fun photoEdge(q: Quality) = if (q == Quality.HD) 4096 else 1600
    fun photoJpeg(q: Quality) = if (q == Quality.HD) 92 else 80

    /** Shortest side of a video, in pixels: 720p, or 1080p in HD. */
    fun videoShortSide(q: Quality) = if (q == Quality.HD) 1080 else 720
    fun videoBitrate(q: Quality) = if (q == Quality.HD) 6_000_000 else 2_000_000

    /** The size a picture is brought to: never enlarged, the long side at most [maxEdge], both sides at least 1. */
    fun fit(w: Int, h: Int, maxEdge: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= maxEdge) return w to h
        val k = maxEdge.toFloat() / long
        return maxOf(1, Math.round(w * k)) to maxOf(1, Math.round(h * k))
    }

    /** The height (as shown, after any turn) a video is scaled to, or null when it is already no bigger than the target. Always even, as encoders want. */
    fun videoHeight(shown: Pair<Int, Int>, q: Quality): Int? {
        val (w, h) = shown
        val short = minOf(w, h)
        val target = videoShortSide(q)
        if (short <= target) return null
        val k = target.toFloat() / short
        return (Math.round(h * k) / 2) * 2
    }
}

/**
 * Brings a photo or video down to the chosen quality before it is sent. The original stays on the phone; only the smaller copy is uploaded, which
 * is also what the server has to store. Anything that cannot be improved (a GIF, a sticker, a picture with see-through parts, a file that comes out no
 * smaller) is sent as it is.
 */
class MediaCompressor(private val context: Context) {
    suspend fun prepare(uri: Uri, name: String, mime: String, quality: Quality): Prepared? = when {
        mime == "image/jpeg" || mime == "image/heic" || mime == "image/heif" || mime == "image/png" || mime == "image/webp" -> photo(uri, name, mime, quality)
        mime.startsWith("video/") -> video(uri, name, mime, quality)
        else -> null
    }

    private fun turn(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private suspend fun photo(uri: Uri, name: String, mime: String, q: Quality): Prepared? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val original = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        // A picture that moves (WebP) or has see-through parts keeps its own format.
        if (mime == "image/webp" && original.size > 12 && String(original, 8, 4, Charsets.US_ASCII) == "WEBP" && String(original, 12, 4, Charsets.US_ASCII) == "VP8X") return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val degrees = runCatching {
            when (ExifInterface(original.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
        val edge = MediaPlan.photoEdge(q)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(original, 0, original.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
        if (decoded.hasAlpha() && mime != "image/jpeg") { decoded.recycle(); return@withContext null }
        val turned = turn(decoded, degrees)
        val (w, h) = MediaPlan.fit(turned.width, turned.height, edge)
        val scaled = if (w == turned.width && h == turned.height) turned else Bitmap.createScaledBitmap(turned, w, h, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, MediaPlan.photoJpeg(q), out)
        val bytes = out.toByteArray()
        // Not worth it: a JPEG that comes out no smaller is sent untouched.
        if (mime == "image/jpeg" && bytes.size >= original.size) return@withContext null
        Prepared(bytes, name.substringBeforeLast('.', name) + ".jpg", "image/jpeg")
    }

    private fun shown(uri: Uri): Pair<Int, Int>? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation % 180 != 0) h to w else w to h
        } catch (e: Exception) {
            null
        } finally {
            r.release()
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun video(uri: Uri, name: String, mime: String, q: Quality): Prepared? {
        val size = shown(uri)
        val height = size?.let { MediaPlan.videoHeight(it, q) }
        val out = File(context.cacheDir, "send-" + System.nanoTime() + ".mp4")
        try {
            val effects = ArrayList<Effect>()
            if (height != null) effects.add(Presentation.createForHeight(height))
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(Effects(emptyList(), effects)).build()
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val transformer = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setEncoderFactory(
                            DefaultEncoderFactory.Builder(context)
                                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(MediaPlan.videoBitrate(q)).build())
                                .build(),
                        )
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(Unit) }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) cont.resumeWithException(exportException)
                            }
                        })
                        .build()
                    transformer.start(edited, out.absolutePath)
                    cont.invokeOnCancellation { transformer.cancel() }
                }
            }
            val original = withContext(Dispatchers.IO) { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }
            // A video that comes out no smaller than it went in is sent as it was.
            if (!out.exists() || out.length() == 0L || (original > 0 && out.length() >= original)) return null
            val bytes = withContext(Dispatchers.IO) { out.readBytes() }
            return Prepared(bytes, name.substringBeforeLast('.', name) + ".mp4", "video/mp4")
        } finally {
            out.delete()
        }
    }
}
