package org.hearth.ember.call

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import android.util.Rational
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.RoomOptions
import io.livekit.android.AudioOptions
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.VideoTrackPublishOptions
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.hearth.ember.EmberApp
import org.hearth.ember.R
import org.hearth.ember.notify.NotificationActionReceiver

enum class Phase { Idle, Lobby, Connecting, Connected, Failed }

/** One person in the call, as the screen needs to draw them. */
data class CallPerson(
    val identity: String,
    val userId: String,
    val name: String,
    val isLocal: Boolean,
    val micOn: Boolean,
    val camOn: Boolean,
    val speaking: Boolean,
    val camera: VideoTrack?,
    val screen: VideoTrack?,
    /** Width over height of the shared screen, so it can be fitted whole into any window. */
    val screenRatio: Float = 16f / 9f,
)

data class CallOutput(val id: String, val label: String, val kind: Kind) {
    enum class Kind { Earpiece, Speaker, Bluetooth, Wired, Other }
}

data class CallState(
    val phase: Phase = Phase.Idle,
    val roomId: String? = null,
    val title: String = "",
    val video: Boolean = true,
    val micOn: Boolean = true,
    val camOn: Boolean = true,
    val lenses: List<Lens> = emptyList(),
    val lens: Lens? = null,
    val people: List<CallPerson> = emptyList(),
    val outputs: List<CallOutput> = emptyList(),
    val output: CallOutput? = null,
    val startedAt: Long = 0,
    val ringing: Boolean = false,
    val error: String? = null,
    val preview: VideoTrack? = null,
    val screenOn: Boolean = false,
)

/**
 * The phone's call engine. It owns the LiveKit room, the camera (our own Camera2 source, so every lens works) and the audio route, and tells
 * the Hearth server who is in the call. The screens only draw [state] and call the functions here, so a call survives the screen it started on:
 * leaving the app, rotating, or floating in a small window does not touch it.
 */
class CallController(private val app: EmberApp) {
    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()
    val pipMode = MutableStateFlow(false)
    /** Width over height of the picture the floating window will show, so the window takes that shape instead of cropping it. */
    val pipRatio = MutableStateFlow(9f / 16f)
    private val frameRatios = java.util.concurrent.ConcurrentHashMap<String, Float>()
    @Volatile private var pipIdentity: String? = null

    /** The real shape of someone's picture as it arrives, which is what the floating window has to match (the published size can say landscape for a portrait phone). */
    fun noteFrame(identity: String, ratio: Float) {
        frameRatios[identity] = ratio
        if (identity == pipIdentity) setPipRatio(ratio)
    }

    private fun setPipRatio(raw: Float) {
        val ratio = raw.coerceIn(0.42f, 2.3f)
        if (Math.abs(ratio - pipRatio.value) > 0.04f) pipRatio.value = ratio
    }

    var room: Room? = null
        private set
    private val audio = AudioSwitchHandler(app)
    private var capturer: LensCapturer? = null
    private var cameraTrack: LocalVideoTrack? = null
    private var job: Job? = null
    private var heartbeat: Job? = null
    private var video = true
    private val telecom = TelecomBridge(app)
    private var keyProvider: io.livekit.android.e2ee.BaseKeyProvider? = null
    private var wantSpeaker = false

    fun liveCallExists(roomId: String?): Boolean = roomId != null && app.repo.calls.value.containsKey(roomId)

    // ------------------------------------------------------------------ lobby
    /** Open the screen before the call: a preview of yourself and the choices, like Element Call's. Nothing is signalled yet. */
    fun openLobby(roomId: String, video: Boolean) {
        if (_state.value.roomId == roomId && _state.value.phase != Phase.Idle) return
        if (_state.value.phase != Phase.Idle) hangup()
        val room0 = app.repo.room(roomId)
        this.video = video
        wantSpeaker = video
        val lenses = Lenses.list(app)
        _state.value = CallState(
            phase = Phase.Lobby, roomId = roomId, title = room0?.title(app.repo.me.value?.id ?: "") ?: "Call", video = video,
            micOn = true, camOn = video && lenses.isNotEmpty(), lenses = lenses, lens = lenses.firstOrNull { it.front } ?: lenses.firstOrNull(),
            ringing = false,
        )
        ensureRoom()
        if (_state.value.camOn) startCamera()
    }

    private fun ensureRoom(): Room {
        room?.let { return it }
        audio.preferredDeviceList = listOf(
            com.twilio.audioswitch.AudioDevice.BluetoothHeadset::class.java,
            com.twilio.audioswitch.AudioDevice.WiredHeadset::class.java,
            com.twilio.audioswitch.AudioDevice.Earpiece::class.java,
            com.twilio.audioswitch.AudioDevice.Speakerphone::class.java,
        )
        // Screen sharing is tuned for motion (games, video): 1080p at 60 frames, a high bitrate and one layer.
        // An encrypted chat's call is sealed end to end: the server only relays frames it cannot read. The key is set before connecting.
        val encrypted = app.repo.room(_state.value.roomId ?: "")?.encrypted == true
        if (org.hearth.ember.BuildConfig.DEBUG) { io.livekit.android.LiveKit.loggingLevel = io.livekit.android.util.LoggingLevel.VERBOSE; io.livekit.android.LiveKit.enableWebRTCLogging = true }
        if (encrypted) System.loadLibrary("lkjingle_peerconnection_so") // the frame encryption lives in the same native library as the call engine
        keyProvider = if (encrypted) io.livekit.android.e2ee.BaseKeyProvider() else null
        val share = RoomOptions(
            adaptiveStream = true, dynacast = true,
            e2eeOptions = keyProvider?.let { io.livekit.android.e2ee.E2EEOptions(keyProvider = it) },
            screenShareTrackCaptureDefaults = LocalVideoTrackOptions(isScreencast = true, captureParams = VideoCaptureParameter(1920, 1080, 60)),
            screenShareTrackPublishDefaults = io.livekit.android.room.participant.VideoTrackPublishDefaults(
                videoEncoding = io.livekit.android.room.track.VideoEncoding(8_000_000, 60), simulcast = false,
            ),
        )
        val r = LiveKit.create(app, share, LiveKitOverrides(
            audioOptions = AudioOptions(
                audioHandler = audio,
                // The phone's built-in echo canceller and noise suppressor are skipped (unless chosen in Settings): when they fail, on speakerphone
                // above all, the other person hears their own voice back. WebRTC's software ones take over.
                javaAudioDeviceModuleCustomizer = { b ->
                    if (!app.prefs.deviceEcho.value) b.setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
                },
            ),
        ))
        room = r
        job = app.scope.launch { r.events.events.collect { onEvent(it) } }
        return r
    }

    private fun startCamera() {
        val r = ensureRoom()
        val lens = _state.value.lens ?: return
        if (cameraTrack != null) return
        runCatching {
            val c = LensCapturer(app, lens)
            capturer = c
            val track = r.localParticipant.createVideoTrack(
                name = "camera", capturer = c,
                options = LocalVideoTrackOptions(captureParams = VideoCaptureParameter(1280, 720, 30)),
            )
            track.startCapture()
            cameraTrack = track
            _state.update { it.copy(camOn = true, preview = track) }
        }.onFailure {
            Log.w("Call", "camera failed", it)
            _state.update { s -> s.copy(camOn = false, error = "The camera could not start.") }
        }
    }

    private fun stopCamera() {
        cameraTrack?.let { t -> runCatching { t.stopCapture() }; runCatching { room?.localParticipant?.unpublishTrack(t) }; runCatching { t.dispose() } }
        cameraTrack = null
        capturer = null
        _state.update { it.copy(camOn = false, preview = null) }
    }

    fun toggleMic() {
        val on = !_state.value.micOn
        _state.update { it.copy(micOn = on) }
        if (_state.value.phase == Phase.Connected) app.scope.launch { runCatching { room?.localParticipant?.setMicrophoneEnabled(on) }; push() }
    }

    fun toggleCamera() {
        if (_state.value.camOn) {
            // Off: stop capturing so the camera light goes out, and stop publishing.
            stopCamera()
        } else {
            startCamera()
            if (_state.value.phase == Phase.Connected) app.scope.launch { publishCamera() }
        }
        push()
    }

    // ------------------------------------------------------------------ screen sharing
    /** True from the moment sharing is asked for until it ends; the call service reads it to declare the right foreground type. */
    @Volatile var projectionWanted = false
        private set
    private var sharingStarting = false

    /** [permission] is what the system's "start recording or casting" dialog returned. */
    fun startScreen(permission: android.content.Intent) {
        if (_state.value.phase != Phase.Connected) return
        projectionWanted = true
        sharingStarting = true
        CallService.start(app)
        app.scope.launch {
            kotlinx.coroutines.delay(350) // let the service take its new type before the capture begins
            val ok = runCatching { room?.localParticipant?.setScreenShareEnabled(true, io.livekit.android.room.track.screencapture.ScreenCaptureParams(permission)) }.isSuccess
            sharingStarting = false
            if (!ok) { projectionWanted = false; CallService.start(app) }
            rebuild(); push()
        }
    }

    fun stopScreen() {
        app.scope.launch {
            runCatching { room?.localParticipant?.setScreenShareEnabled(false) }
            projectionWanted = false
            CallService.start(app)
            rebuild(); push()
        }
    }

    fun selectLens(lens: Lens) {
        _state.update { it.copy(lens = lens) }
        val c = capturer
        if (c != null) c.switchTo(lens)
    }

    fun flipCamera() {
        val s = _state.value
        val front = s.lenses.firstOrNull { it.front }
        val main = s.lenses.firstOrNull { it.id == "main" } ?: s.lenses.firstOrNull { !it.front }
        val next = if (s.lens?.front == true) main else front
        if (next != null) selectLens(next)
    }

    // ------------------------------------------------------------------ audio route
    private fun outputsNow(): Pair<List<CallOutput>, CallOutput?> {
        val devices = audio.availableAudioDevices
        fun map(d: AudioDevice) = CallOutput(
            d.name, d.name,
            when (d) {
                is AudioDevice.Earpiece -> CallOutput.Kind.Earpiece
                is AudioDevice.Speakerphone -> CallOutput.Kind.Speaker
                is AudioDevice.BluetoothHeadset -> CallOutput.Kind.Bluetooth
                is AudioDevice.WiredHeadset -> CallOutput.Kind.Wired
                else -> CallOutput.Kind.Other
            },
        )
        return devices.map(::map) to audio.selectedAudioDevice?.let(::map)
    }

    fun selectOutput(output: CallOutput) {
        val device = audio.availableAudioDevices.firstOrNull { it.name == output.id } ?: return
        wantSpeaker = output.kind == CallOutput.Kind.Speaker
        telecomRoute(device)
        audio.selectDevice(device)
        refreshOutputs()
    }

    fun toggleSpeaker() {
        val speaker = audio.availableAudioDevices.firstOrNull { it is AudioDevice.Speakerphone }
        val quiet = audio.availableAudioDevices.firstOrNull { it is AudioDevice.BluetoothHeadset }
            ?: audio.availableAudioDevices.firstOrNull { it is AudioDevice.WiredHeadset }
            ?: audio.availableAudioDevices.firstOrNull { it is AudioDevice.Earpiece }
        val now = audio.selectedAudioDevice
        val target = if (now is AudioDevice.Speakerphone) quiet else speaker
        if (target != null) { wantSpeaker = target is AudioDevice.Speakerphone; telecomRoute(target); audio.selectDevice(target) }
        refreshOutputs()
    }

    /** The system call owns the route while it holds the call, so a move is requested from it as well as from the audio manager. */
    private fun telecomRoute(device: AudioDevice) {
        telecom.route(
            when (device) {
                is AudioDevice.Speakerphone -> TelecomBridge.SPEAKER
                is AudioDevice.BluetoothHeadset -> TelecomBridge.BLUETOOTH
                is AudioDevice.WiredHeadset -> TelecomBridge.WIRED
                else -> TelecomBridge.EARPIECE
            },
        )
    }

    private fun refreshOutputs() {
        val (list, selected) = outputsNow()
        _state.update { it.copy(outputs = list, output = selected) }
    }

    // ------------------------------------------------------------------ going live
    /** The lobby's button: place the call (or join the one in progress). [answer] is true when picking up a ring. */
    fun go(answer: Boolean = false) {
        val s = _state.value
        val roomId = s.roomId ?: return
        if (s.phase != Phase.Lobby) return
        _state.update { it.copy(phase = Phase.Connecting, error = null) }
        val live = app.repo.calls.value[roomId]
        when {
            answer -> app.realtime.acceptCall(roomId)
            live == null -> app.realtime.startCall(roomId)
        }
        app.callNotifier.stop(roomId)
        app.scope.launch {
            try {
                val token = app.api.callToken(roomId, app.store.deviceId)
                val r = ensureRoom()
                // Same string the web app uses (the media key in base64), so both ends derive the same frame keys.
                keyProvider?.let { provider ->
                    val key = app.e2ee.mediaKey(roomId) ?: error("This call is end-to-end encrypted. Unlock the key first in Settings.")
                    val shared = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
                    provider.setSharedKey(shared)
                }
                r.connect(token.url, token.token)
                audio.registerAudioDeviceChangeListener { _, _ -> refreshOutputs(); applyDefaultRoute() }
                r.localParticipant.setMicrophoneEnabled(_state.value.micOn)
                if (_state.value.camOn) publishCamera()
                app.realtime.joinCall(roomId)
                _state.update { it.copy(phase = Phase.Connected, startedAt = live?.startedAt ?: System.currentTimeMillis(), ringing = live == null) }
                // Register with the system so headsets, watches and cars can mute and end it. The first mute report is just the starting
                // state, so only a change after it counts.
                var systemMuted: Boolean? = null
                telecom.begin(
                    roomId, s.title, video, incoming = answer,
                    onEndedBySystem = { hangup() },
                    onSystemMute = { muted ->
                        val before = systemMuted
                        systemMuted = muted
                        if (before != null && before != muted && muted == _state.value.micOn) toggleMic()
                    },
                )
                CallService.start(app)
                refreshOutputs()
                applyDefaultRoute()
                push()
                startHeartbeat(roomId)
            } catch (e: Exception) {
                Log.w("Call", "connect failed", e)
                _state.update { it.copy(phase = Phase.Failed, error = e.message ?: "Could not connect the call.") }
                runCatching { app.realtime.leaveCall(roomId) }
            }
        }
    }

    private var routeApplied = false
    /** First time devices are known: speaker for a video call, earpiece for a voice call, headsets always win. */
    private fun applyDefaultRoute() {
        val list = audio.availableAudioDevices
        if (list.isEmpty()) return
        val headset = list.firstOrNull { it is AudioDevice.BluetoothHeadset } ?: list.firstOrNull { it is AudioDevice.WiredHeadset }
        if (!routeApplied) {
            routeApplied = true
            val target = headset ?: if (wantSpeaker) list.firstOrNull { it is AudioDevice.Speakerphone } else list.firstOrNull { it is AudioDevice.Earpiece }
            if (target != null) audio.selectDevice(target)
            refreshOutputs()
        } else if (headset != null && audio.selectedAudioDevice !== headset && !_state.value.outputs.any { it.kind == CallOutput.Kind.Speaker && it.id == audio.selectedAudioDevice?.name && wantSpeaker }) {
            // A headset appeared in the middle of the call: use it unless the person picked the speaker on purpose.
        }
    }

    private suspend fun publishCamera() {
        val track = cameraTrack ?: return
        val lp = room?.localParticipant ?: return
        lp.publishVideoTrack(track, VideoTrackPublishOptions(name = "camera", source = Track.Source.CAMERA))
    }

    private fun startHeartbeat(roomId: String) {
        heartbeat?.cancel()
        heartbeat = app.scope.launch {
            while (isActive) {
                sendState(roomId)
                delay(10_000)
            }
        }
    }

    private fun sendState(roomId: String) {
        val s = _state.value
        app.realtime.send(buildJsonObject {
            put("t", "call.state"); put("roomId", roomId); put("mic", s.micOn); put("cam", s.camOn); put("screen", s.screenOn)
        })
    }

    private fun push() {
        val id = _state.value.roomId ?: return
        if (_state.value.phase == Phase.Connected) sendState(id)
    }

    fun hangup() {
        val s = _state.value
        val wasInCall = s.phase == Phase.Connected || s.phase == Phase.Connecting
        val roomId = s.roomId
        heartbeat?.cancel()
        telecom.end()
        if (roomId != null && (s.phase == Phase.Connected || s.phase == Phase.Connecting)) app.realtime.leaveCall(roomId)
        stopCamera()
        job?.cancel()
        runCatching { room?.disconnect() }
        runCatching { room?.release() }
        room = null
        keyProvider = null
        routeApplied = false
        projectionWanted = false
        CallService.stop(app)
        _state.value = CallState()
        // Ended from the floating window (its own end button): there is nothing left to float, so bring the app back to full size.
        if (pipMode.value) {
            runCatching {
                app.startActivity(
                    android.content.Intent(app, org.hearth.ember.MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            }
        }
        pipMode.value = false
        if (wasInCall) app.sounds.play(org.hearth.ember.notify.SoundPlayer.Kind.End)
    }

    // ------------------------------------------------------------------ room events
    private fun onEvent(event: RoomEvent) {
        if (event is RoomEvent.TrackE2EEStateEvent) android.util.Log.d("EmberE2EE", "track ${event.publication.sid} ${event.participant.identity} -> ${event.state}")
        when (event) {
            is RoomEvent.Disconnected -> if (_state.value.phase == Phase.Connected) {
                // The server or network ended it: say so, then clean up.
                hangup()
                return
            }
            else -> {}
        }
        rebuild()
    }

    private fun rebuild() {
        val r = room ?: return
        val me = app.repo.me.value
        fun person(p: Participant, local: Boolean): CallPerson {
            val identity = p.identity?.value ?: ""
            val userId = identity.substringBefore(':')
            val user = app.repo.users.value[userId]
            val cam = p.getTrackPublication(Track.Source.CAMERA)
            val screen = p.getTrackPublication(Track.Source.SCREEN_SHARE)
            return CallPerson(
                identity = identity, userId = userId,
                name = if (local) (me?.displayName ?: "You") else user?.displayName?.ifBlank { null } ?: p.name ?: "Someone",
                isLocal = local,
                micOn = p.isMicrophoneEnabled,
                camOn = cam?.track != null && cam.muted.not(),
                speaking = p.isSpeaking,
                camera = cam?.track as? VideoTrack,
                screen = screen?.track as? VideoTrack,
                screenRatio = screen?.dimensions?.let { if (it.width > 0 && it.height > 0) it.width.toFloat() / it.height else null } ?: (16f / 9f),
            )
        }
        val people = listOf(person(r.localParticipant, true)) + r.remoteParticipants.values.map { person(it, false) }
        // The floating window shows the speaker's (or first) camera: take that picture's shape.
        val shown = r.remoteParticipants.values.firstOrNull { it.isSpeaking && it.getTrackPublication(Track.Source.CAMERA)?.track != null }
            ?: r.remoteParticipants.values.firstOrNull { it.getTrackPublication(Track.Source.CAMERA)?.track != null }
        pipIdentity = shown?.identity?.value
        val dim = shown?.getTrackPublication(Track.Source.CAMERA)?.dimensions
        val seen = pipIdentity?.let { frameRatios[it] }
        if (seen != null) setPipRatio(seen)
        else if (dim != null && dim.width > 0 && dim.height > 0) setPipRatio(dim.width.toFloat() / dim.height)
        val sharing = r.localParticipant.getTrackPublication(Track.Source.SCREEN_SHARE) != null
        if (!sharing && projectionWanted && !sharingStarting) { projectionWanted = false; CallService.start(app) } // the system's own stop chip ended it
        val changed = sharing != _state.value.screenOn
        _state.update { it.copy(people = people, screenOn = sharing) }
        if (changed) push()
    }

    // ------------------------------------------------------------------ picture in picture
    fun onLeaveHint(activity: Activity) {
        Log.i("Call", "leave hint, phase=" + _state.value.phase)
        if (_state.value.phase != Phase.Connected || Build.VERSION.SDK_INT < 26) return
        runCatching { activity.enterPictureInPictureMode(pipParams(activity)) }
            .onSuccess { Log.i("Call", "entered pip: $it") }
            .onFailure { Log.w("Call", "pip failed", it) }
    }

    fun pipParams(context: Context): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(Math.round(pipRatio.value * 1000), 1000))
        val actions = ArrayList<RemoteAction>()
        fun action(code: Int, name: String, label: String, icon: Int): RemoteAction {
            val intent = Intent(context, NotificationActionReceiver::class.java).setAction(name)
            val pending = PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return RemoteAction(Icon.createWithResource(context, icon), label, label, pending)
        }
        val micOn = _state.value.micOn
        actions += action(1, ACTION_MUTE, if (micOn) "Mute" else "Unmute", if (micOn) android.R.drawable.ic_btn_speak_now else android.R.drawable.ic_lock_silent_mode)
        actions += action(2, ACTION_HANGUP, "End call", android.R.drawable.ic_menu_close_clear_cancel)
        builder.setActions(actions)
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(_state.value.phase == Phase.Connected)
        return builder.build()
    }

    companion object {
        const val ACTION_MUTE = "org.hearth.ember.CALL_MUTE"
        const val ACTION_HANGUP = "org.hearth.ember.CALL_HANGUP"
    }
}
