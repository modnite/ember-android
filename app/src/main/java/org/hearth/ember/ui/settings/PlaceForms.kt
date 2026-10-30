package org.hearth.ember.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hearth.ember.data.Destination
import org.hearth.ember.ember

/** The kinds of place that need a form, with the words that go with them. */
internal data class RemoteKind(val kind: String, val title: String, val subtitle: String)

internal val REMOTE_KINDS = listOf(
    RemoteKind(Destination.KIND_S3, "S3-compatible storage", "MinIO, Backblaze B2, Wasabi, Cloudflare R2, Garage, Amazon S3"),
    RemoteKind(Destination.KIND_FTP, "FTP or FTPS", "Your own FTP server, with or without TLS"),
    RemoteKind(Destination.KIND_SFTP, "SFTP (SSH)", "Any machine you can sign in to over SSH"),
)

/**
 * The form for one kind of remote place. "Check and add" tries the place first (lists what is in it) and hands it back only if it answers, so a
 * wrong password or address is found now and not on the night of the first scheduled backup.
 */
@Composable
internal fun RemotePlaceForm(kind: String, onCancel: () -> Unit, onDone: (Destination) -> Unit) {
    val app = LocalContext.current.ember
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }
    var bucket by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var tls by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val info = (REMOTE_KINDS + RemoteKind(Destination.KIND_DAV, "WebDAV or Nextcloud", "Your own server")).first { it.kind == kind }
    val s3 = kind == Destination.KIND_S3

    fun build(): Destination {
        val h = host.trim()
        return when (kind) {
            Destination.KIND_S3 -> Destination(UUID.randomUUID().toString().take(8), kind, "${bucket.trim()} at ${h.removePrefix("https://").removePrefix("http://").substringBefore('/')}", h, user.trim(), pass, "${region.trim()}|${bucket.trim()}|${folder.trim()}")
            Destination.KIND_FTP -> Destination(UUID.randomUUID().toString().take(8), kind, "${h.substringBefore(':').ifBlank { "FTP" }} (${if (tls) "FTPS" else "FTP"})", "${if (tls) "ftps" else "ftp"}://$h/${folder.trim().trim('/')}", user.trim(), pass)
            Destination.KIND_DAV -> Destination(UUID.randomUUID().toString().take(8), kind, runCatching { android.net.Uri.parse(h).host }.getOrNull() ?: "WebDAV", h, user.trim(), pass)
            else -> Destination(UUID.randomUUID().toString().take(8), kind, "${h.substringBefore(':').ifBlank { "SFTP" }} (SFTP)", "sftp://$h/${folder.trim().trim('/')}", user.trim(), pass)
        }
    }

    val ready = !busy && host.isNotBlank() && (!s3 || bucket.isNotBlank())
    fun check() {
        val d = build()
        busy = true; problem = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { app.backup.target(d).list() } }
                .onSuccess { onDone(d) }
                .onFailure { problem = it.message ?: "Could not reach it." }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(info.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it }, singleLine = true, label = { Text(if (s3) "Address" else if (kind == Destination.KIND_DAV) "Folder address" else "Server") }, placeholder = { Text(if (s3) "https://s3.example.com" else if (kind == Destination.KIND_DAV) "https://cloud.example.com/remote.php/dav/files/you/Backups" else "server.example.com or server.example.com:2222") }, modifier = Modifier.fillMaxWidth())
                if (s3) {
                    OutlinedTextField(region, { region = it }, singleLine = true, label = { Text("Region (optional)") }, placeholder = { Text("us-east-1") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(bucket, { bucket = it }, singleLine = true, label = { Text("Bucket") }, modifier = Modifier.fillMaxWidth())
                }
                if (kind != Destination.KIND_DAV) OutlinedTextField(folder, { folder = it }, singleLine = true, label = { Text(if (s3) "Folder in the bucket (optional)" else "Folder (optional)") }, placeholder = { Text("Backups/Ember") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, singleLine = true, label = { Text(if (s3) "Access key" else "Username") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text(if (s3) "Secret key" else "Password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (ready) check() }), modifier = Modifier.fillMaxWidth())
                if (kind == Destination.KIND_FTP) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Encrypt the connection (FTPS)")
                        Switch(tls, { tls = it })
                    }
                }
                if (busy) Text("Checking…", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                problem?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = { check() }) { Text("Check and add") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onCancel) { Text("Cancel") } },
    )
}
