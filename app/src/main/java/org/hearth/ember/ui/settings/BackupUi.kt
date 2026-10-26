package org.hearth.ember.ui.settings

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Restore
import kotlinx.coroutines.launch
import org.hearth.ember.data.BackupException
import org.hearth.ember.data.BackupFormat
import org.hearth.ember.ember

/** What the person is in the middle of: choosing how to back up, or entering a passphrase for a file being restored. */
private sealed interface Step {
    data object ChooseDestination : Step
    data object ChooseSource : Step
    data object ToFile : Step
    data object ToDav : Step
    class Passphrase(val file: ByteArray) : Step
}

/**
 * Backup and restore. A backup holds the encryption key and the settings (messages live on the server). It can be saved as a file anywhere
 * Android can reach, or sent to a WebDAV or Nextcloud server, and it can be encrypted with a passphrase or left plain, as you choose.
 */
@Composable
fun BackupRows() {
    val context = LocalContext.current
    val app = context.ember
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<Step?>(null) }
    var encrypt by remember { mutableStateOf(true) }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(app.store.webdavUrl) }
    var user by remember { mutableStateOf(app.store.webdavUser) }
    var davPass by remember { mutableStateOf(app.store.webdavPass) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    fun problem(e: Throwable) = say(e.message ?: "Something went wrong.")

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val bytes = pending; pending = null
        if (uri != null && bytes != null) scope.launch { runCatching { app.backup.writeUri(uri, bytes) }.onSuccess { say("Backup saved.") }.onFailure { problem(it) } }
    }

    /** Restore from [file]; an encrypted one asks for its passphrase first. */
    fun restore(file: ByteArray, passphrase: String?) {
        scope.launch {
            runCatching { app.backup.apply(file, passphrase) }.onSuccess { say(it); step = null; pass = "" }.onFailure { e ->
                if (e is BackupException && e.needsPassphrase) step = Step.Passphrase(file).also { if (!passphrase.isNullOrEmpty()) say(e.message ?: "") } else problem(e)
            }
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch { runCatching { app.backup.readUri(uri) }.onSuccess { restore(it, null) }.onFailure { problem(it) } }
    }

    ActionRow(Icons.Filled.Backup, "Back up", "Save your encryption key and settings") { step = Step.ChooseDestination }
    ActionRow(Icons.Filled.Restore, "Restore", "Put a saved backup back on this phone") { step = Step.ChooseSource }
    if (busy) Text("Working…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 56.dp))

    val current = step
    if (current is Step.ChooseDestination) AlertDialog(
        onDismissRequest = { step = null },
        title = { Text("Back up to") },
        text = {
            Column {
                Choice("A file", "On this phone, an SD card or a network share") { encrypt = true; step = Step.ToFile }
                Choice("WebDAV or Nextcloud", "Your own server") { encrypt = true; step = Step.ToDav }
            }
        },
        confirmButton = { TextButton(onClick = { step = null }) { Text("Cancel") } },
    )
    if (current is Step.ChooseSource) AlertDialog(
        onDismissRequest = { step = null },
        title = { Text("Restore from") },
        text = {
            Column {
                Choice("A file", "Open a backup you saved") { step = null; openFile.launch(arrayOf("*/*")) }
                Choice("WebDAV or Nextcloud", "The newest backup on your server") {
                    step = null
                    if (app.store.webdavUrl.isBlank()) { encrypt = true; step = Step.ToDav; say("Enter your server first.") }
                    else scope.launch { busy = true; runCatching { app.backup.downloadLatestDav() }.onSuccess { restore(it, null) }.onFailure { problem(it) }; busy = false }
                }
            }
        },
        confirmButton = { TextButton(onClick = { step = null }) { Text("Cancel") } },
    )
    if (current is Step.ToFile || current is Step.ToDav) {
        val toDav = current is Step.ToDav
        val mismatch = encrypt && (pass.isEmpty() || pass != pass2)
        AlertDialog(
            onDismissRequest = { step = null },
            title = { Text(if (toDav) "Back up to WebDAV or Nextcloud" else "Back up to a file") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (toDav) {
                        OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("Folder address") }, placeholder = { Text("https://cloud.example.com/remote.php/dav/files/you/Backups") })
                        OutlinedTextField(user, { user = it }, singleLine = true, label = { Text("Username") })
                        OutlinedTextField(davPass, { davPass = it }, singleLine = true, label = { Text("Password or app password") }, visualTransformation = PasswordVisualTransformation())
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Encrypt with a passphrase", Modifier.weight(1f))
                        Switch(encrypt, { encrypt = it })
                    }
                    if (encrypt) {
                        OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text("Passphrase") }, visualTransformation = PasswordVisualTransformation())
                        OutlinedTextField(pass2, { pass2 = it }, singleLine = true, label = { Text("Passphrase again") }, visualTransformation = PasswordVisualTransformation())
                        Text("Keep it safe: without it an encrypted backup cannot be opened.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else Text("A plain backup can be read by anyone who gets the file, and it contains your encryption key.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !mismatch && (!toDav || url.isNotBlank()), onClick = {
                    val bytes = app.backup.make(if (encrypt) pass else null)
                    step = null; pass = ""; pass2 = ""
                    if (toDav) {
                        app.store.webdavUrl = url; app.store.webdavUser = user; app.store.webdavPass = davPass
                        scope.launch { busy = true; runCatching { app.backup.uploadDav(bytes) }.onSuccess { say("Backed up as $it.") }.onFailure { problem(it) }; busy = false }
                    } else { pending = bytes; createFile.launch(app.backup.fileName()) }
                }) { Text("Back up") }
            },
            dismissButton = { TextButton(onClick = { step = null }) { Text("Cancel") } },
        )
    }
    if (current is Step.Passphrase) AlertDialog(
        onDismissRequest = { step = null; pass = "" },
        title = { Text("This backup is encrypted") },
        text = { OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text("Passphrase") }, visualTransformation = PasswordVisualTransformation()) },
        confirmButton = { TextButton(enabled = pass.isNotEmpty(), onClick = { restore(current.file, pass) }) { Text("Restore") } },
        dismissButton = { TextButton(onClick = { step = null; pass = "" }) { Text("Cancel") } },
    )
}

@Composable
private fun Choice(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
