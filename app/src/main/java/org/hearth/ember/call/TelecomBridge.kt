package org.hearth.ember.call

import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.hearth.ember.EmberApp

/**
 * Tells Android about the call, so the system treats it as a real phone call: Bluetooth headsets, watches and cars can answer, mute and end
 * it, and the system keeps it alive and gives it priority. It only registers the call and listens; the sound itself is still routed by the
 * call engine (a second router fighting over the audio is what once left the microphone stuck paused). If anything here fails the call
 * simply carries on without it.
 */
class TelecomBridge(private val app: EmberApp) {
    private val manager by lazy {
        CallsManager(app).also {
            it.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING)
        }
    }
    private var job: Job? = null
    private var ended: CompletableDeferred<Unit>? = null

    /**
     * [onEndedBySystem]: a watch, headset or car ended the call. [onSystemMute]: the system's microphone mute changed (true = muted).
     */
    fun begin(roomId: String, title: String, video: Boolean, incoming: Boolean, onEndedBySystem: () -> Unit, onSystemMute: (Boolean) -> Unit) {
        end()
        val done = CompletableDeferred<Unit>()
        ended = done
        job = app.scope.launch {
            runCatching {
                val attributes = CallAttributesCompat(
                    displayName = title.ifBlank { "Hearth call" },
                    address = Uri.parse("hearth:$roomId"),
                    direction = if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
                    callType = if (video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                )
                manager.addCall(
                    attributes,
                    onAnswer = { },
                    onDisconnect = { if (!done.isCompleted) onEndedBySystem() },
                    onSetActive = { },
                    onSetInactive = { },
                ) {
                    // The block is not a suspending one itself; the call's own scope runs the work.
                    launch {
                        setActive()
                        launch { isMuted.collect { onSystemMute(it) } }
                        done.await()
                        disconnect(DisconnectCause(DisconnectCause.LOCAL))
                    }
                }
            }.onFailure { Log.w("Telecom", "call not registered with the system", it) }
        }
    }

    /** The call is over (from this app): let the system know and let go. */
    fun end() {
        ended?.complete(Unit)
        ended = null
        job = null
    }
}
