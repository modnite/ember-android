package org.hearth.ember.ui.chat

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.hearth.ember.data.Attachment
import org.hearth.ember.ember
import org.hearth.ember.ui.callTimer
import org.hearth.ember.ui.theme.EmberTheme
import java.io.File

/**
 * Records a voice message to a file in the cache as AAC in ADTS frames (.aac): every frame stands alone, so a recording that is cut short
 * still plays, unlike an .m4a, which is only a valid file once its index is written at the very end. Plays in the web app and on the phone.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    var file: File? = null
        private set
    var startedAt = 0L
        private set

    fun start(): Boolean {
        val out = File(context.cacheDir, "voice-" + System.currentTimeMillis() + ".aac")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(64_000)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = out
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            runCatching { r.release() }
            false
        }
    }

    /** Stop and keep the recording. Returns null if it was too short to be a message. */
    fun finish(): File? {
        val r = recorder ?: return null
        recorder = null
        val longEnough = System.currentTimeMillis() - startedAt > 600
        runCatching { r.stop() }.onFailure { android.util.Log.w("Voice", "stop failed", it) }
        runCatching { r.release() }
        return if (longEnough) file else null.also { file?.delete() }
    }

    fun cancel() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        file?.delete()
    }

    val maxAmplitude: Int get() = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
}

/** The bar that replaces the message box while recording: a running time and level, delete on the left, send on the right. */
@Composable
fun RecordingBar(recorder: VoiceRecorder, onCancel: () -> Unit, onSend: () -> Unit) {
    var elapsed by remember { mutableIntStateOf(0) }
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            elapsed = (System.currentTimeMillis() - recorder.startedAt).toInt()
            level = (recorder.maxAmplitude / 12000f).coerceIn(0f, 1f)
            delay(80)
        }
    }
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.weight(1f).height(56.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Delete, "Discard", tint = MaterialTheme.colorScheme.error) }
                Box(Modifier.size(10.dp).clip(CircleShape).background(EmberTheme.colors.callDanger.copy(alpha = 0.55f + 0.45f * level)))
                Spacer(Modifier.width(10.dp))
                Text(callTimer(elapsed.toLong()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(14.dp))
                // A live level meter: the bars rise and fall with your voice.
                Row(Modifier.weight(1f).height(28.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    val bars = 18
                    for (i in 0 until bars) {
                        val shape = 0.35f + 0.65f * Math.abs(Math.sin((i + elapsed / 90f) * 0.7)).toFloat()
                        val h = (4 + 24 * level * shape).dp
                        Box(Modifier.width(3.dp).height(h).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)))
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.padding(bottom = 4.dp).size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onSend),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.Send, "Send voice message", tint = MaterialTheme.colorScheme.onPrimary) }
    }
}

/** One voice message in a chat: play/pause, a scrubber, the length, and a speed button. Only one plays at a time. */
@Composable
fun VoiceBubble(a: Attachment, roomId: String, mine: Boolean) {
    val context = LocalContext.current
    val app = context.ember
    val textColor = if (mine) EmberTheme.colors.onBubbleMine else EmberTheme.colors.onBubbleTheirs
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }

    DisposableEffect(a.id) {
        onDispose {
            player[0]?.let { runCatching { it.release() } }
            player[0] = null
            if (VoicePlaying.current === player) VoicePlaying.current = null
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            player[0]?.let { position = it.currentPosition }
            delay(120)
        }
    }

    suspend fun prepare(): MediaPlayer? = withContext(Dispatchers.IO) {
        runCatching {
            var bytes = app.api.downloadAttachment(a.id)
            if (a.encrypted != 0) bytes = app.e2ee.openFile(roomId, a.envelope, bytes) ?: return@runCatching null
            val f = File(context.cacheDir, "play-" + a.id + ".aac").also { it.writeBytes(bytes) }
            val mp = MediaPlayer()
            mp.setDataSource(f.absolutePath)
            val ready = kotlinx.coroutines.CompletableDeferred<Boolean>()
            mp.setOnPreparedListener { ready.complete(true) }
            mp.setOnErrorListener { _, _, _ -> ready.complete(false); true }
            mp.prepareAsync()
            // A broken file must say so, not spin forever.
            if (kotlinx.coroutines.withTimeoutOrNull(6000) { ready.await() } == true) mp else { runCatching { mp.release() }; null }
        }.getOrNull()
    }

    fun toggle(scope: CoroutineScope) {
        val p = player[0]
        if (p != null) {
            if (p.isPlaying) { p.pause(); playing = false } else {
                VoicePlaying.take(player) { playing = false }
                p.start(); p.playbackParams = p.playbackParams.setSpeed(speed); playing = true
            }
            return
        }
        scope.launch {
            loading = true
            val made = prepare()
            loading = false
            if (made == null) { error = true; return@launch }
            made.setOnCompletionListener { playing = false; position = 0 }
            player[0] = made
            duration = made.duration
            VoicePlaying.take(player) { playing = false }
            made.start()
            made.playbackParams = made.playbackParams.setSpeed(speed)
            playing = true
        }
    }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Row(Modifier.padding(vertical = 2.dp).widthIn(min = 220.dp, max = 320.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { toggle(scope) },
            contentAlignment = Alignment.Center,
        ) {
            if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", tint = MaterialTheme.colorScheme.onPrimary)
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            // A thin seek bar: tap or drag anywhere on it to move through the message.
            var widthPx by remember { mutableIntStateOf(1) }
            val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Box(
                Modifier.fillMaxWidth().height(22.dp)
                    .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                    .pointerInput(duration) {
                        detectTapGestures { o -> player[0]?.let { it.seekTo((o.x / widthPx * it.duration).toInt()); position = it.currentPosition } }
                    }
                    .pointerInput(duration) {
                        detectHorizontalDragGestures { change, _ ->
                            player[0]?.let { it.seekTo((change.position.x.coerceIn(0f, widthPx.toFloat()) / widthPx * it.duration).toInt()); position = it.currentPosition }
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(textColor.copy(alpha = 0.22f)))
                Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
                Box(
                    Modifier.offset(x = with(androidx.compose.ui.platform.LocalDensity.current) { (widthPx * fraction).toDp() } - 6.dp).size(12.dp)
                        .clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                )
            }
            Text(
                if (error) "Could not play" else if (duration > 0) callTimer(position.toLong()) + " / " + callTimer(duration.toLong()) else "Voice message",
                fontSize = 11.sp, color = textColor.copy(alpha = 0.65f),
            )
        }
        Text(
            (if (speed == 1f) "1" else speed.toString().removeSuffix(".0")) + "x", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = textColor,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(textColor.copy(alpha = 0.1f))
                .clickable {
                    speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
                    player[0]?.let { p -> if (p.isPlaying) p.playbackParams = p.playbackParams.setSpeed(speed) }
                }.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Starting one voice message stops the one that was playing. */
private object VoicePlaying {
    var current: Array<MediaPlayer?>? = null
    private var stopCallback: (() -> Unit)? = null
    fun take(next: Array<MediaPlayer?>, onStopped: () -> Unit) {
        if (current !== next) {
            current?.get(0)?.let { runCatching { if (it.isPlaying) it.pause() } }
            stopCallback?.invoke()
        }
        current = next
        stopCallback = onStopped
    }
}
