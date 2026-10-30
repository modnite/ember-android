package org.hearth.ember.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hearth.ember.data.BackupException
import org.hearth.ember.data.BackupNames
import org.hearth.ember.data.Destination
import org.hearth.ember.data.RemoteEntry
import org.hearth.ember.ember

/**
 * The first time someone signs in on a fresh install: if this account already has a backup on this server, offer to bring it back at once
 * (the encryption key and settings, so encrypted chats open); otherwise, or if they would rather use another place, let them pick where the
 * backup is: this server, a file, a folder or cloud drive on the phone, WebDAV or Nextcloud, S3, FTP or SFTP. Asked once per install. The
 * password just typed opens a backup sealed under it; progress is in the strip across the app while it runs.
 */
@Composable
fun FirstLoginRestore(password: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.ember
    var checking by remember { mutableStateOf(true) }
    var found by remember { mutableStateOf<Pair<Destination, RemoteEntry>?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf<String?>(null) }
    var askPassphrase by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var passphrase by remember { mutableStateOf("") }
    val serverPlace = remember { Destination("server", Destination.KIND_SERVER, app.store.server.removePrefix("https://").removePrefix("http://"), app.store.server) }

    fun say(text: String) = Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
    fun finish() { app.store.restoreOffered = true; app.backup.firstLoginOffer.value = null; onClose() }

    /** Restore the newest backup in [dest] (or [entry]); a backup from before accounts had passwords asks for its passphrase. */
    fun restore(dest: Destination, entry: RemoteEntry? = null, pass: String? = null) {
        app.scope.launch {
            runCatching {
                val e = entry ?: app.backup.backupsIn(dest).firstOrNull() ?: throw BackupException("No Ember backup found in ${dest.label}.")
                app.backup.restoreFrom(dest, e.name, pass, password)
            }.onSuccess { say(it); finish() }.onFailure { e ->
                if (e is BackupException && e.needsPassphrase) askPassphrase = { p -> restore(dest, entry, p) } else say(e.message ?: "Something went wrong.")
            }
        }
    }

    LaunchedEffect(Unit) {
        found = runCatching {
            withContext(Dispatchers.IO) { app.backup.backupsIn(serverPlace).firstOrNull()?.let { serverPlace to it } }
        }.getOrNull()
        checking = false
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        app.scope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) { app.backup.readUri(uri) }
                app.backup.apply(bytes, null, password)
            }.onSuccess { say(it); finish() }.onFailure { e -> say(e.message ?: "Could not read that file.") }
        }
    }
    val openFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val name = DocumentFile.fromTreeUri(context, uri)?.name ?: "Folder"
        restore(Destination(UUID.randomUUID().toString().take(8), Destination.KIND_FOLDER, name, uri.toString()))
    }

    form?.let { kind ->
        RemotePlaceForm(kind, onCancel = { form = null }) { dest -> form = null; restore(dest) }
        return
    }
    askPassphrase?.let { go ->
        AlertDialog(
            onDismissRequest = { askPassphrase = null },
            title = { Text("An older backup, sealed with a passphrase") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { OutlinedTextField(passphrase, { passphrase = it }, singleLine = true, label = { Text("Passphrase") }, visualTransformation = PasswordVisualTransformation()); ActivityLine() } },
            confirmButton = { TextButton(enabled = passphrase.isNotEmpty(), onClick = { val p = passphrase; passphrase = ""; askPassphrase = null; go(p) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { askPassphrase = null }) { Text("Cancel") } },
        )
        return
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Restore from") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    found?.let { (d, e) -> Source("Your backup on this server", "Made ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.modified))}") { choosing = false; restore(d, e) } }
                    Source("A file", "Open a backup you saved") { openFile.launch(arrayOf("*/*")) }
                    Source("A folder or cloud drive", "On this phone, an SD card, or Google Drive, OneDrive, Proton Drive and Dropbox through their apps") { openFolder.launch(null) }
                    Source("WebDAV or Nextcloud", "The newest backup on your server") { form = Destination.KIND_DAV }
                    for (k in REMOTE_KINDS) Source(k.title, k.subtitle) { form = k.kind }
                    ActivityLine()
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Back") } },
        )
        return
    }

    // The text and the buttons share one scrolling body: at a big text size or sideways, no button is ever cut off below the dialog.
    AlertDialog(
        onDismissRequest = { finish() },
        title = { Text(if (found != null) "Restore your backup?" else "Restore a backup?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    checking -> Text("Looking for a backup of this account on this server…", style = MaterialTheme.typography.bodyMedium)
                    found != null -> Text(
                        "This account has a backup on this server from ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(found!!.second.modified))}. Restoring brings back your encryption key and settings, so your encrypted chats open.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> Text("No backup of this account was found on this server. If you have one somewhere else, you can restore it from a file, a folder, a cloud drive or your own server. Your encryption key and settings come back, so encrypted chats open.", style = MaterialTheme.typography.bodyMedium)
                }
                ActivityLine()
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
                if (found != null) {
                    TextButton(onClick = { restore(found!!.first, found!!.second) }, modifier = Modifier.fillMaxWidth()) { Text("Restore") }
                    TextButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth()) { Text("Another source") }
                } else TextButton(enabled = !checking, onClick = { choosing = true }, modifier = Modifier.fillMaxWidth()) { Text("Choose a source") }
                TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun Source(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
