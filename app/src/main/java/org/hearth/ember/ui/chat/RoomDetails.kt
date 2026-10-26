package org.hearth.ember.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import org.hearth.ember.data.Room
import org.hearth.ember.ember
import org.hearth.ember.ui.components.RoomAvatar
import org.hearth.ember.ui.components.UserAvatar

/** Who is in a chat and what it is called. Groups can be renamed and left; a direct chat just shows the other person. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomDetailsScreen(nav: NavHostController, roomId: String) {
    val app = LocalContext.current.ember
    val rooms by app.repo.rooms.collectAsState()
    val me by app.repo.me.collectAsState()
    val room: Room? = rooms.firstOrNull { it.id == roomId }
    val meId = me?.id ?: ""
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text(if (room?.isDm == true) "Contact" else "Group", fontWeight = FontWeight.SemiBold) },
            actions = { if (room != null && !room.isDm) IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, "Edit group") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        if (room == null) return@Column
        LazyColumn(Modifier.fillMaxSize().widthIn(max = 720.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    RoomAvatar(room, meId, 112.dp, showStatus = false)
                    Spacer(Modifier.height(14.dp))
                    Text(room.title(meId), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    val sub = if (room.isDm) room.other(meId)?.statusMessage.orEmpty() else room.topic
                    if (sub.isNotBlank()) Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    if (room.encrypted) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Lock, null, Modifier.padding(end = 6.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("End-to-end encrypted", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                HorizontalDivider()
                Text(
                    "${room.members.size} ${if (room.members.size == 1) "member" else "members"}",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            items(room.members, key = { it.id }) { member ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    UserAvatar(member, 44.dp, showStatus = true)
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(
                            member.displayName.ifBlank { member.username } + if (member.id == meId) " (you)" else "",
                            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                        )
                        val line = member.statusMessage.ifBlank { if (member.isOnline) "online" else "" }
                        if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!room.isDm) item {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Text(
                    "Leave group", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { leaving = true }.padding(horizontal = 20.dp, vertical = 16.dp),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (editing && room != null) {
        var name by remember { mutableStateOf(room.name) }
        var topic by remember { mutableStateOf(room.topic) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Edit group") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
                    OutlinedTextField(topic, { topic = it }, label = { Text("Topic") }, maxLines = 3)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    editing = false
                    scope.launch { runCatching { app.api.renameRoom(roomId, name.trim(), topic.trim()); app.repo.refreshAll() }.onFailure { error = it.message } }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
    if (leaving) AlertDialog(
        onDismissRequest = { leaving = false },
        title = { Text("Leave this group?") },
        text = { Text("You will stop receiving its messages. Someone has to add you again to come back.") },
        confirmButton = {
            TextButton(onClick = {
                leaving = false
                scope.launch {
                    runCatching { app.api.leaveRoom(roomId); app.repo.refreshAll() }
                        .onSuccess { nav.popBackStack("home", false) }
                        .onFailure { error = it.message }
                }
            }) { Text("Leave", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { leaving = false }) { Text("Cancel") } },
    )
}
