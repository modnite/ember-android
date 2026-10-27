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
            object : TextureViewRenderer(ctx) {
                override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                    super.onFrameResolutionChanged(videoWidth, videoHeight, rotation)
                    val turned = rotation % 180 != 0
                    val w = if (turned) videoHeight else videoWidth
                    val h = if (turned) videoWidth else videoHeight
                    if (w > 0 && h > 0) post { ratioCallback?.invoke(w.toFloat() / h) }
                }
            }.also {
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
        onDispose { if (view != null && track != null) runCatching { track.removeRenderer(view) } }
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
    Box(Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { controls = !controls }) {
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
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End)).padding(end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.camOn && state.lenses.size > 1) { LensColumn(state, controller); Spacer(Modifier.width(10.dp)) }
                        Column(
                            Modifier.clip(RoundedCornerShape(32.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
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
    // Your picture sits in a corner and can be dragged to any other: let go and it settles into the nearest one.
    var right by remember(landscape) { mutableStateOf(!landscape) }
    var bottom by remember(landscape) { mutableStateOf(landscape) }
    var drag by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var area by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var tileCenter by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Box(Modifier.fillMaxSize().onSizeChanged { area = it }) {
        if (big != null) PersonTile(controller, big, Modifier.fillMaxSize(), big = true) else Waiting(state)
        if (small != null && other != null) {
            val alignment = when {
                right && bottom -> Alignment.BottomEnd
                right -> Alignment.TopEnd
                bottom -> Alignment.BottomStart
                else -> Alignment.TopStart
            }
            Box(
                Modifier.align(alignment)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal.plus(WindowInsetsSides.Vertical)))
                    .padding(top = if (!bottom) 64.dp else 0.dp, bottom = if (bottom) (if (landscape) 14.dp else 120.dp) else 0.dp, start = 12.dp, end = 12.dp)
                    .offset { androidx.compose.ui.unit.IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
                    .width(if (landscape) 150.dp else 104.dp).aspectRatio(if (landscape) 1.5f else 0.72f)
                    .clip(RoundedCornerShape(16.dp)).border(1.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                    .onGloballyPositioned { c -> tileCenter = c.positionInParent() + androidx.compose.ui.geometry.Offset(c.size.width / 2f, c.size.height / 2f) }
                    .pointerInput(Unit) { detectTapGestures { onSwap() } }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, amount -> change.consume(); drag += amount },
                            onDragEnd = {
                                right = tileCenter.x > area.width / 2f
                                bottom = tileCenter.y > area.height / 2f
                                drag = androidx.compose.ui.geometry.Offset.Zero
                            },
                            onDragCancel = { drag = androidx.compose.ui.geometry.Offset.Zero },
                        )
                    },
            ) { PersonTile(controller, small, Modifier.fillMaxSize(), big = false) }
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
    var ratio by remember(p.identity) { mutableStateOf(0f) }
    var userFit by remember(p.identity) { mutableStateOf<Boolean?>(null) }
    var tile by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val tileRatio = if (tile.height > 0) tile.width.toFloat() / tile.height else 1f
    val autoFit = ratio > 0f && (ratio > 1f) != (tileRatio > 1f)
    val fit = forceFit || (userFit ?: autoFit)
    Box(
        modifier.background(Color(0xFF241C18)).onSizeChanged { tile = it }
            .then(if (ring && speaking) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val track = p.camera
        if (p.camOn && track != null) {
            key(track) {
                VideoView(
                    controller.room, track, Modifier.fillMaxSize(),
                    mirror = p.isLocal && controller.state.value.lens?.front == true, fill = !fit,
                    onRatio = { ratio = it; if (!p.isLocal) controller.noteFrame(p.identity, it) },
                )
            }
        } else {
            Avatar(p.name, user?.avatar, user?.let { parseColor(it.avatarColor) } ?: Color.Gray, if (big) 120.dp else 56.dp)
        }
        // The fit/fill button comes and goes with the call controls (a tap on the picture shows or hides both).
        if (!forceFit && p.camOn && track != null && ratio > 0f && !p.isLocal) androidx.compose.animation.AnimatedVisibility(
            LocalCallControls.current, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Box(
                Modifier.padding(if (big) 14.dp else 6.dp).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(top = if (big) 56.dp else 0.dp)
                    .size(if (big) 40.dp else 30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable { userFit = !fit },
                contentAlignment = Alignment.Center,
            ) { Icon(if (fit) Icons.Filled.Fullscreen else Icons.Filled.FitScreen, if (fit) "Fill the window" else "Fit the whole picture", tint = Color.White, modifier = Modifier.size(if (big) 22.dp else 16.dp)) }
        }
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
        Modifier.size(if (small) (if (danger) 52.dp else 46.dp) else if (danger) 58.dp else 52.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = fg, modifier = Modifier.size(if (small) 22.dp else 26.dp)) }
}
