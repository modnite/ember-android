package org.hearth.ember

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.hearth.ember.notify.CallNotifier
import org.hearth.ember.notify.Delivery
import org.hearth.ember.notify.Notifier
import org.hearth.ember.notify.ReceiverService
import org.hearth.ember.ui.ThemeMode
import org.hearth.ember.ui.chat.ChatScreen
import org.hearth.ember.ui.contacts.NewChatScreen
import org.hearth.ember.ui.contacts.NewGroupScreen
import org.hearth.ember.ui.home.HomeScreen
import org.hearth.ember.ui.login.LoginScreen
import org.hearth.ember.ui.settings.ProfileScreen
import org.hearth.ember.ui.theme.EmberTheme

class MainActivity : ComponentActivity() {
    /** A chat the person asked for from outside (a notification, a share): the navigation picks it up once. */
    private val pendingRoom = mutableStateOf<String?>(null)
    private val pendingAnswer = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        ember.sounds // load the chat sounds now, so the first one is ready when it is needed
        if (ember.store.signedIn) ember.sounds.fetchNudge()
        lifecycleScope.launch { runCatching { ember.updater.checkIfDue() } }
        val app = ember
        // Keep the floating-window settings current: it enters by itself on leaving once a call is live, and its Mute button names the right thing.
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(app.callController!!.state.map { it.phase to it.micOn }.distinctUntilChanged(), app.callController!!.pipRatio) { a, b -> a to b }.collect { refreshPip() }
        }
        // The screen stays on for as long as a call is going: it must not dim or sleep under someone who is talking.
        lifecycleScope.launch {
            app.callController!!.state.map { it.phase == org.hearth.ember.call.Phase.Connected || it.phase == org.hearth.ember.call.Phase.Connecting }.distinctUntilChanged().collect { live ->
                if (live) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        setContent {
            val mode by app.prefs.theme.collectAsState()
            val dynamic by app.prefs.dynamicColor.collectAsState()
            val accent by app.prefs.accent.collectAsState()
            val dark = when (mode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark, ThemeMode.Black -> true
            }
            EmberTheme(dark = dark, dynamic = dynamic, accent = accent, black = mode == ThemeMode.Black) {
                val overlays = remember { org.hearth.ember.ui.chat.OverlayHost() }
                androidx.compose.runtime.CompositionLocalProvider(org.hearth.ember.ui.chat.LocalOverlayHost provides overlays) {
                    androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                        val signedIn by app.repo.signedIn.collectAsState()
                        if (!signedIn) LoginScreen() else EmberRoot(pendingRoom, pendingAnswer)
                    }
                    org.hearth.ember.ui.chat.OverlayLayers(overlays)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            readShare(intent)
            return
        }
        val room = intent?.getStringExtra(Notifier.EXTRA_ROOM) ?: return
        when (intent.action) {
            Notifier.ACTION_OPEN_CHAT -> pendingRoom.value = room
            CallNotifier.ACTION_ANSWER -> pendingAnswer.value = room
        }
    }

    /** A link, some words or files from another app's Share menu: remember them; the "Send to" screen opens once signed in. */
    @Suppress("DEPRECATION")
    private fun readShare(intent: Intent) {
        val text = listOfNotNull(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }).firstOrNull()
        val uris = ArrayList<android.net.Uri>()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        else (intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
        if (uris.isEmpty()) intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) } }
        if (text == null && uris.isEmpty()) return
        ember.share.value = org.hearth.ember.ui.chat.SharePayload(text, uris)
    }

    private var shotReceiver: android.content.BroadcastReceiver? = null

    override fun onStart() {
        super.onStart()
        ember.repo.onForeground()
        if (BuildConfig.DEBUG && shotReceiver == null) {
            // Debug builds only: save a picture of this window alone (not other windows over it) for review screenshots.
            shotReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: Intent) {
                    val view = window.decorView
                    val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                    android.view.PixelCopy.request(window, bitmap, { result ->
                        if (result == android.view.PixelCopy.SUCCESS) {
                            val out = java.io.File(getExternalFilesDir("shots"), intent.getStringExtra("name") ?: "shot.png")
                            out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        }
                    }, android.os.Handler(mainLooper))
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(this, shotReceiver, android.content.IntentFilter("org.hearth.ember.SHOT"), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        }
    }

    override fun onStop() {
        ember.repo.onBackground()
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        android.util.Log.i("Call", "onUserLeaveHint")
        ember.callController?.onLeaveHint(this)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ember.callController?.pipMode?.value = isInPictureInPictureMode
    }

    /** Mute/unmute changes the label on the floating window's own button. */
    fun refreshPip() {
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching { setPictureInPictureParams(ember.callController!!.pipParams(this)) }
                .onSuccess { android.util.Log.i("Call", "pip params set, phase=" + ember.callController?.state?.value?.phase) }
                .onFailure { android.util.Log.w("Call", "pip params failed", it) }
        }
    }
}

@Composable
private fun EmberRoot(pendingRoom: androidx.compose.runtime.MutableState<String?>, pendingAnswer: androidx.compose.runtime.MutableState<String?>) {
    val app = androidx.compose.ui.platform.LocalContext.current.ember
    val nav = rememberNavController()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Notifications: ask once, and start the quiet background receiver.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Delivery.apply(app)
    }
    LaunchedEffect(Unit) {
        if (!app.prefs.notificationsOn.value) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) else Delivery.apply(app)
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            // Leaving the app while a backup runs: a notification keeps it alive. Coming back: the strip takes over again.
            if (event == Lifecycle.Event.ON_STOP) org.hearth.ember.data.BackupKeepAlive.onLeft(app)
            if (event == Lifecycle.Event.ON_RESUME) {
                org.hearth.ember.data.BackupKeepAlive.onReturned(app)
                app.repo.onForeground()
                // A scheduled backup Android did not run (phone off, no network, battery low) starts now.
                org.hearth.ember.data.BackupScheduler.catchUp(app)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // The call floats in its own small window: whatever screen was open, the window must show the call and nothing else.
    val pip by (app.callController?.pipMode ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
    val callState by (app.callController?.state ?: kotlinx.coroutines.flow.MutableStateFlow(org.hearth.ember.call.CallState())).collectAsState()
    LaunchedEffect(pip) {
        val id = callState.roomId
        if (pip && id != null && callState.phase == org.hearth.ember.call.Phase.Connected && nav.currentDestination?.route?.startsWith("call/") != true) {
            nav.navigate("call/$id") { launchSingleTop = true }
        }
    }

    // Something shared from another app: ask which chat it goes to (waits for sign-in; the login screen does not reach here).
    val shared by app.share.collectAsState()
    LaunchedEffect(shared) {
        if (shared != null && nav.currentDestination?.route != "share") nav.navigate("share") { launchSingleTop = true }
    }

    val room = pendingRoom.value
    LaunchedEffect(room) {
        if (room != null) {
            nav.navigate("chat/$room") { launchSingleTop = true }
            app.notifier.cancelRoom(room)
            pendingRoom.value = null
        }
    }
    val answer = pendingAnswer.value
    LaunchedEffect(answer) {
        if (answer != null) {
            app.callNotifier.stop(answer)
            nav.navigate("call/$answer?answer=true") { launchSingleTop = true }
            pendingAnswer.value = null
        }
    }

    // While a call is going and its screen is not the one showing, a bar across the top says so and takes you back to it, so the rest of
    // the app (chats, contacts, settings) stays usable during a call.
    val backEntry by nav.currentBackStackEntryAsState()
    val onCallScreen = backEntry?.destination?.route?.startsWith("call/") == true
    val ongoing = callState.phase == org.hearth.ember.call.Phase.Connected || callState.phase == org.hearth.ember.call.Phase.Connecting
    val backupActivity by org.hearth.ember.ui.settings.rememberBackupActivity()
    val callBarShown = ongoing && !pip && !onCallScreen && callState.roomId != null
    // The backup page shows the same thing itself, with more detail, so the strip is for every other screen.
    val onBackupPage = backEntry?.destination?.route == "settings/{page}" && backEntry?.arguments?.getString("page") == "backup"
    val activityShown = backupActivity != null && !pip && !onCallScreen && !onBackupPage
    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
        if (callBarShown) {
            org.hearth.ember.call.CallReturnBar(callState) { nav.navigate("call/${callState.roomId}") { launchSingleTop = true } }
        }
        // A backup or restore going on: one strip over every screen says what it is doing; tapping opens the backup page.
        backupActivity?.let { a ->
            if (activityShown) org.hearth.ember.ui.settings.BackupActivityBar(a, padStatusBar = !callBarShown) { nav.navigate("settings/backup") { launchSingleTop = true } }
        }
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.weight(1f).fillMaxWidth()
                .then(if ((ongoing && !pip && !onCallScreen) || activityShown) androidx.compose.ui.Modifier.consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.statusBars) else androidx.compose.ui.Modifier),
        ) {
            EmberNav(nav)
            // The other person's picture stays on screen over the rest of the app while a call goes on.
            if (ongoing && !pip && !onCallScreen && callState.roomId != null) org.hearth.ember.call.CallMini(app.callController!!, callState) {
                nav.navigate("call/${callState.roomId}") { launchSingleTop = true }
            }
        }
    }
    // A newer Ember was found: say so once per launch and offer to install it.
    val update by app.updater.available.collectAsState()
    var dismissedUpdate by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    update?.let { if (dismissedUpdate != it.version) org.hearth.ember.ui.settings.UpdateDialog(it) { dismissedUpdate = it.version } }
    // Someone calling while the app is open: full screen over everything, answer takes you straight into the call.
    org.hearth.ember.call.IncomingCallScreen(onAnswer = { room -> nav.navigate("call/$room?answer=true") { launchSingleTop = true } })
}

@Composable
private fun EmberNav(nav: NavHostController) {
    NavHost(navController = nav, startDestination = "home") {
        composable("home") { HomeScreen(nav) }
        composable("chat/{roomId}") { entry ->
            val id = entry.arguments?.getString("roomId") ?: ""
            // On a wide screen a chat opens in the pane beside the list, so a request for one (a notification, a new chat) just selects it.
            androidx.compose.foundation.layout.BoxWithConstraints(androidx.compose.ui.Modifier.fillMaxSize()) {
                if (maxWidth >= 1000.dp) {
                    LaunchedEffect(id) { nav.context.ember.selectedRoom.value = id; nav.popBackStack("home", false) }
                } else ChatScreen(nav, id)
            }
        }
        composable("room/{roomId}") { entry -> org.hearth.ember.ui.chat.RoomDetailsScreen(nav, entry.arguments?.getString("roomId") ?: "") }
        composable("settings/{page}") { entry -> org.hearth.ember.ui.settings.SettingsPage(nav, entry.arguments?.getString("page") ?: "") }
        composable("share") { org.hearth.ember.ui.chat.ShareScreen(nav) }
        composable("newchat") { NewChatScreen(nav) }
        composable("newgroup") { NewGroupScreen(nav) }
        composable("profile") { ProfileScreen(nav) }
        org.hearth.ember.call.callRoutes(this, nav)
    }
}
