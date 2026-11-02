package org.hearth.ember.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.hearth.ember.BuildConfig
import org.hearth.ember.data.ServerInfo
import org.hearth.ember.ember
import org.hearth.ember.ui.contacts.SectionLabel

/** Where the source of each part lives. */
private val REPOS = listOf(
    Triple("Ember for Android", "Source code of this app", "https://github.com/modnite/ember-android"),
    Triple("Ember for desktop", "Windows, Mac and Linux", "https://github.com/modnite/ember-desktop"),
    Triple("Hearth server", "The server Ember talks to", "https://github.com/modnite/hearth-server"),
)

/** About Ember: the app and server versions, the update check, and links to the source of every part. */
@Composable
fun AboutRows() {
    val context = LocalContext.current
    val app = context.ember
    var info by remember { mutableStateOf<ServerInfo?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(app.store.server) {
        runCatching { app.api.serverInfo() }.onSuccess { info = it; failed = false }.onFailure { failed = true }
    }
    SectionLabel("Versions")
    ActionRow(Icons.Filled.Info, "Ember for Android", "Version ${BuildConfig.VERSION_NAME}") {}
    val server = info
    val serverLine = when {
        server != null -> listOfNotNull(server.name?.takeIf { it.isNotBlank() }, server.version?.takeIf { it.isNotBlank() }?.let { "version $it" }).joinToString(", ").ifBlank { "Answers, no version given" }
        failed -> "Could not reach the server"
        else -> "Checking…"
    }
    ActionRow(Icons.Filled.Dns, app.store.server.removePrefix("https://"), serverLine) {}
    SectionLabel("Updates")
    UpdateRow()
    SectionLabel("Source code")
    for ((title, note, url) in REPOS) {
        ActionRow(Icons.Filled.Code, title, note) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
    Spacer(Modifier.height(8.dp))
}
