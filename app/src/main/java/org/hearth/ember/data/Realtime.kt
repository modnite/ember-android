package org.hearth.ember.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * The live connection to the server: messages, typing, presence, calls. It reconnects by itself (a phone sleeps, changes network, the
 * server restarts) and says so through [connected]; everything it hears goes out on [frames] for the repository to apply.
 */
class Realtime(
    private val store: SessionStore,
    client: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val http = client.newBuilder().pingInterval(25, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val _frames = MutableSharedFlow<JsonObject>(extraBufferCapacity = 256)
    val frames: SharedFlow<JsonObject> = _frames.asSharedFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var socket: WebSocket? = null
    private var job: Job? = null
    private var wanted = false
    private var attempt = 0

    fun start() {
        if (wanted) return
        wanted = true
        attempt = 0
        open()
    }

    fun stop() {
        wanted = false
        job?.cancel()
        socket?.close(1000, "bye")
        socket = null
        _connected.value = false
    }

    /** The network came back or the app returned to the front: do not wait out the backoff. */
    fun nudge() {
        if (!wanted || _connected.value) return
        job?.cancel()
        attempt = 0
        open()
    }

    fun send(frame: JsonObject) {
        socket?.send(frame.toString())
    }

    fun typing(roomId: String, typing: Boolean) =
        send(buildJsonObject { put("t", "typing"); put("roomId", roomId); put("typing", typing) })

    fun presence(visible: Boolean) = send(buildJsonObject { put("t", "presence.visibility"); put("visible", visible) })

    fun setStatus(status: String, message: String) =
        send(buildJsonObject { put("t", "presence.set"); put("status", status); put("statusMessage", message) })

    /** A nudge: the other people's chat shakes and buzzes (and they are told if they are away). */
    fun nudge(roomId: String) = send(buildJsonObject { put("t", "nudge"); put("roomId", roomId) })

    fun startCall(roomId: String) = send(buildJsonObject { put("t", "call.start"); put("roomId", roomId) })
    fun joinCall(roomId: String) = send(buildJsonObject { put("t", "call.join"); put("roomId", roomId) })
    fun leaveCall(roomId: String) = send(buildJsonObject { put("t", "call.leave"); put("roomId", roomId) })
    fun acceptCall(roomId: String) = send(buildJsonObject { put("t", "call.accept"); put("roomId", roomId) })
    fun declineCall(roomId: String) = send(buildJsonObject { put("t", "call.decline"); put("roomId", roomId) })
    fun endCall(roomId: String) = send(buildJsonObject { put("t", "call.end"); put("roomId", roomId) })

    private fun open() {
        if (!store.signedIn) return
        val request = Request.Builder().url(store.server + "/ws?device=" + store.deviceId).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { EmberJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                if (frame["t"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } == "ready") _connected.value = true
                _frames.tryEmit(frame)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // 401: the session is gone. Tell the repository, which signs out; do not hammer the server.
                if (response?.code == 401) {
                    _frames.tryEmit(buildJsonObject { put("t", "auth.lost") })
                    wanted = false
                }
                lost(webSocket)
            }
        })
    }

    private fun lost(webSocket: WebSocket) {
        if (socket !== webSocket) return
        socket = null
        _connected.value = false
        if (!wanted) return
        val wait = minOf(30_000L, 1_000L shl minOf(attempt, 5))
        attempt++
        job = scope.launch {
            delay(wait)
            if (wanted) open()
        }
    }
}
