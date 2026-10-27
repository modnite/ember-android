package org.hearth.ember.ui.chat

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hearth.ember.EmberApp
import org.hearth.ember.data.Attachment
import org.hearth.ember.ember

/** What a video needs to be played or shown: where it can be read from, found out once. */
sealed interface VideoSource {
    data class Remote(val url: String) : VideoSource
    data class Local(val file: File) : VideoSource
}

/** A video from an encrypted chat is fetched as ciphertext and opened here with the group key; the clear copy only ever lives in the app's cache. */
suspend fun videoSource(app: EmberApp, a: Attachment, roomId: String): VideoSource? =
    if (a.encrypted == 0) VideoSource.Remote(app.store.server + "/api/attachments/" + a.id)
    else withContext(Dispatchers.IO) {
        runCatching {
            val file = File(app.cacheDir, "videos").also { it.mkdirs() }.resolve(a.id + ".mp4")
            if (!file.exists() || file.length() == 0L) {
                val clear = app.e2ee.openFile(roomId, a.envelope, app.api.downloadAttachment(a.id)) ?: return@runCatching null
                file.writeBytes(clear)
            }
            VideoSource.Local(file)
        }.getOrNull()
    }

/** A still from the first moments of a video, and how long it runs. Kept for the session so a chat scrolls without fetching again. */
object VideoThumbs {
    class Thumb(val bitmap: Bitmap?, val durationMs: Long)

    private val cache = LruCache<String, Thumb>(40)

    suspend fun of(app: EmberApp, a: Attachment): Thumb? {
        cache.get(a.id)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(app.store.server + "/api/attachments/" + a.id, mapOf("Cookie" to "hearth_session=" + app.store.cookie))
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val frame = retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    val scaled = frame?.let {
                        val k = 640f / maxOf(it.width, it.height)
                        if (k < 1f) Bitmap.createScaledBitmap(it, (it.width * k).toInt().coerceAtLeast(1), (it.height * k).toInt().coerceAtLeast(1), true) else it
                    }
                    Thumb(scaled, duration)
                } finally {
                    retriever.release()
                }
            }.getOrNull()?.also { cache.put(a.id, it) }
        }
    }
}

/** A video full screen, over the chat, with play and pause, a scrubber and the sound it came with. Back or the X returns to the chat. */
@OptIn(UnstableApi::class)
@Composable
fun VideoViewer(attachment: Attachment, roomId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.ember
    val source by produceState<VideoSource?>(null, attachment.id) { value = videoSource(app, attachment, roomId) }
    val player = remember {
        ExoPlayer.Builder(context)
            // The app's own HTTP client, so the request carries the sign-in cookie and the server can answer byte ranges (seeking).
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(app.client)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
    DisposableEffect(source) {
        when (val s = source) {
            is VideoSource.Remote -> player.setMediaItem(MediaItem.fromUri(s.url))
            is VideoSource.Local -> player.setMediaItem(MediaItem.fromUri(Uri.fromFile(s.file)))
            null -> return@DisposableEffect onDispose { }
        }
        player.prepare()
        player.playWhenReady = true
        onDispose { }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    FullScreenDialog(onClose) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        this.player = player
                        useController = true
                        setKeepScreenOn(true)
                    }
                },
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            )
            if (source == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
                Icon(Icons.Filled.Close, "Close", tint = Color.White)
            }
            if (source == null && attachment.encrypted != 0) Text("Opening the encrypted video…", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp))
        }
    }
}
