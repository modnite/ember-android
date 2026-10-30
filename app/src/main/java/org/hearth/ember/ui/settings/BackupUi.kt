package org.hearth.ember.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule as ScheduleIcon
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hearth.ember.data.BackupException
import org.hearth.ember.data.BackupNames
import org.hearth.ember.data.BackupScheduler
import org.hearth.ember.data.DavTarget
import org.hearth.ember.data.Destination
import org.hearth.ember.data.RemoteEntry
import org.hearth.ember.data.Schedule
import org.hearth.ember.ember
import org.hearth.ember.ui.fileSize

/** The windows the backup page can have open. */
private sealed interface Dlg {
    data object AddDav : Dlg
    data object Timing : Dlg
    data object AddPlace : Dlg
    data object Included : Dlg
    data object RestoreSource : Dlg
    class Key(val text: String, val id: String) : Dlg
    class RestoreList(val dest: Destination, val entries: List<RemoteEntry>) : Dlg
    class AskPassphrase(val then: (String) -> Unit) : Dlg
    class AskPassword(val then: (String) -> Unit) : Dlg
}

private fun whenOf(e: RemoteEntry): String {
    val ms = if (e.modified > 0) e.modified else runCatching {
        SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).parse(e.name.removePrefix(BackupNames.CORE_PREFIX).take(13))?.time
    }.getOrNull() ?: 0L
    return if (ms > 0) SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(ms)) else e.name
}

private fun ago(ms: Long): String {
    val minutes = (System.currentTimeMillis() - ms) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

/** 12-hour time with AM or PM, minutes only when there are some ("3 AM", "9:30 PM"). */
private fun clock(minute: Int): String {
    val h = minute / 60
    val m = minute % 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    val ampm = if (h < 12) "AM" else "PM"
    return if (m == 0) "$h12 $ampm" else "%d:%02d %s".format(h12, m, ampm)
}

/**
 * Backup and restore. A backup holds your encryption key, your settings and your stickers (and, if you ask, a readable copy of your chats and
 * their files). Turning on automatic backups is one switch and one folder; every backup is sealed with a key that belongs to your account, so
 * there is no password to choose or to forget, and nobody else can open it. Restoring needs only that you are signed in.
 */
@Composable
fun BackupRows() {
    val context = LocalContext.current
    val app = context.ember
    val settings = app.backup.settings
    val revision by settings.revision.collectAsState()
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<Dlg?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var pass by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var davPass by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var turningOn by remember { mutableStateOf(false) }
    val busy = progress != null

    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    fun problem(e: Throwable) = say(e.message ?: "Something went wrong.")
    fun reschedule() = BackupScheduler.apply(context, settings)

    fun backUpNow() {
        scope.launch {
            progress = "Starting…"
            runCatching { app.backup.runNow { progress = it } }.onSuccess { results ->
                val failed = results.filter { !it.second.ok }
                say(if (failed.isEmpty()) "Backed up to ${results.size} ${if (results.size == 1) "place" else "places"}." else failed.joinToString("\n") { "${it.first.label}: ${it.second.message}" })
            }.onFailure { problem(it) }
            progress = null
        }
    }

    val addFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val wasTurningOn = turningOn
        turningOn = false
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val name = DocumentFile.fromTreeUri(context, uri)?.name ?: "Folder"
        settings.destinations = settings.destinations.filter { it.uri != uri.toString() } + Destination(UUID.randomUUID().toString().take(8), Destination.KIND_FOLDER, name, uri.toString())
        // The one switch: a folder, a daily backup at the usual hour, and a first backup right away so it is known to work.
        if (wasTurningOn) { settings.schedule = Schedule.Daily; reschedule(); backUpNow() }
    }

    /** Restore from a file's bytes; an old passphrase-sealed one asks for its passphrase. */
    fun restoreBytes(bytes: ByteArray, passphrase: String?, password: String? = null) {
        scope.launch {
            progress = "Restoring…"
            runCatching { app.backup.apply(bytes, passphrase, password) }.onSuccess { say(it) }.onFailure { e ->
                if (e is BackupException && e.needsPassword) dialog = Dlg.AskPassword { p -> restoreBytes(bytes, passphrase, p) }
                else if (e is BackupException && e.needsPassphrase) dialog = Dlg.AskPassphrase { p -> restoreBytes(bytes, p) } else problem(e)
            }
            progress = null
        }
    }
    fun restoreEntry(d: Destination, name: String, passphrase: String?, password: String? = null) {
        scope.launch {
            progress = "Restoring…"
            runCatching { app.backup.restoreFrom(d, name, passphrase, password) }.onSuccess { say(it) }.onFailure { e ->
                if (e is BackupException && e.needsPassword) dialog = Dlg.AskPassword { p -> restoreEntry(d, name, passphrase, p) }
                else if (e is BackupException && e.needsPassphrase) dialog = Dlg.AskPassphrase { p -> restoreEntry(d, name, p) } else problem(e)
            }
            progress = null
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch { runCatching { withContext(Dispatchers.IO) { app.backup.readUri(uri) } }.onSuccess { restoreBytes(it, settings.passphrase.ifEmpty { null }) }.onFailure { problem(it) } }
    }

    Section("Automatic")
    val auto = settings.schedule != Schedule.Off
    ToggleRow(
        Icons.Filled.Backup, "Back up automatically",
        if (!auto) "A folder and one switch. No password." else lastSummary(settings.destinations.size, settings.lastRuns.values.maxOfOrNull { it.at }, revision),
        auto,
    ) { on ->
        when {
            !on -> { settings.schedule = Schedule.Off; reschedule() }
            settings.destinations.isEmpty() -> { turningOn = true; addFolder.launch(null) }
            else -> { settings.schedule = Schedule.Daily; reschedule() }
        }
    }
    if (auto) ActionRow(Icons.Filled.ScheduleIcon, "Schedule", "${settings.schedule.label} at ${clock(settings.atMinute)}" + if (settings.wifiOnly) ", Wi-Fi only" else "") { dialog = Dlg.Timing }
    ActionRow(Icons.Filled.Backup, "Back up now", if (settings.destinations.isEmpty()) "Add a place first" else lastSummary(settings.destinations.size, settings.lastRuns.values.maxOfOrNull { it.at }, revision)) {
        if (settings.destinations.isEmpty()) say("Add a place to keep backups first.") else if (!busy) backUpNow()
    }
    progress?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 56.dp, end = 20.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(start = 56.dp, end = 20.dp, top = 6.dp, bottom = 6.dp))
    }

    Section("Places and contents")
    for (d in settings.destinations) {
        val run = settings.lastRuns[d.id]
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (d.isFolder) Icons.Filled.Folder else Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(d.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    when { run == null -> "Nothing backed up here yet"; run.ok -> "${ago(run.at)}, ${fileSize(run.bytes)}"; else -> "Failed ${ago(run.at)}: ${run.message}" },
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, color = if (run != null && !run.ok) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { settings.destinations = settings.destinations.filter { it.id != d.id }; settings.lastRuns = settings.lastRuns - d.id }) { Icon(Icons.Filled.Delete, "Remove ${d.label}") }
        }
    }
    ActionRow(Icons.Filled.CreateNewFolder, "Add a place", "A folder on this phone or a drive, WebDAV or Nextcloud") { dialog = Dlg.AddPlace }
    ActionRow(
        Icons.Filled.History, "Included",
        "Key, settings, stickers" + (if (settings.includeHistory) ", chats" else "") + (if (settings.includeHistory && settings.includeMedia) " and files" else "") + ". Keeps ${settings.keep}.",
    ) { dialog = Dlg.Included }

    Section("Keys and restore")
    ActionRow(Icons.Filled.Key, "Backup key", "Seals every backup. Show it to read one on a computer.") {
        scope.launch { runCatching { app.backup.key() }.onSuccess { dialog = Dlg.Key(it.text, it.id) }.onFailure { problem(it) } }
    }
    EncryptionRows()
    ActionRow(Icons.Filled.Restore, "Restore", "From a place you back up to, or from a file") { dialog = Dlg.RestoreSource }

    when (val d = dialog) {
        Dlg.AddDav -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Add WebDAV or Nextcloud") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("Folder address") }, placeholder = { Text("https://cloud.example.com/remote.php/dav/files/you/Backups") })
                    OutlinedTextField(user, { user = it }, singleLine = true, label = { Text("Username") })
                    OutlinedTextField(davPass, { davPass = it }, singleLine = true, label = { Text("Password or app password") }, visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(enabled = url.isNotBlank(), onClick = {
                    val dest = Destination(UUID.randomUUID().toString().take(8), Destination.KIND_DAV, runCatching { Uri.parse(url.trim()).host }.getOrNull() ?: "WebDAV", url.trim(), user.trim(), davPass)
                    dialog = null
                    scope.launch {
                        progress = "Checking ${dest.label}…"
                        // It is only kept if the server answers with this sign-in.
                        runCatching { withContext(Dispatchers.IO) { DavTarget(app.client, dest).list() } }.onSuccess { settings.destinations = settings.destinations + dest; say("Added ${dest.label}.") }.onFailure { problem(it) }
                        progress = null
                    }
                }) { Text("Check and add") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        is Dlg.Key -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Your backup key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(d.text, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                    Text("Key ${d.id}. Anyone who has this key and one of your backup files can read it, so keep it to yourself. The Ember backup reader on a computer asks for it, or for your sign-in.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ember backup key", d.text))
                    say("Copied."); dialog = null
                }) { Text("Copy") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
        )
        Dlg.Timing -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Automatic backup") },
            text = {
                Column {
                    for (s in Schedule.entries.filter { it != Schedule.Off }) Row(Modifier.fillMaxWidth().clickable { settings.schedule = s; reschedule() }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = settings.schedule == s, onClick = { settings.schedule = s; reschedule() })
                        Text(s.label)
                    }
                    // The time of day: one row, an hour at a time (3 AM, 4 AM, ... 12 PM, 1 PM ...). No clock face, no minutes.
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Time", Modifier.weight(1f))
                        val hour = settings.atMinute / 60
                        TextButton(onClick = { settings.atMinute = ((hour + 23) % 24) * 60; reschedule() }) { Text("−") }
                        Text(clock(settings.atMinute), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.widthIn(min = 64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        TextButton(onClick = { settings.atMinute = ((hour + 1) % 24) * 60; reschedule() }) { Text("+") }
                    }
                    Text("Android may run it a little later when the phone is asleep.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    Row(Modifier.fillMaxWidth().clickable { settings.wifiOnly = !settings.wifiOnly; reschedule() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Only on Wi-Fi", Modifier.weight(1f))
                        androidx.compose.material3.Switch(settings.wifiOnly, { settings.wifiOnly = it; reschedule() })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Done") } },
        )
        Dlg.AddPlace -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Add a place") },
            text = {
                Column {
                    Choice("A folder", "On this phone, an SD card or USB drive, or a network share through its app") { dialog = null; turningOn = false; addFolder.launch(null) }
                    Choice("WebDAV or Nextcloud", "Your own server") { url = ""; user = ""; davPass = ""; label = ""; dialog = Dlg.AddDav }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        Dlg.Included -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("What a backup includes") },
            text = {
                Column {
                    Text("Always: your encryption key, settings and stickers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().clickable { settings.includeHistory = !settings.includeHistory }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Chat history"); Text("A readable copy of your conversations", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        androidx.compose.material3.Switch(settings.includeHistory, { settings.includeHistory = it })
                    }
                    Row(Modifier.fillMaxWidth().clickable { if (settings.includeHistory) settings.includeMedia = !settings.includeMedia }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Pictures, videos and files"); Text("Files over 64 MB are left out", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        androidx.compose.material3.Switch(settings.includeMedia && settings.includeHistory, { settings.includeMedia = it }, enabled = settings.includeHistory)
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Keep the last ${settings.keep}", Modifier.weight(1f))
                        TextButton(onClick = { settings.keep = settings.keep - 1 }, enabled = settings.keep > 1) { Text("−") }
                        TextButton(onClick = { settings.keep = settings.keep + 1 }, enabled = settings.keep < 30) { Text("+") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Done") } },
        )
        Dlg.RestoreSource -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Restore from") },
            text = {
                Column {
                    for (dest in settings.destinations) Choice(dest.label, if (dest.isFolder) "A folder" else "WebDAV or Nextcloud") {
                        dialog = null
                        scope.launch {
                            progress = "Looking in ${dest.label}…"
                            runCatching { app.backup.backupsIn(dest) }.onSuccess { list -> if (list.isEmpty()) say("No Ember backups in ${dest.label}.") else dialog = Dlg.RestoreList(dest, list) }.onFailure { problem(it) }
                            progress = null
                        }
                    }
                    Choice("A file", "Open a backup you saved") { dialog = null; openFile.launch(arrayOf("*/*")) }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        is Dlg.RestoreList -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Choose a backup") },
            text = {
                Column {
                    for (e in d.entries.take(10)) Choice(whenOf(e), if (e.size > 0) fileSize(e.size) else e.name) { dialog = null; restoreEntry(d.dest, e.name, settings.passphrase.ifEmpty { null }) }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        is Dlg.AskPassword -> AlertDialog(
            onDismissRequest = { dialog = null; pass = "" },
            title = { Text("Your account password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This backup is sealed under the password of the account that made it. It is only used to open the backup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = { TextButton(enabled = pass.isNotEmpty(), onClick = { val p = pass; pass = ""; dialog = null; d.then(p) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { dialog = null; pass = "" }) { Text("Cancel") } },
        )
        is Dlg.AskPassphrase -> AlertDialog(
            onDismissRequest = { dialog = null; pass = "" },
            title = { Text("An older backup, sealed with a passphrase") },
            text = { OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text("Passphrase") }, visualTransformation = PasswordVisualTransformation()) },
            confirmButton = { TextButton(enabled = pass.isNotEmpty(), onClick = { val p = pass; pass = ""; dialog = null; d.then(p) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { dialog = null; pass = "" }) { Text("Cancel") } },
        )
        null -> {}
    }
}

private fun lastSummary(places: Int, last: Long?, @Suppress("UNUSED_PARAMETER") revision: Int): String = when {
    places == 0 -> "Add a place to keep backups first"
    last == null -> "Not backed up yet"
    else -> "Last backup ${ago(last)}"
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp))
}

@Composable
private fun Choice(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Restoring on a new phone, from the sign-in screen: the server, username and password already typed there sign you in, and then a backup
 * file, or the newest backup on a WebDAV or Nextcloud server, puts the encryption key and the settings back, so the encrypted chats open at once.
 * It runs in the app's own scope, so it carries on when the sign-in screen goes away.
 */
@Composable
fun RestoreOnSignIn(server: String, username: String, password: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.ember
    var step by remember { mutableStateOf(0) } // 0 where from, 1 the server's details, 2 a passphrase (an older backup)
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var davPass by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var continueWith by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var busy by remember { mutableStateOf(false) }
    val ready = server.isNotBlank() && username.isNotBlank() && password.isNotEmpty()

    fun say(text: String) = Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()

    fun run(block: suspend () -> String, again: (String) -> Unit) {
        app.scope.launch {
            busy = true
            runCatching {
                if (!app.store.signedIn) app.repo.signIn(server, username, password)
                block()
            }.onSuccess { say(it); onClose() }.onFailure { e ->
                if (e is BackupException && e.needsPassphrase) { continueWith = again; step = 2 } else say(e.message ?: "Something went wrong.")
            }
            busy = false
        }
    }
    fun fromBytes(bytes: ByteArray, p: String?) { run({ app.backup.apply(bytes, p, password) }) { fromBytes(bytes, it) } }
    fun fromServer(d: Destination, p: String?) {
        run({
            val newest = app.backup.backupsIn(d).firstOrNull() ?: throw BackupException("No Ember backup found in that folder.")
            app.backup.restoreFrom(d, newest.name, p, password)
        }) { fromServer(d, it) }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) app.scope.launch { runCatching { withContext(Dispatchers.IO) { app.backup.readUri(uri) } }.onSuccess { fromBytes(it, null) }.onFailure { say(it.message ?: "Could not read that file.") } }
    }
    val dav by remember(url, user, davPass) { mutableStateOf(Destination("restore", Destination.KIND_DAV, "server", url.trim(), user.trim(), davPass)) }

    when (step) {
        0 -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Restore from a backup") },
            text = {
                Column {
                    Text(
                        if (ready) "This signs you in with the details you typed, then puts your encryption key and settings back, so your encrypted chats open."
                        else "Type your server, username and password on the sign-in screen first. Restoring signs you in with them, and the backup needs nothing else.",
                        style = MaterialTheme.typography.bodySmall, color = if (ready) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (ready) {
                        Choice("A file", "Open a backup you saved") { openFile.launch(arrayOf("*/*")) }
                        Choice("WebDAV or Nextcloud", "The newest backup on your server") { step = 1 }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        )
        1 -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Your backup server") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("Folder address") })
                    OutlinedTextField(user, { user = it }, singleLine = true, label = { Text("Username") })
                    OutlinedTextField(davPass, { davPass = it }, singleLine = true, label = { Text("Password or app password") }, visualTransformation = PasswordVisualTransformation())
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(enabled = url.isNotBlank() && !busy, onClick = { fromServer(dav, null) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        )
        else -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("An older backup, sealed with a passphrase") },
            text = { OutlinedTextField(passphrase, { passphrase = it }, singleLine = true, label = { Text("Passphrase") }, visualTransformation = PasswordVisualTransformation()) },
            confirmButton = { TextButton(enabled = passphrase.isNotEmpty(), onClick = { val p = passphrase; passphrase = ""; continueWith?.invoke(p) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        )
    }
}
