package org.hearth.ember.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.hearth.ember.BuildConfig
import org.hearth.ember.data.UpdateInfo
import org.hearth.ember.ember

/** The version line in Settings: it says whether Ember is up to date, checks on a tap, and offers the update when there is one. */
@Composable
fun UpdateRow() {
    val context = LocalContext.current
    val app = context.ember
    val scope = rememberCoroutineScope()
    val available by app.updater.available.collectAsState()
    var status by remember { mutableStateOf<String?>(null) }
    var show by remember { mutableStateOf(false) }

    val subtitle = status ?: available?.let { "Version ${it.version} is available. Tap to update." } ?: "You have Ember ${BuildConfig.VERSION_NAME}. Tap to look for a newer one."
    ActionRow(if (available != null) Icons.Filled.SystemUpdate else Icons.Filled.Info, if (available != null) "Update available" else "Check for updates", subtitle) {
        if (available != null) show = true
        else scope.launch {
            status = "Checking…"
            app.updater.check().onSuccess { status = if (it == null) "You have the latest version." else null; if (it != null) show = true }
                .onFailure { status = "Could not check: ${it.message ?: "no connection"}" }
        }
    }
    if (show) available?.let { UpdateDialog(it) { show = false } }
}

/** What an update offers: its notes, a download with progress, then Android's own installer. */
@Composable
fun UpdateDialog(info: UpdateInfo, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.ember
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Ember ${info.version} is available") },
        text = {
            Column {
                if (info.notes.isNotBlank()) Text(info.notes.take(600), style = MaterialTheme.typography.bodyMedium)
                if (info.size > 0) Text("Download: ${"%.0f".format(info.size / 1048576.0)} MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                if (busy) DownloadBar(progress, Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                // Android only installs apps from sources the person has allowed; the first time it has to be allowed for Ember.
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(context, "Allow Ember to install updates, then tap Update again.", Toast.LENGTH_LONG).show()
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return@TextButton
                }
                busy = true
                scope.launch {
                    runCatching { app.updater.download(info) { progress = it } }
                        .onSuccess { app.updater.install(it); onClose() }
                        .onFailure { Toast.makeText(context, it.message ?: "The download failed.", Toast.LENGTH_LONG).show(); busy = false }
                }
            }) { Text(if (busy) "Downloading…" else "Update") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Later") } },
    )
}

/** The download's progress: a plain rounded bar that fills from the left and ends where the download is, with nothing drawn past it (no end marker, no gap). */
@Composable
private fun DownloadBar(progress: Float, modifier: Modifier = Modifier) {
    val p = progress.coerceIn(0f, 1f)
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(MaterialTheme.colorScheme.primary))
        }
        Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}
