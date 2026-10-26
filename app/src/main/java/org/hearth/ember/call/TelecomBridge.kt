package org.hearth.ember.call

import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.channels.Channel
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
    @Volatile private var endpoints: List<CallEndpointCompat> = emptyList()
    private var routeRequests = Channel<CallEndpointCompat>(Channel.CONFLATED)

    /**
     * Ask the system to send the call's sound to the earpiece, speaker or headset. While the system holds the call, it owns the route
     * (Samsung's call audio controller overrides anything the app sets on the audio manager), so the move has to be requested here.
     * Returns false when there is no such endpoint (or no system call), and the caller falls back to the audio manager.
     */
    fun route(type: Int): Boolean {
        val target = endpoints.firstOrNull { it.type == type } ?: return false
        return routeRequests.trySend(target).isSuccess
    }

    companion object {
        const val EARPIECE = CallEndpointCompat.TYPE_EARPIECE
        const val SPEAKER = CallEndpointCompat.TYPE_SPEAKER
        const val BLUETOOTH = CallEndpointCompat.TYPE_BLUETOOTH
        const val WIRED = CallEndpointCompat.TYPE_WIRED_HEADSET
    }

    /**
     * [onEndedBySystem]: a watch, headset or car ended the call. [onSystemMute]: the system's microphone mute changed (true = muted).
     */
    fun begin(roomId: String, title: String, video: Boolean, incoming: Boolean, onEndedBySystem: () -> Unit, onSystemMute: (Boolean) -> Unit) {
        end()
        val done = CompletableDeferred<Unit>()
        ended = done
        endpoints = emptyList()
        routeRequests = Channel(Channel.CONFLATED)
        val requests = routeRequests
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
                    onDisconnect = { cause -> Log.d("Telecom", "system disconnected the call: $cause done=${done.isCompleted}"); if (!done.isCompleted) onEndedBySystem() },
                    onSetActive = { },
                    onSetInactive = { },
                ) {
                    // The block is not a suspending one itself; the call's own scope runs the work.
                    Log.d("Telecom", "call scope started")
                    launch {
                        Log.d("Telecom", "setActive -> ${setActive()}")
                        launch { isMuted.collect { onSystemMute(it) } }
                        launch { availableEndpoints.collect { endpoints = it } }
                        launch { for (target in requests) Log.d("Telecom", "route to ${target.name} -> ${requestEndpointChange(target)}") }
                        done.await()
                        Log.d("Telecom", "app ended the call, disconnecting")
                        disconnect(DisconnectCause(DisconnectCause.LOCAL))
                    }
                }
            }.onSuccess { Log.d("Telecom", "addCall returned") }.onFailure { Log.w("Telecom", "call not registered with the system", it) }
        }
    }

    /** The call is over (from this app): let the system know and let go. */
    fun end() {
        ended?.complete(Unit)
        ended = null
        job = null
        endpoints = emptyList()
        routeRequests.close()
    }
}
