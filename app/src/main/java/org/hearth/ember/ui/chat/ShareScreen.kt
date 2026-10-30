package org.hearth.ember.ui.chat

import androidx.compose.foundation.layout.imePadding
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import org.hearth.ember.ember
import org.hearth.ember.ui.components.RoomAvatar

/** What another app handed to Ember to send: some words or a link, and/or files. */
data class SharePayload(val text: String?, val uris: List<Uri>)

/** "Send to…": pick the chat for what was shared from another app, send it there, and open that chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(nav: NavHostController) {
    val app = LocalContext.current.ember
    val payload by app.share.collectAsState()
    val rooms by app.repo.rooms.collectAsState()
    val me by app.repo.me.collectAsState()
    val meId = me?.id ?: ""
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    val shown = remember(rooms, query) { rooms.filter { query.isBlank() || it.title(meId).contains(query, true) } }

    fun close() { app.share.value = null; nav.popBackStack() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = { close() }) { Icon(Icons.Filled.Close, "Cancel") } },
            title = { Text("Send to…", fontWeight = FontWeight.SemiBold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        val p = payload
        if (p != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                if (!p.text.isNullOrBlank()) Text(p.text, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (p.uris.isNotEmpty()) Text(if (p.uris.size == 1) "1 file" else "${p.uris.size} files", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, placeholder = { org.hearth.ember.ui.components.PlaceholderText("Search chats") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        LazyColumn(Modifier.fillMaxSize().imePadding()) {
            items(shown, key = { it.id }) { room ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = p != null) {
                        val share = payload ?: return@clickable
                        scope.launch {
                            runCatching {
                                if (!share.text.isNullOrBlank()) app.repo.send(room.id, share.text)
                                for (uri in share.uris) app.repo.sendUri(room.id, uri)
                            }.onSuccess {
                                app.share.value = null
                                nav.popBackStack("home", false)
                                nav.navigate("chat/${room.id}") { launchSingleTop = true }
                            }.onFailure { failure = it.message ?: "Could not send." }
                        }
                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoomAvatar(room, meId, 44.dp, showStatus = false)
                    Spacer(Modifier.width(14.dp))
                    Text(room.title(meId), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
