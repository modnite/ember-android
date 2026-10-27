package org.hearth.ember.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import org.hearth.ember.R
import kotlinx.coroutines.launch
import org.hearth.ember.ember

/**
 * Ember's small sounds inside a chat: a message coming in, one you send, someone starting to type. They follow the notification volume and
 * the phone's silent and vibrate modes, stay quiet during a call, and all go off together with the Sounds switch in the settings.
 */
class SoundPlayer(private val context: Context) {
    enum class Kind { In, Out, Typing, End }

    private val pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
    ).build()
    private val ids = mapOf(
        Kind.In to pool.load(context, R.raw.chat_in, 1),
        Kind.Out to pool.load(context, R.raw.chat_out, 1),
        Kind.Typing to pool.load(context, R.raw.chat_typing, 1),
        Kind.End to pool.load(context, R.raw.call_end, 1),
    )
    private var lastTyping = 0L

    fun play(kind: Kind) {
        val app = context.ember
        if (!app.prefs.soundsOn.value) return
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        if (kind != Kind.End && app.callController?.state?.value?.phase.let { it != null && it != org.hearth.ember.call.Phase.Idle }) return
        if (kind == Kind.Typing) {
            val now = System.currentTimeMillis()
            if (now - lastTyping < 4000) return
            lastTyping = now
        }
        val stream = ids[kind]?.let { pool.play(it, 0.7f, 0.7f, 1, 0, 1f) }
        android.util.Log.d("ChatSound", "$kind stream=$stream")
    }

    // ------------------------------------------------------------------ the nudge: the classic sound, fetched from your own server once and kept
    // The file name carries a version: an older, much quieter nudge.mp3 may be sitting in the cache and would otherwise be kept for ever.
    private val nudgeFile = java.io.File(context.cacheDir, "nudge-v2.mp3").also { java.io.File(context.cacheDir, "nudge.mp3").delete() }
    private val nudgeVoices = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var fetching = false

    /** Play the nudge sound (the same file the web app plays). The first time it is downloaded; until then a nudge is silent. */
    fun nudge() {
        val app = context.ember
        if (!app.prefs.soundsOn.value) return
        if (context.getSystemService(AudioManager::class.java).ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        if (!nudgeFile.exists() || nudgeFile.length() == 0L) { fetchNudge(); return }
        if (nudgeVoices.get() >= 4) return
        runCatching {
            nudgeVoices.incrementAndGet()
            val player = android.media.MediaPlayer()
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            player.setDataSource(nudgeFile.absolutePath)
            player.setOnCompletionListener { it.release(); nudgeVoices.decrementAndGet() }
            player.setOnErrorListener { mp, _, _ -> mp.release(); nudgeVoices.decrementAndGet(); true }
            player.setVolume(1f, 1f)
            player.setOnPreparedListener { it.start() }
            player.prepareAsync()
        }.onFailure { nudgeVoices.decrementAndGet() }
    }

    /** Get the sound ready ahead of the first nudge (called once signed in). */
    fun fetchNudge() {
        if (fetching || nudgeFile.exists()) return
        fetching = true
        val app = context.ember
        app.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val request = okhttp3.Request.Builder().url(app.store.server + "/assets/sounds/nudge.mp3").build()
                app.client.newCall(request).execute().use { r ->
                    if (r.isSuccessful) r.body?.bytes()?.takeIf { it.size > 1000 }?.let { nudgeFile.writeBytes(it) }
                    android.util.Log.d("NudgeSound", "fetch ${request.url} -> ${r.code}, saved=${nudgeFile.exists()}")
                }
            }.onFailure { android.util.Log.w("NudgeSound", "fetch failed: $it") }
            fetching = false
        }
    }
}
