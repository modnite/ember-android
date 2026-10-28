package org.hearth.ember.call

import android.Manifest
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.filled.Call
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxHeight
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.delay
import livekit.org.webrtc.RendererCommon
import org.hearth.ember.ember
import org.hearth.ember.ui.callTimer
import org.hearth.ember.ui.components.Avatar
import org.hearth.ember.ui.components.parseColor
import org.hearth.ember.ui.theme.EmberTheme

/**
 * Leave the call screen, and only that. Ending a call pops the screen from the End button and again when the call turns idle; a plain
 * pop on the second time removed the chat list as well and left the app on a black, empty window.
 */
fun NavHostController.leaveCall() {
    if (currentDestination?.route?.startsWith("call/") == true) popBackStack()
}

fun callRoutes(builder: NavGraphBuilder, nav: NavHostController) {
    builder.composable(
        "call/{roomId}?video={video}&answer={answer}",
        arguments = listOf(
            navArgument("roomId") { type = NavType.StringType },
            navArgument("video") { type = NavType.BoolType; defaultValue = true },
            navArgument("answer") { type = NavType.BoolType; defaultValue = false },
        ),
    ) { entry ->
        CallScreen(
            nav, entry.arguments?.getString("roomId") ?: "",
            entry.arguments?.getBoolean("video") ?: true, entry.arguments?.getBoolean("answer") ?: false,
        )
    }
}

@Composable
fun CallScreen(nav: NavHostController, roomId: String, video: Boolean, answer: Boolean) {
    val context = LocalContext.current
    // A keyboard left open in the chat the call started from would sit over the answer and hang-up buttons.
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(Unit) { focus.clearFocus(); keyboard?.hide() }
    val app = context.ember
    val controller = app.callController ?: CallController(app).also { app.callController = it }
    val state by controller.state.collectAsState()
    val pip by controller.pipMode.collectAsState()

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val micOk = granted[Manifest.permission.RECORD_AUDIO] == true
        if (!micOk) { nav.leaveCall(); return@rememberLauncherForActivityResult }
        controller.openLobby(roomId, video && granted[Manifest.permission.CAMERA] == true)
        if (answer) controller.go(answer = true)
    }
    LaunchedEffect(roomId) {
        if (state.roomId == roomId && state.phase != Phase.Idle) return@LaunchedEffect // already in it: come back to it
        perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
    }
    // The call ended (hung up, dropped, declined): leave the screen.
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(state.phase, state.roomId) {
        if (state.phase != Phase.Idle) started = true
        // Only once this screen has seen a call begin: at the very start the state is Idle while the permission prompt is up.
        else if (started) nav.leaveCall()
    }
    // A call screen that never gets a call (restored after the call is long gone, a duplicate entry) must not sit there blank.
    LaunchedEffect(Unit) {
        delay(3000)
        if (!started && controller.state.value.phase == Phase.Idle) nav.leaveCall()
    }
    // Back while on a call: float it, like Element and WhatsApp, instead of ending it.
    // Back from a call goes back to the chats with the call still running (a bar across the top returns to it); leaving the app floats it.
    BackHandler(enabled = state.phase == Phase.Connected) { nav.leaveCall() }
    BackHandler(enabled = state.phase == Phase.Lobby) { controller.hangup(); nav.leaveCall() }

    Box(Modifier.fillMaxSize().background(EmberTheme.colors.callBackground)) {
        when (state.phase) {
            Phase.Lobby, Phase.Failed -> Lobby(controller, state, answer) { nav.leaveCall() }
            Phase.Connecting, Phase.Connected -> InCall(controller, state, pip) { nav.leaveCall() }
            Phase.Idle -> {}
        }
    }
}

// ----------------------------------------------------------------------------------------------- video

/** Whether the call controls are showing: the picture's own buttons follow them. */
val LocalCallControls = androidx.compose.runtime.compositionLocalOf { true }

/**
 * One LiveKit video track drawn into a view. Re-binds when the track changes. [onRatio] hears the real shape of the picture (width over height,
 * after any turn the sender's phone applied), which is what decides whether it fits a tile or a floating window.
 */
@Composable
fun VideoView(room: Room?, track: VideoTrack?, modifier: Modifier = Modifier, mirror: Boolean = false, fill: Boolean = true, onRatio: ((Float) -> Unit)? = null) {
    if (room == null) return
    val renderer = remember { arrayOfNulls<TextureViewRenderer>(1) }
    val ratioCallback by androidx.compose.runtime.rememberUpdatedState(onRatio)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureViewRenderer(ctx).also {
                room.initVideoRenderer(it)
                renderer[0] = it
            }
        },
        update = {
            it.setMirror(mirror)
            // Fill crops the picture to the window; fit shows all of it with bars. A landscape picture in a portrait window is best fitted.
            val type = if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT
            it.setScalingType(type, type)
        },
    )
    DisposableEffect(track, room) {
        val view = renderer[0]
        if (view != null && track != null) track.addRenderer(view)
        // A second listener on the same track, only to learn the picture's real shape: the renderer itself reports nothing unless it is
        // given an events object when it is set up, and the room sets it up without one.
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var last = 0f
        // A frame of the OLD track can still be queued when a picture changes hands (the two pictures swap places): it must not be taken for the new one's shape.
        var disposed = false
        val shape = livekit.org.webrtc.VideoSink { frame ->
            val w = frame.rotatedWidth
            val h = frame.rotatedHeight
            if (w > 0 && h > 0) {
                val r = w.toFloat() / h
                if (Math.abs(r - last) > 0.01f) { last = r; main.post { if (!disposed) ratioCallback?.invoke(r) } }
            }
        }
        if (track != null) track.addRenderer(shape)
        onDispose {
            disposed = true
            if (view != null && track != null) runCatching { track.removeRenderer(view) }
            if (track != null) runCatching { track.removeRenderer(shape) }
        }
    }
}

// ----------------------------------------------------------------------------------------------- lobby

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Lobby(controller: CallController, state: CallState, answering: Boolean, onClose: () -> Unit) {
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val preview: @Composable (Modifier) -> Unit = { m ->
        Box(m.clip(RoundedCornerShape(24.dp)).background(Color(0xFF241C18)), contentAlignment = Alignment.Center) {
            if (state.camOn && state.preview != null) {
                VideoView(controller.room, state.preview, Modifier.fillMaxSize(), mirror = state.lens?.front == true)
            } else {
                Icon(Icons.Filled.VideocamOff, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(56.dp))
            }
            // The person picks the lens right on the preview, the way a camera app does.
            if (state.camOn && state.lenses.size > 1) LensPicker(state, controller, Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
    }
    val controls: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(if (state.micOn) Icons.Filled.Mic else Icons.Filled.MicOff, if (state.micOn) "Mute" else "Unmute", off = !state.micOn) { controller.toggleMic() }
            if (state.lenses.isNotEmpty()) RoundButton(if (state.camOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, if (state.camOn) "Camera off" else "Camera on", off = !state.camOn) { controller.toggleCamera() }
            if (state.camOn && state.lenses.size > 1) RoundButton(Icons.Filled.Cameraswitch, "Flip camera") { controller.flipCamera() }
        }
    }
    val go: @Composable () -> Unit = {
        androidx.compose.material3.Button(
            onClick = { controller.go(answer = answering) },
            enabled = state.phase == Phase.Lobby,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(27.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) { Text(if (answering) "Answer" else if (controller.liveCallExists(state.roomId)) "Join call" else "Start call", style = MaterialTheme.typography.titleMedium) }
        TextButton(onClick = { controller.hangup(); onClose() }) { Text("Cancel", color = Color.White.copy(alpha = 0.7f)) }
    }
    val heading: @Composable () -> Unit = {
        Text(state.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(
            if (state.error != null) state.error else "Check how you look and sound before you join",
            color = if (state.error != null) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.65f),
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
    }
    if (landscape) {
        // On its side: the picture on the left, the choices on the right.
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            preview(Modifier.weight(1.4f).fillMaxSize())
            Column(Modifier.weight(1f).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                heading()
                controls()
                Spacer(Modifier.height(20.dp))
                go()
            }
        }
    } else {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { controller.hangup(); onClose() }) { Icon(Icons.Filled.KeyboardArrowDown, "Close", tint = Color.White) }
                Spacer(Modifier.weight(1f))
            }
            heading()
            preview(Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(18.dp))
            controls()
            Spacer(Modifier.height(20.dp))
            go()
        }
    }
}

/** The lens choices stacked, for a phone on its side. */
@Composable
private fun LensColumn(state: CallState, controller: CallController) {
    Column(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (lens in state.lenses) {
            val on = state.lens?.id == lens.id
            Text(
                if (lens.front) "Front" else lens.label.substringAfterLast(' '),
                color = if (on) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Color.White else Color.Transparent)
                    .clickable { controller.selectLens(lens) }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun LensPicker(state: CallState, controller: CallController, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (lens in state.lenses) {
            val on = state.lens?.id == lens.id
            Text(
                if (lens.front) "Front" else lens.label.substringAfterLast(' '),
                color = if (on) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Color.White else Color.Transparent)
                    .clickable { controller.selectLens(lens) }.padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

// ----------------------------------------------------------------------------------------------- in a call

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InCall(controller: CallController, state: CallState, pip: Boolean, onMinimize: () -> Unit) {
    val context = LocalContext.current
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var controls by remember { mutableStateOf(true) }
    var outputSheet by remember { mutableStateOf(false) }
    var swapped by remember { mutableStateOf(false) }
    val others = state.people.filter { !it.isLocal }
    val me = state.people.firstOrNull { it.isLocal }
    val now by produceNow()
    val shareLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) controller.startScreen(data)
    }
    val sharer = others.firstOrNull { it.screen != null }

    // Hide the controls a few seconds after the last touch while there is video, as WhatsApp does.
    LaunchedEffect(controls, state.people.any { it.camOn }) {
        if (controls && others.any { it.camOn }) { delay(4500); controls = false }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalCallControls provides controls) {
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onDoubleTap = { controller.toggleFit() }, onTap = { controls = !controls }) }) {
        when {
            pip -> PipContent(controller, state, others, me)
            sharer != null -> ScreenStage(controller, sharer, listOf(sharer) + others.filter { it !== sharer } + listOfNotNull(me), landscape)
            others.size <= 1 -> OneToOne(controller, state, others.firstOrNull(), me, swapped, landscape, onSwap = { swapped = !swapped })
            else -> Grid(controller, state.people, landscape)
        }

        if (!pip) {
            AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Row(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top.plus(WindowInsetsSides.Horizontal))).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onMinimize) { Icon(Icons.Filled.KeyboardArrowDown, "Back to chats", tint = Color.White) }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(state.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when {
                                state.phase == Phase.Connecting -> "Connecting…"
                                others.isEmpty() -> if (state.ringing) "Ringing…" else "Waiting for others"
                                else -> callTimer(now - state.startedAt)
                            },
                            color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            val buttons: @Composable () -> Unit = {
                val out = state.output
                RoundButton(
                    when (out?.kind) { CallOutput.Kind.Speaker -> Icons.Filled.VolumeUp; CallOutput.Kind.Bluetooth -> Icons.Filled.Bluetooth; CallOutput.Kind.Wired -> Icons.Filled.Headset; else -> Icons.Filled.Hearing },
                    "Sound output", on = out?.kind == CallOutput.Kind.Speaker, small = landscape,
                ) { if (state.outputs.size > 2) outputSheet = true else controller.toggleSpeaker() }
                RoundButton(if (state.camOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, if (state.camOn) "Camera off" else "Camera on", off = !state.camOn, small = landscape) { controller.toggleCamera() }
                // Fit the whole picture of the other person, or fill the window with it (a double tap on the picture does the same).
                if (others.any { it.camOn }) {
                    val fitting by controller.shownFit.collectAsState()
                    RoundButton(if (fitting) Icons.Filled.Fullscreen else Icons.Filled.FitScreen, if (fitting) "Fill the window" else "Fit the whole picture", small = landscape) { controller.toggleFit() }
                }
                RoundButton(
                    if (state.screenOn) Icons.Filled.StopScreenShare else Icons.Filled.ScreenShare, if (state.screenOn) "Stop sharing" else "Share screen",
                    on = state.screenOn, small = landscape,
                ) {
                    if (state.screenOn) controller.stopScreen()
                    else {
                        val manager = context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
                        shareLauncher.launch(manager.createScreenCaptureIntent())
                    }
                }
                RoundButton(if (state.micOn) Icons.Filled.Mic else Icons.Filled.MicOff, if (state.micOn) "Mute" else "Unmute", off = !state.micOn, small = landscape) { controller.toggleMic() }
                RoundButton(Icons.Filled.CallEnd, "End call", danger = true, small = landscape) { controller.hangup(); onMinimize() }
            }
            if (landscape) {
                // On its side the buttons stand in a column at the right edge, clear of the camera cut-out and the video's middle.
                AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterEnd)) {
                    Row(
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End.plus(WindowInsetsSides.Vertical))).padding(end = 10.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.camOn && state.lenses.size > 1) { LensColumn(state, controller); Spacer(Modifier.width(10.dp)) }
                        Column(
                            Modifier.clip(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        ) { buttons() }
                    }
                }
            } else AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Column(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))).navigationBarsPadding().padding(bottom = 18.dp, top = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state.camOn && state.lenses.size > 1) { LensPicker(state, controller); Spacer(Modifier.height(14.dp)) }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { buttons() }
                }
            }
        }
    }

    }
    if (outputSheet) ModalBottomSheet(onDismissRequest = { outputSheet = false }) {
        Text("Sound output", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold)
        for (o in state.outputs) {
            Row(
                Modifier.fillMaxWidth().clickable { controller.selectOutput(o); outputSheet = false }.padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(o.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (state.output?.id == o.id) Text("●", color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun produceNow(): androidx.compose.runtime.State<Long> = androidx.compose.runtime.produceState(System.currentTimeMillis()) {
    while (true) { value = System.currentTimeMillis(); delay(1000) }
}

/** Someone is sharing their screen: it fills the window uncropped, with the people in small pictures along the edge. */
@Composable
private fun ScreenStage(controller: CallController, sharer: CallPerson, rest: List<CallPerson>, landscape: Boolean) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The shared screen is sized to its own shape and centred, so the whole of it shows with bars around it instead of being zoomed to fill.
        val screen: @Composable (Modifier) -> Unit = { modifier ->
            androidx.compose.foundation.layout.BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
                val wide = maxWidth.value / maxHeight.value < sharer.screenRatio
                val shape = if (wide) Modifier.fillMaxWidth().aspectRatio(sharer.screenRatio) else Modifier.fillMaxHeight().aspectRatio(sharer.screenRatio)
                key(sharer.screen) { VideoView(controller.room, sharer.screen, shape, fill = false) }
            }
        }
        if (landscape) {
            // On its side the people stand in a column beside the screen, so nothing sits on top of it.
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                Column(Modifier.fillMaxHeight().width(82.dp).padding(start = 10.dp, top = 64.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
                    for (p in rest.take(3)) PersonTile(controller, p, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)), big = false)
                }
                screen(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            screen(Modifier.fillMaxSize())
            Row(
                Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal.plus(WindowInsetsSides.Top)))
                    .padding(top = 64.dp, start = 10.dp, end = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (p in rest.take(4)) PersonTile(controller, p, Modifier.width(72.dp).aspectRatio(0.75f).clip(RoundedCornerShape(12.dp)), big = false)
            }
        }
        Text(
            if (sharer.isLocal) "You are sharing" else sharer.name + " is sharing", color = Color.White, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(12.dp)
                .clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Two people: the other fills the screen, you are a small picture in the corner. Tap yours to swap. */
@Composable
private fun OneToOne(controller: CallController, state: CallState, other: CallPerson?, me: CallPerson?, swapped: Boolean, landscape: Boolean, onSwap: () -> Unit) {
    val big = if (swapped) me else other
    val small = if (swapped) other else me
    Box(Modifier.fillMaxSize()) {
        if (big != null) PersonTile(controller, big, Modifier.fillMaxSize(), big = true) else Waiting(state)
        if (small != null && other != null) {
            // Your picture goes wherever you drop it, and stays there (also after turning the phone, and in the next call). Tap it to swap the pictures.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
                val tileW = if (landscape) 150.dp else 104.dp
                val tileH = if (landscape) 100.dp else 144.dp
                val density = androidx.compose.ui.platform.LocalDensity.current
                val roomX = with(density) { (maxWidth - tileW).toPx() }.coerceAtLeast(1f)
                val roomY = with(density) { (maxHeight - tileH).toPx() }.coerceAtLeast(1f)
                val pos by controller.selfPos.collectAsState()
                Box(
                    Modifier
                        .offset { androidx.compose.ui.unit.IntOffset((pos.x * roomX).roundToInt(), (pos.y * roomY).roundToInt()) }
                        .size(tileW, tileH)
                        .clip(RoundedCornerShape(16.dp)).border(1.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .pointerInput(Unit) { detectTapGestures { onSwap() } }
                        .pointerInput(roomX, roomY) {
                            detectDragGestures(
                                onDrag = { change, amount ->
                                    change.consume()
                                    val p = controller.selfPos.value
                                    controller.selfPos.value = androidx.compose.ui.geometry.Offset((p.x + amount.x / roomX).coerceIn(0f, 1f), (p.y + amount.y / roomY).coerceIn(0f, 1f))
                                },
                                onDragEnd = { controller.saveSelfPos() },
                                onDragCancel = { controller.saveSelfPos() },
                            )
                        },
                ) { PersonTile(controller, small, Modifier.fillMaxSize(), big = false) }
            }
        }
    }
}

@Composable
private fun Waiting(state: CallState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(if (state.ringing) "Ringing…" else "Waiting for others to join", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Grid(controller: CallController, people: List<CallPerson>, landscape: Boolean) {
    val columns = if (landscape) (if (people.size <= 3) people.size else if (people.size <= 8) 4 else 5) else if (people.size <= 4) 2 else 3
    val rows = (people.size + columns - 1) / columns
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal.plus(WindowInsetsSides.Top)))
            .padding(top = if (landscape) 52.dp else 56.dp, bottom = if (landscape) 10.dp else 120.dp, start = 6.dp, end = if (landscape) 96.dp else 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (r in 0 until rows) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until columns) {
                    val p = people.getOrNull(r * columns + c)
                    if (p == null) Spacer(Modifier.weight(1f)) else PersonTile(controller, p, Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(16.dp)), big = false, ring = true)
                }
            }
        }
    }
}

/**
 * A person: their video if they have it on, else their picture and name. Speaking people get an orange edge. A picture whose shape does not
 * match its tile (a landscape camera in a portrait window, or the reverse) is fitted whole by itself, so nobody is cut off; the corner button
 * (or a tap on the picture) switches between fitting it and filling the tile.
 */
@Composable
private fun PersonTile(controller: CallController, p: CallPerson, modifier: Modifier, big: Boolean, ring: Boolean = false, forceFit: Boolean = false) {
    val app = LocalContext.current.ember
    val user = app.repo.users.collectAsState().value[p.userId]
    val speaking = p.speaking && p.micOn
    var ratio by remember(p.identity) { mutableStateOf(controller.knownRatio(p.identity)) }
    val manualFit by controller.fitMode.collectAsState()
    var tile by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val tileRatio = if (tile.height > 0) tile.width.toFloat() / tile.height else 1f
    val autoFit = ratio > 0f && (ratio > 1f) != (tileRatio > 1f)
    // Your own small picture always fills its little window; every other picture fits or fills as chosen (by itself: whole when the shapes differ).
    val fit = forceFit || (if (p.isLocal && !big) false else (manualFit ?: autoFit))
    // The big picture of someone else tells the call controls what is showing.
    if (big) androidx.compose.runtime.SideEffect { controller.shownFit.value = fit }
    Box(
        modifier.background(Color(0xFF241C18)).onSizeChanged { tile = it }
            .then(if (ring && speaking) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val track = p.camera
        if (p.camOn && track != null) {
            // Fitting: the picture's view is given the picture's own shape inside the tile (bars around it), so nothing is cut off whatever the renderer does.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val showFit = fit && ratio > 0f
                val shape = if (showFit) {
                    if (maxWidth.value / maxHeight.value < ratio) Modifier.fillMaxWidth().aspectRatio(ratio) else Modifier.fillMaxHeight().aspectRatio(ratio)
                } else Modifier.fillMaxSize()
                key(track) {
                    VideoView(
                        controller.room, track, shape,
                        mirror = p.isLocal && controller.state.value.lens?.front == true, fill = !showFit,
                        onRatio = { ratio = it; android.util.Log.i("Call", "picture of ${p.name} is ${"%.2f".format(it)} wide over high"); controller.noteFrame(p.identity, it) },
                    )
                }
            }
        } else {
            Avatar(p.name, user?.avatar, user?.let { parseColor(it.avatarColor) } ?: Color.Gray, if (big) 120.dp else 56.dp)
        }
        // What is going on with this person's sound, always visible: muted by themselves, or muted by you for yourself (the grid labels it below).
        val showBadges = !ring && ((!p.micOn) || p.mutedByMe)
        val canMuteForMe = !p.isLocal && !forceFit
        val buttonShown = canMuteForMe && (!big || LocalCallControls.current)
        if (showBadges || (buttonShown && big)) Row(
            Modifier.align(Alignment.TopStart).then(
                if (big) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top.plus(WindowInsetsSides.Start))).padding(start = 12.dp, top = 64.dp)
                else Modifier.padding(6.dp),
            ),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!p.micOn) Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MicOff, null, tint = Color(0xFFFF8A80), modifier = Modifier.size(if (big) 16.dp else 12.dp))
                if (big) Text(" Muted", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
            if (p.mutedByMe) Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.VolumeOff, null, tint = Color(0xFFFFCC80), modifier = Modifier.size(if (big) 16.dp else 12.dp))
                if (big) Text(" You muted them", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
            if (buttonShown && big) Box(
                Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable { controller.toggleMuteFor(p.identity) },
                contentAlignment = Alignment.Center,
            ) { Icon(if (p.mutedByMe) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, if (p.mutedByMe) "Hear ${p.name} again" else "Mute ${p.name} for me", tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
        if (buttonShown && !big) Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable { controller.toggleMuteFor(p.identity) },
            contentAlignment = Alignment.Center,
        ) { Icon(if (p.mutedByMe) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, if (p.mutedByMe) "Hear ${p.name} again" else "Mute ${p.name} for me", tint = Color.White, modifier = Modifier.size(16.dp)) }
        // Flipping the camera lives on your own picture, as in Element Call, so the control bar stays uncrowded.
        val st = controller.state.collectAsState().value
        if (p.isLocal && p.camOn && st.lenses.size > 1) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(if (big) 14.dp else 6.dp).size(if (big) 44.dp else 32.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)).clickable { controller.flipCamera() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Cameraswitch, "Flip camera", tint = Color.White, modifier = Modifier.size(if (big) 24.dp else 18.dp)) }
        }
        // One-to-one calls carry the name in the top bar; only the group grid labels each picture.
        if (ring) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!p.micOn) { Icon(Icons.Filled.MicOff, null, tint = Color.White, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
                if (p.mutedByMe) { Icon(Icons.Filled.VolumeOff, null, tint = Color(0xFFFFCC80), modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
                Text(if (p.isLocal) "You" else p.name, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/** What the floating window shows: only people's video, nothing to tap, nothing but pictures. The system draws the mute and end controls. */
@Composable
private fun PipContent(controller: CallController, state: CallState, others: List<CallPerson>, me: CallPerson?) {
    val speaker = others.firstOrNull { it.speaking } ?: others.firstOrNull { it.camOn } ?: others.firstOrNull() ?: me
    if (speaker != null) PersonTile(controller, speaker, Modifier.fillMaxSize(), big = false, forceFit = true)
}

/**
 * The other person, in a small window over the rest of the app while a call goes on and its screen is not showing. It stays where you put it (and
 * after turning the phone), a tap goes back to the call. Keeping their picture drawn here also keeps it flowing: coming back to the call shows it at
 * once instead of waiting for it to be asked for again.
 */
@Composable
fun CallMini(controller: CallController, state: CallState, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val person = state.people.firstOrNull { !it.isLocal && it.camOn && it.camera != null } ?: return
    val ratio = controller.knownRatio(person.identity).takeIf { it > 0f } ?: 0.56f
    val landscapePicture = ratio > 1f
    val tileW = if (landscapePicture) 150.dp else 96.dp
    val tileH = (tileW.value / ratio).dp.coerceIn(80.dp, 190.dp)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val roomX = with(density) { (maxWidth - tileW).toPx() }.coerceAtLeast(1f)
        val roomY = with(density) { (maxHeight - tileH).toPx() }.coerceAtLeast(1f)
        val pos by controller.miniPos.collectAsState()
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset((pos.x * roomX).roundToInt(), (pos.y * roomY).roundToInt()) }
                .size(tileW, tileH)
                .clip(RoundedCornerShape(14.dp)).border(1.5.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).background(Color.Black)
                .pointerInput(Unit) { detectTapGestures { onOpen() } }
                .pointerInput(roomX, roomY) {
                    detectDragGestures(
                        onDrag = { change, amount ->
                            change.consume()
                            val p = controller.miniPos.value
                            controller.miniPos.value = androidx.compose.ui.geometry.Offset((p.x + amount.x / roomX).coerceIn(0f, 1f), (p.y + amount.y / roomY).coerceIn(0f, 1f))
                        },
                        onDragEnd = { controller.saveMiniPos() },
                        onDragCancel = { controller.saveMiniPos() },
                    )
                },
        ) {
            key(person.camera) { VideoView(controller.room, person.camera, Modifier.fillMaxSize(), fill = true, onRatio = { controller.noteFrame(person.identity, it) }) }
            if (!person.micOn) Box(Modifier.align(Alignment.BottomStart).padding(4.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(4.dp)) {
                Icon(Icons.Filled.MicOff, null, tint = Color(0xFFFF8A80), modifier = Modifier.size(12.dp))
            }
        }
    }
}

/** The strip across the top of every other screen while a call is going: who, how long, tap to go back to it. */
@Composable
fun CallReturnBar(state: CallState, onReturn: () -> Unit) {
    val now by produceNow()
    Row(
        Modifier.fillMaxWidth().background(EmberTheme.colors.online).clickable(onClick = onReturn)
            .windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Call, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            state.title.ifBlank { "Call" } + if (state.phase == Phase.Connected && state.startedAt > 0) "  ·  " + callTimer(now - state.startedAt) else "  ·  connecting",
            color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text("Tap to return", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelLarge)
    }
}

// ----------------------------------------------------------------------------------------------- buttons

@Composable
private fun RoundButton(icon: ImageVector, label: String, off: Boolean = false, on: Boolean = false, danger: Boolean = false, small: Boolean = false, onClick: () -> Unit) {
    val bg = when {
        danger -> EmberTheme.colors.callDanger
        on -> Color.White
        off -> Color.White.copy(alpha = 0.92f)
        else -> Color.White.copy(alpha = 0.18f)
    }
    val fg = when {
        danger -> Color.White
        on || off -> Color(0xFF1B1411)
        else -> Color.White
    }
    Box(
        Modifier.size(if (small) (if (danger) 44.dp else 38.dp) else if (danger) 58.dp else 52.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = fg, modifier = Modifier.size(if (small) 20.dp else 26.dp)) }
}
