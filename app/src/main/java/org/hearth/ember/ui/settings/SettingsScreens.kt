package org.hearth.ember.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import org.hearth.ember.BuildConfig
import org.hearth.ember.ember
import org.hearth.ember.notify.ReceiverService
import org.hearth.ember.ui.ThemeMode
import org.hearth.ember.ui.components.UserAvatar
import org.hearth.ember.ui.contacts.SectionLabel


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.ember
    val me by app.repo.me.collectAsState()
    var signOutDialog by remember { mutableStateOf(false) }

    // The title scrolls away with the list: sideways there is hardly any height to spare, and the rows should have it.
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
            )
            Row(
                Modifier.fillMaxWidth().clickable { nav.navigate("profile") }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UserAvatar(me, 64.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(me?.displayName ?: "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        me?.statusMessage?.ifBlank { null } ?: "@${me?.username ?: ""}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            ActionRow(Icons.Filled.Notifications, "Notifications", "Alerts, message text, battery") { nav.navigate("settings/notifications") }
            ActionRow(Icons.Filled.Notifications, "Sounds", "Message sound, ringtone, test") { nav.navigate("settings/sounds") }
            ActionRow(Icons.Filled.DarkMode, "Appearance", "Theme and colours") { nav.navigate("settings/appearance") }
            ActionRow(Icons.Filled.Keyboard, "Chats", "Enter key, chat sounds") { nav.navigate("settings/chats") }
            ActionRow(Icons.Filled.Lock, "Encryption", "The key that opens encrypted chats") { nav.navigate("settings/encryption") }
            ActionRow(Icons.Filled.Search, "GIFs and stickers", "Search Giphy, Imgur and more") { nav.navigate("settings/online") }
            ActionRow(Icons.Filled.Backup, "Backup and restore", "Back up and restore your key and settings") { nav.navigate("settings/backup") }
            UpdateRow()

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                "Signed in to ${app.store.server.removePrefix("https://")}",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActionRow(Icons.AutoMirrored.Filled.Logout, "Sign out", "Remove this phone from your account", danger = true) { signOutDialog = true }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (signOutDialog) AlertDialog(
        onDismissRequest = { signOutDialog = false },
        title = { Text("Sign out?") },
        text = { Text("You will stop getting messages on this phone until you sign in again.") },
        confirmButton = {
            TextButton(onClick = {
                signOutDialog = false
                ReceiverService.stop(app)
                app.notifier.cancelAll()
                app.repo.signOut()
            }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { signOutDialog = false }) { Text("Cancel") } },
    )
}

/** A settings page: its title, a way back, and its rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(nav: NavHostController, page: String) {
    val title = when (page) {
        "notifications" -> "Notifications"
        "sounds" -> "Sounds"
        "appearance" -> "Appearance"
        "chats" -> "Chats"
        "encryption" -> "Encryption"
        "backup" -> "Backup and restore"
        "online" -> "GIFs and stickers"
        else -> "Settings"
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text(title, fontWeight = FontWeight.SemiBold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (page) {
                "notifications" -> NotificationsPage()
                "sounds" -> SoundsPage()
                "appearance" -> AppearancePage()
                "chats" -> ChatsPage()
                "encryption" -> EncryptionPage()
                "backup" -> BackupRows()
                "online" -> OnlineRows()
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun NotificationsPage() {
    val context = LocalContext.current
    val app = context.ember
    val notifications by app.prefs.notificationsOn.collectAsState()
    val previews by app.prefs.previews.collectAsState()
            ToggleRow(Icons.Filled.Notifications, "Message and call alerts", "Stay connected in the background so they arrive on time", notifications) { on ->
                app.prefs.setNotifications(on)
                if (on) ReceiverService.start(app) else ReceiverService.stop(app)
            }
            ToggleRow(Icons.Filled.Visibility, "Show message text", "Turn off to show only “New message” on the lock screen", previews) { app.prefs.setPreviews(it) }
    ActionRow(Icons.Filled.Notifications, "Send a test notification", "See and hear how a message alert looks and sounds") { app.notifier.test() }
            ActionRow(Icons.Filled.BatteryChargingFull, "Battery", "Let Ember run without being put to sleep") {
                val pm = context.getSystemService(PowerManager::class.java)
                val intent = if (pm.isIgnoringBatteryOptimizations(context.packageName)) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
}

@Composable
private fun SoundsPage() {
    val context = LocalContext.current
    val app = context.ember
            val messageSound by app.prefs.messageSound.collectAsState()
            val callSound by app.prefs.callSound.collectAsState()
            fun chosen(result: androidx.activity.result.ActivityResult): Pair<Boolean, String?> {
                if (result.resultCode != android.app.Activity.RESULT_OK) return false to null
                val uri = result.data?.getParcelableExtra<Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                return true to (uri?.toString() ?: "silent")
            }
            val pickMessage = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
                val (ok, v) = chosen(r); if (ok) { app.prefs.setMessageSound(v); app.notifier.createChannels() }
            }
            val pickCall = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
                val (ok, v) = chosen(r); if (ok) { app.prefs.setCallSound(v); app.notifier.createChannels() }
            }
            fun picker(type: Int, title: String, current: String?) = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, type).putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, title)
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true).putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current?.takeIf { it != "silent" }?.let { Uri.parse(it) })
            fun soundName(choice: String?, type: Int): String = when (choice) {
                null -> "Ember"
                "silent" -> "None"
                else -> runCatching { android.media.RingtoneManager.getRingtone(context, Uri.parse(choice)).getTitle(context) }.getOrNull() ?: "Custom sound"
            }
            ActionRow(Icons.Filled.Notifications, "Message sound", soundName(messageSound, android.media.RingtoneManager.TYPE_NOTIFICATION)) {
                pickMessage.launch(picker(android.media.RingtoneManager.TYPE_NOTIFICATION, "Message sound", messageSound))
            }
            ActionRow(Icons.Filled.Notifications, "Ringtone", soundName(callSound, android.media.RingtoneManager.TYPE_RINGTONE)) {
                pickCall.launch(picker(android.media.RingtoneManager.TYPE_RINGTONE, "Call ringtone", callSound))
            }
            ActionRow(Icons.Filled.Notifications, "Use Ember's own sounds", "Go back to the default chime and ringtone") {
                app.prefs.setMessageSound(null); app.prefs.setCallSound(null); app.notifier.createChannels()
            }
            val deviceEcho by app.prefs.deviceEcho.collectAsState()
            ToggleRow(
                Icons.Filled.Hearing, "Phone's own echo cancelling in calls",
                "Off: Ember cancels echo itself, which copes with speakerphone best. Turn on if others say your voice sounds wrong. From the next call.",
                deviceEcho,
            ) { app.prefs.setDeviceEcho(it) }
            ActionRow(Icons.Filled.Notifications, "Send a test notification", "Shows a message alert with your chosen sound") { app.notifier.test() }
            ActionRow(Icons.Filled.Notifications, "Play the ringtone", "Plays it for a few seconds") {
                val uri = when (callSound) { null -> Uri.parse("android.resource://" + context.packageName + "/" + org.hearth.ember.R.raw.ember_ring); "silent" -> null; else -> Uri.parse(callSound) }
                if (uri != null) runCatching {
                    val ring = android.media.RingtoneManager.getRingtone(context, uri)
                    ring.play()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { ring.stop() } }, 4000)
                }
            }
}

@Composable
private fun AppearancePage() {
    val context = LocalContext.current
    val app = context.ember
    val theme by app.prefs.theme.collectAsState()
    val dynamic by app.prefs.dynamicColor.collectAsState()
    var themeDialog by remember { mutableStateOf(false) }
            ActionRow(Icons.Filled.DarkMode, "Theme", when (theme) { ThemeMode.System -> "Same as the phone"; ThemeMode.Light -> "Light"; ThemeMode.Dark -> "Dark"; ThemeMode.Black -> "Black (saves power on OLED)" }) { themeDialog = true }
    AccentRow()
            if (Build.VERSION.SDK_INT >= 31) {
                ToggleRow(Icons.Filled.Palette, "Use phone colours", "Match your wallpaper instead of Ember orange", dynamic) { app.prefs.setDynamicColor(it) }
            }
    if (themeDialog) AlertDialog(
        onDismissRequest = { themeDialog = false },
        title = { Text("Theme") },
        text = {
            Column {
                for ((mode, label) in listOf(ThemeMode.System to "Same as the phone", ThemeMode.Light to "Light", ThemeMode.Dark to "Dark", ThemeMode.Black to "Black (pure black, saves power on OLED)")) {
                    Row(
                        Modifier.fillMaxWidth().clickable { app.prefs.setTheme(mode); themeDialog = false }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = theme == mode, onClick = { app.prefs.setTheme(mode); themeDialog = false })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { themeDialog = false }) { Text("Close") } },
    )
}

@Composable
private fun ChatsPage() {
    val context = LocalContext.current
    val app = context.ember
    val enterSends by app.prefs.enterSends.collectAsState()
    val soundsOn by app.prefs.soundsOn.collectAsState()
            ToggleRow(Icons.Filled.Keyboard, "Enter sends", "Use the Enter key to send instead of adding a new line", enterSends) { app.prefs.setEnterSends(it) }
    ToggleRow(Icons.Filled.Notifications, "Chat sounds", "Small sounds in a chat for messages arriving, sending and typing", soundsOn) { on -> app.prefs.setSoundsOn(on) }
}

@Composable
private fun EncryptionPage() {
    val context = LocalContext.current
    val app = context.ember
            val e2ee by app.e2ee.status.collectAsState()
            val scope = rememberCoroutineScope()
            ActionRow(
                Icons.Filled.Lock, "End-to-end encryption",
                when (e2ee) {
                    org.hearth.ember.data.E2eeStatus.Ready -> "This phone can read encrypted chats (key ${app.e2ee.latestKeyId})"
                    org.hearth.ember.data.E2eeStatus.Waiting -> "Waiting to be let in from another device. Tap to check again."
                    org.hearth.ember.data.E2eeStatus.None -> "Not set up yet. Tap to set up on this phone."
                    else -> "Checking…"
                },
            ) { scope.launch { runCatching { app.e2ee.init(createIfMissing = e2ee == org.hearth.ember.data.E2eeStatus.None) } } }
}

@Composable
internal fun ToggleRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun ActionRow(icon: ImageVector, title: String, subtitle: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Your name, your picture and the line under your name that your contacts see. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.ember
    val me by app.repo.me.collectAsState()
    val scope = rememberCoroutineScope()
    var name by remember(me?.id) { mutableStateOf(me?.displayName ?: "") }
    var about by remember(me?.id) { mutableStateOf(me?.statusMessage ?: "") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
                app.repo.updateAvatar(bytes, context.contentResolver.getType(uri) ?: "image/jpeg")
            }.onFailure { error = it.message }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.clickable { pick.launch("image/*") }) {
                UserAvatar(me, 110.dp)
                Box(
                    Modifier.align(Alignment.BottomEnd).size(36.dp)
                        .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.CameraAlt, "Change picture", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp)) }
            }
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = about, onValueChange = { about = it }, label = { Text("About") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("@${me?.username ?: ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.Button(
                onClick = {
                    saving = true
                    scope.launch {
                        runCatching { app.repo.updateProfile(name.trim().ifBlank { null }, about.trim()) }
                            .onSuccess { nav.popBackStack() }
                            .onFailure { error = it.message }
                        saving = false
                    }
                },
                enabled = !saving && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("Save") }
        }
    }
}
