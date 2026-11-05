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
    /** You switched this person's sound off, for yourself only: they are not told. */
    val mutedByMe: Boolean = false,
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
    /** My own battery level while it is low in this call (null once I plug in). */
    val lowBattery: Int? = null,
    /** Others in the call whose battery is low, by identity. */
    val peerBattery: Map<String, PeerBattery> = emptyMap(),
)

data class PeerBattery(val name: String, val level: Int)

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
    private val viewPrefs = app.getSharedPreferences("ember_call", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ low battery
    private var batteryWatch: BatteryWatch? = null
    private val announcedSteps = mutableSetOf<Int>()
    private var told = false
    private var lastBattery: Pair<Int, Boolean>? = null

    /** While in a call: watch the battery, warn me at 20, 15, 10 and 5 percent, and tell the others in the call. */
    private fun startBattery() {
        batteryWatch?.stop()
        announcedSteps.clear()
        told = false
        batteryWatch = BatteryWatch(app) { level, charging -> onBattery(level, charging) }.also { it.start() }
    }

    private fun stopBattery() {
        batteryWatch?.stop()
        batteryWatch = null
        announcedSteps.clear()
        told = false
        lastBattery = null
        app.notifier.clearLowBattery()
    }

    private fun onBattery(level: Int, charging: Boolean) {
        if (_state.value.phase != Phase.Connected || !app.prefs.lowBatteryAlerts.value) return
        lastBattery = level to charging
        val showing = _state.value.lowBattery != null
        if ((charging || level > LowBattery.STEPS.first()) && showing) {
            _state.update { it.copy(lowBattery = null) }
            app.notifier.clearLowBattery()
            if (told) tellBattery(level, charging)
            told = false
        }
        if (LowBattery.crossed(level, charging, announcedSteps)) {
            _state.update { it.copy(lowBattery = level) }
            app.notifier.lowBattery(level)
            buzz()
            told = true
            tellBattery(level, false)
        }
    }

    private fun tellBattery(level: Int, charging: Boolean) {
        val r = room ?: return
        app.scope.launch { runCatching { r.localParticipant.publishData(LowBattery.encode(level, charging), topic = LowBattery.TOPIC) } }
    }

    private fun onPeerBattery(identity: String?, data: ByteArray) {
        val (level, charging) = LowBattery.decode(data) ?: return
        val id = identity ?: return
        if (charging || level > LowBattery.STEPS.first()) {
            _state.update { it.copy(peerBattery = it.peerBattery - id) }
            return
        }
        val name = _state.value.people.firstOrNull { it.identity == id }?.name
            ?: app.repo.users.value[id.substringBefore(':')]?.displayName ?: "Someone"
        _state.update { it.copy(peerBattery = it.peerBattery + (id to PeerBattery(name, level))) }
        buzz()
    }

    private fun buzz() = runCatching {
        val vibrator = app.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        val pattern = longArrayOf(0, 250, 120, 250)
        if (Build.VERSION.SDK_INT >= 26) vibrator?.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
        else @Suppress("DEPRECATION") vibrator?.vibrate(pattern, -1)
    }

    /** Where your own small picture sits, as a fraction of the room it has to move in (0,0 top left to 1,1 bottom right): free, and kept across turning the phone and across calls. */
    val selfPos = MutableStateFlow(androidx.compose.ui.geometry.Offset(viewPrefs.getFloat("selfX", 0.86f), viewPrefs.getFloat("selfY", 0.06f)))
    /** Where the little picture of the other person sits over the rest of the app while a call goes on, as a fraction of the room it can move in. */
    val miniPos = MutableStateFlow(androidx.compose.ui.geometry.Offset(viewPrefs.getFloat("miniX", 1f), viewPrefs.getFloat("miniY", 0.12f)))
    fun saveMiniPos() { viewPrefs.edit().putFloat("miniX", miniPos.value.x).putFloat("miniY", miniPos.value.y).apply() }
    fun saveSelfPos() { viewPrefs.edit().putFloat("selfX", selfPos.value.x).putFloat("selfY", selfPos.value.y).apply() }

    /** People whose sound you switched off for yourself (by identity). Only this phone's playback changes. */
    val mutedForMe = MutableStateFlow<Set<String>>(emptySet())
    fun toggleMuteFor(identity: String) {
        mutedForMe.update { if (identity in it) it - identity else it + identity }
        checkRemoteAudio("local playback choice")
        rebuild()
    }

    /** How other people's pictures are drawn: null = by themselves (whole picture when its shape does not match the window), true = whole picture, false = filling the window. */
    val fitMode = MutableStateFlow<Boolean?>(true)
    /** What is on screen right now, so the button can say what a press will do. */
    val shownFit = MutableStateFlow(true)
    /** In a group each tile fits or fills by itself until the button is pressed: what each tile shows now, so the button reflects the tiles and not just the last choice. */
    val tileFit = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    fun gridFit(): Boolean = fitMode.value ?: tileFit.values.any { it }
    fun toggleFit() {
        // Taken from what is shown and applied to what is shown at once, so a quick second press never acts on a value that has not caught up yet.
        val next = !shownFit.value
        shownFit.value = next
        fitMode.value = next
    }
    /** Set it from what a tile is showing right now, so a quick second press never acts on a value that has not caught up yet. */
    fun setFit(fit: Boolean) { fitMode.value = fit }
    private val frameRatios = java.util.concurrent.ConcurrentHashMap<String, Float>()
    @Volatile private var pipIdentity: String? = null

    /** The last shape seen for someone's picture (0 when none yet), so a picture that changes places is drawn in its own shape at once. */
    fun knownRatio(identity: String): Float = frameRatios[identity] ?: 0f

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
    private var audio = AudioSwitchHandler(app)
    private var capturer: LensCapturer? = null
    private var cameraTrack: LocalVideoTrack? = null
    private var job: Job? = null
    private var connectJob: Job? = null
    private var receiveJob: Job? = null
    private var microphoneActivity = MicrophoneActivity()
    private var meterTrack: io.livekit.android.room.track.LocalAudioTrack? = null
    private var meterSink: livekit.org.webrtc.AudioTrackSink? = null
    private var meterJob: Job? = null
    @Volatile private var meterGeneration = 0
    private var localSpeaking = false
    private val receiveHealth = ReceiveHealth()
    private val repairing = mutableSetOf<String>()
    private val lastSpeech = mutableMapOf<String, Long>()
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
        // The speaker or the earpiece you last chose is where the next call starts; with nothing chosen yet, a video call starts on the speaker.
        wantSpeaker = if (viewPrefs.contains("speaker")) viewPrefs.getBoolean("speaker", false) else video
        val lenses = Lenses.list(app)
        // A video call starts with the camera on. Joining or answering starts the way the camera was left last time (on or off).
        val camWanted = video || (viewPrefs.contains("cam") && viewPrefs.getBoolean("cam", false))
        _state.value = CallState(
            phase = Phase.Lobby, roomId = roomId, title = room0?.title(app.repo.me.value?.id ?: "") ?: "Call", video = video,
            micOn = true, camOn = camWanted && lenses.isNotEmpty(), lenses = lenses, lens = lenses.firstOrNull { it.front } ?: lenses.firstOrNull(),
            ringing = false,
        )
        ensureRoom()
        if (_state.value.camOn) startCamera()
    }

    private fun ensureRoom(): Room {
        room?.let { return it }
        // A released room owns/stops its handler. Never share it with a rejoin.
        audio = AudioSwitchHandler(app)
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
            // adaptiveStream is off on purpose: it pauses a picture the moment its view leaves the screen (into the floating window, or while the call
            // screen is not showing), and bringing everyone's pictures back on return then takes seconds in a group. Dynacast still saves the sender's work.
            adaptiveStream = false, dynacast = true,
            e2eeOptions = keyProvider?.let { io.livekit.android.e2ee.E2EEOptions(keyProvider = it) },
            screenShareTrackCaptureDefaults = LocalVideoTrackOptions(isScreencast = true, captureParams = VideoCaptureParameter(1920, 1080, 60)),
            screenShareTrackPublishDefaults = io.livekit.android.room.participant.VideoTrackPublishDefaults(
                videoEncoding = io.livekit.android.room.track.VideoEncoding(8_000_000, 60), simulcast = false,
            ),
        )
        val r = LiveKit.create(app, share, LiveKitOverrides(
            audioOptions = AudioOptions(
                audioHandler = audio,
                // By default the phone's own echo canceller and noise suppressor do the work, as they always did. Settings can switch to WebRTC's
                // software ones for a phone whose own does badly on speakerphone.
                javaAudioDeviceModuleCustomizer = { b ->
                    if (app.prefs.softwareEcho.value) b.setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
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
        if (_state.value.phase == Phase.Connected) app.scope.launch {
            runCatching {
                val participant = room?.localParticipant ?: error("The call has disconnected.")
                check(participant.setMicrophoneEnabled(on)) { "Could not change the microphone. Check the connection and microphone permission." }
            }.onSuccess {
                _state.update { s -> s.copy(error = null) }
            }.onFailure {
                _state.update { s -> s.copy(micOn = !on, error = it.message ?: "Could not change the microphone.") }
            }
            push()
        }
        // The call notification says Mute or Unmute to match.
        if (_state.value.phase == Phase.Connected || _state.value.phase == Phase.Connecting) CallService.start(app)
    }

    fun toggleCamera() {
        // What the person chose is remembered for the next call, even if the camera fails to start this time.
        viewPrefs.edit().putBoolean("cam", !_state.value.camOn).apply()
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

    /** How a shared screen is sent: Gaming keeps the frame rate up, Sharp text keeps the picture sharp. The last choice is remembered. */
    enum class ShareQuality(val label: String, val note: String, val fps: Int, val bitrate: Long) {
        Gaming("Gaming", "60 frames a second", 60, 9_000_000),
        Everyday("Everyday", "30 frames a second", 30, 4_500_000),
        Sharp("Sharp text", "15 frames a second", 15, 3_500_000),
    }

    var shareQuality: ShareQuality
        get() = ShareQuality.entries.firstOrNull { it.name == viewPrefs.getString("shareQuality", null) } ?: ShareQuality.Everyday
        set(v) { viewPrefs.edit().putString("shareQuality", v.name).apply() }

    /** [permission] is what the system's "start recording or casting" dialog returned. */
    fun startScreen(permission: android.content.Intent) {
        if (_state.value.phase != Phase.Connected) return
        val q = shareQuality
        room?.localParticipant?.let { lp ->
            lp.screenShareTrackCaptureDefaults = LocalVideoTrackOptions(isScreencast = true, captureParams = VideoCaptureParameter(1920, 1080, q.fps))
            lp.screenShareTrackPublishDefaults = io.livekit.android.room.participant.VideoTrackPublishDefaults(
                videoEncoding = io.livekit.android.room.track.VideoEncoding(q.bitrate.toInt(), q.fps), simulcast = false,
            )
        }
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
        viewPrefs.edit().putBoolean("speaker", wantSpeaker).apply()
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
        if (target != null) { wantSpeaker = target is AudioDevice.Speakerphone; viewPrefs.edit().putBoolean("speaker", wantSpeaker).apply(); telecomRoute(target); audio.selectDevice(target) }
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
        CallService.start(app)
        val live = app.repo.calls.value[roomId]
        when {
            answer -> app.realtime.acceptCall(roomId)
            live == null -> app.realtime.startCall(roomId)
        }
        app.callNotifier.stop(roomId)
        connectJob = app.scope.launch {
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
                val roomAudio = audio
                roomAudio.registerAudioDeviceChangeListener { _, _ -> if (room === r && audio === roomAudio) { refreshOutputs(); applyDefaultRoute() } }
                if (_state.value.micOn && !r.localParticipant.setMicrophoneEnabled(true)) {
                    _state.update { it.copy(micOn = false, error = "The microphone could not start. Check its permission or rejoin the call.") }
                }
                if (_state.value.camOn) publishCamera()
                app.realtime.joinCall(roomId)
                _state.update { it.copy(phase = Phase.Connected, startedAt = live?.startedAt ?: System.currentTimeMillis(), ringing = live == null) }
                startBattery()
                app.scope.launch { for (wait in listOf(2000L, 4000L, 8000L)) { delay(wait); if (room === r) checkRemoteAudio("after ${wait / 1000} s") } }
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
                // The audio layer is still starting up when the first route is asked for and can put the sound back on its own choice, which
                // left the button saying speaker (or earpiece) while the sound came from the other one until it was pressed twice. The
                // remembered route is asked for again as the call settles.
                app.scope.launch { for (wait in listOf(700L, 1500L, 3500L)) { delay(wait); if (room === r && _state.value.phase == Phase.Connected) enforceRoute() } }
                push()
                startHeartbeat(roomId)
                receiveJob?.cancel()
                receiveJob = app.scope.launch {
                    while (isActive && room === r) {
                        delay(3_000)
                        for (p in r.remoteParticipants.values.toList()) {
                            val pub = p.getTrackPublication(Track.Source.MICROPHONE) as? io.livekit.android.room.track.RemoteTrackPublication ?: continue
                            val track = pub.track as? io.livekit.android.room.track.RemoteAudioTrack ?: continue
                            val report = try { track.getRTCStats() } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) { null } ?: continue
                            if (room !== r) return@launch
                            val now = android.os.SystemClock.elapsedRealtime()
                            val recentSpeech = p.isSpeaking || lastSpeech[p.identity?.value]?.let { now - it < 1_500 } == true
                            val bytes = report.statsMap.values.filter { it.type == "inbound-rtp" }.sumOf { (it.members["bytesReceived"] as? Number)?.toLong() ?: 0L }
                            val eligible = recentSpeech && !pub.muted && p.identity?.value !in mutedForMe.value
                            if (receiveHealth.stalled(pub.sid, bytes, eligible, now)) {
                                repairing.add(pub.sid)
                                try {
                                    Log.w("Call", "repair stalled receiver ${pub.sid}: remote speech with no received-byte progress")
                                    pub.setSubscribed(false)
                                    delay(250)
                                    if (room === r && p.getTrackPublication(Track.Source.MICROPHONE) === pub) pub.setSubscribed(true)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w("Call", "receiver repair failed for ${pub.sid}", e)
                                } finally { repairing.remove(pub.sid) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("Call", "connect failed", e)
                // A failed room must not be reused by the next attempt.
                hangup()
                _state.update { it.copy(phase = Phase.Failed, error = e.message ?: "Could not connect the call.") }
                runCatching { app.realtime.leaveCall(roomId) }
            }
        }
    }

    /** Put the sound where the person last had it (a headset wins), if it is somewhere else. */
    private fun enforceRoute() {
        val list = audio.availableAudioDevices
        if (list.isEmpty()) return
        val headset = list.firstOrNull { it is AudioDevice.BluetoothHeadset } ?: list.firstOrNull { it is AudioDevice.WiredHeadset }
        val target = headset ?: if (wantSpeaker) list.firstOrNull { it is AudioDevice.Speakerphone } else list.firstOrNull { it is AudioDevice.Earpiece }
        if (target != null && audio.selectedAudioDevice?.name != target.name) {
            telecomRoute(target)
            audio.selectDevice(target)
            refreshOutputs()
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
            // The system call owns the route (Samsung's above all), so the start is requested from it as well, as a tap on the button is.
            if (target != null) { telecomRoute(target); audio.selectDevice(target) }
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
                checkRemoteAudio("heartbeat")
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
        stopMicrophoneMeter()
        fitMode.value = true
        tileFit.clear()
        mutedForMe.value = emptySet()
        frameRatios.clear()
        val s = _state.value
        val wasInCall = s.phase == Phase.Connected || s.phase == Phase.Connecting
        val roomId = s.roomId
        connectJob?.cancel()
        connectJob = null
        receiveJob?.cancel(); receiveJob = null
        receiveHealth.clear(); repairing.clear(); lastSpeech.clear()
        heartbeat?.cancel()
        stopBattery()
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
    /**
     * Everyone's voice must actually be arriving. After leaving and rejoining, a person's microphone sometimes stayed silent until they muted and
     * unmuted themselves: so what the room says about each microphone is logged (to see it in `adb logcat -s Call`), and a microphone that is not
     * subscribed is subscribed again.
     */
    private fun checkRemoteAudio(reason: String) {
        val r = room ?: return
        for (p in r.remoteParticipants.values) {
            val pub = p.getTrackPublication(Track.Source.MICROPHONE) as? io.livekit.android.room.track.RemoteTrackPublication ?: continue
            android.util.Log.i("Call", "voice of ${p.identity?.value}: $reason, subscribed=${pub.subscribed}, track=${pub.track != null}, muted=${pub.muted}")
            if (!pub.subscribed && pub.sid !in repairing) runCatching { pub.setSubscribed(true) }
            (pub.track as? io.livekit.android.room.track.RemoteAudioTrack)?.let { runCatching { it.setVolume(if (p.identity?.value in mutedForMe.value) 0.0 else 1.0) } }
        }
    }

    private fun onEvent(event: RoomEvent) {
        if (event.room !== room) return
        if (event is RoomEvent.ActiveSpeakersChanged) {
            val now = android.os.SystemClock.elapsedRealtime()
            for (p in event.speakers) p.identity?.value?.let { lastSpeech[it] = now }
        }
        if (event is RoomEvent.Reconnected) { receiveHealth.clear(); checkRemoteAudio("reconnected") }
        if (event is RoomEvent.TrackE2EEStateEvent) android.util.Log.d("EmberE2EE", "track ${event.publication.sid} ${event.participant.identity} -> ${event.state}")
        when (event) {
            is RoomEvent.Disconnected -> if (_state.value.phase == Phase.Connected) {
                // The server or network ended it: say so, then clean up.
                hangup()
                return
            }
            is RoomEvent.TrackPublished, is RoomEvent.TrackSubscribed, is RoomEvent.TrackSubscriptionFailed, is RoomEvent.TrackUnsubscribed -> checkRemoteAudio(event.javaClass.simpleName)
            is RoomEvent.ParticipantConnected -> {
                checkRemoteAudio(event.javaClass.simpleName)
                // Someone who joins late still hears that my battery is low.
                if (told) lastBattery?.let { (level, charging) -> if (!charging) tellBattery(level, false) }
            }
            is RoomEvent.DataReceived -> if (event.topic == LowBattery.TOPIC) onPeerBattery(event.participant?.identity?.value, event.data)
            is RoomEvent.ParticipantDisconnected -> {
                val gone = event.participant.identity?.value
                if (gone != null) _state.update { it.copy(peerBattery = it.peerBattery - gone) }
            }
            else -> {}
        }
        rebuild()
    }

    private fun stopMicrophoneMeter() {
        meterGeneration++
        meterJob?.cancel(); meterJob = null
        val sink = meterSink
        if (sink != null) runCatching { meterTrack?.removeSink(sink) }
        meterTrack = null; meterSink = null
        microphoneActivity = MicrophoneActivity(); localSpeaking = false
    }

    private fun updateMicrophoneMeter(r: Room) {
        val track = r.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track as? io.livekit.android.room.track.LocalAudioTrack
        if (track === meterTrack) return
        stopMicrophoneMeter()
        if (track == null) return
        val generation = meterGeneration
        val activity = microphoneActivity
        val sink = livekit.org.webrtc.AudioTrackSink { data, bits, _, _, _, _ ->
            if (meterGeneration == generation) activity.accept(data, bits, android.os.SystemClock.elapsedRealtime())
        }
        meterTrack = track; meterSink = sink
        runCatching { track.addSink(sink) }.onFailure { stopMicrophoneMeter(); return }
        meterJob = app.scope.launch {
            var hadSamples = false
            while (isActive && room === r) {
                delay(50)
                val speaking = activity.active(android.os.SystemClock.elapsedRealtime(), _state.value.micOn && r.localParticipant.isMicrophoneEnabled)
                if (speaking != localSpeaking || hadSamples != activity.hasSamples) {
                    hadSamples = activity.hasSamples; localSpeaking = speaking; rebuild()
                }
            }
        }
    }

    private fun rebuild() {
        val r = room ?: return
        updateMicrophoneMeter(r)
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
                speaking = if (local && microphoneActivity.hasSamples) localSpeaking else p.isSpeaking,
                camera = cam?.track as? VideoTrack,
                screen = screen?.track as? VideoTrack,
                mutedByMe = !local && identity in mutedForMe.value,
                screenRatio = screen?.dimensions?.let { if (it.width > 0 && it.height > 0) it.width.toFloat() / it.height else null } ?: (16f / 9f),
            )
        }
        // Volume is applied on track events and user choices, not every speaker-level update.
        val people = listOf(person(r.localParticipant, true)) + r.remoteParticipants.values.map { person(it, false) }
        // The floating window shows the speaker's (or first) camera: take that picture's shape.
        val shown = r.remoteParticipants.values.firstOrNull { it.isSpeaking && it.getTrackPublication(Track.Source.CAMERA)?.track != null }
            ?: r.remoteParticipants.values.firstOrNull { it.getTrackPublication(Track.Source.CAMERA)?.track != null }
        if (r.remoteParticipants.size >= 2) {
            // A group's window keeps one shape whoever is shown (it follows the speaker, and a window that changes shape with every speaker jumps about).
            pipIdentity = null
            setPipRatio(4f / 3f)
        } else {
            pipIdentity = shown?.identity?.value
            val dim = shown?.getTrackPublication(Track.Source.CAMERA)?.dimensions
            val seen = pipIdentity?.let { frameRatios[it] }
            if (seen != null) setPipRatio(seen)
            else if (dim != null && dim.width > 0 && dim.height > 0) setPipRatio(dim.width.toFloat() / dim.height)
        }
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
        fun attempt(n: Int) {
            runCatching { activity.enterPictureInPictureMode(pipParams(activity)) }
                .onSuccess {
                    Log.i("Call", "entered pip: $it (try $n)")
                    // The system sometimes says no while the screen is still changing over; ask again a moment later, a few times.
                    if (!it && n < 3 && !pipMode.value) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (!pipMode.value && !activity.isFinishing) attempt(n + 1) }, 150)
                }
                .onFailure { Log.w("Call", "pip failed", it) }
        }
        attempt(1)
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
        actions += action(1, ACTION_MUTE, if (micOn) "Mute" else "Unmute", if (micOn) R.drawable.pip_mic else R.drawable.pip_mic_off)
        actions += action(2, ACTION_HANGUP, "End call", R.drawable.pip_call_end)
        builder.setActions(actions)
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(_state.value.phase == Phase.Connected)
        return builder.build()
    }

    companion object {
        const val ACTION_MUTE = "org.hearth.ember.CALL_MUTE"
        const val ACTION_HANGUP = "org.hearth.ember.CALL_HANGUP"
    }
}
