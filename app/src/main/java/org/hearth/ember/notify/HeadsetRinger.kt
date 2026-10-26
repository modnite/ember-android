package org.hearth.ember.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import org.hearth.ember.R

/**
 * The ringer is muted on a silent phone, and the notification ring does not follow a headset, so a call would go unheard in the
 * ears. While a call is ringing and a headset is connected, the ring is played straight to the headset as media, which silent
 * mode does not mute. Safe to start twice (a push and the open app both announce the same call).
 */
class HeadsetRinger(private val context: Context) {
    private var player: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    fun start() = main.post {
        if (player != null) return@post
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val headset = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in HEADSETS } ?: return@post
        runCatching {
            val p = MediaPlayer()
            context.resources.openRawResourceFd(R.raw.ember_ring).use { p.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setPreferredDevice(headset)
            p.isLooping = true
            p.prepare()
            p.start()
            player = p
            main.postDelayed({ if (player === p) stop() }, 45_000)
        }
    }

    fun stop() = main.post {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    private companion object {
        val HEADSETS = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
