package org.hearth.ember.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import org.hearth.ember.data.User
import org.hearth.ember.ember
import org.hearth.ember.ui.components.UserAvatar

/** The People tab: requests waiting for you, then everyone you know, with a way to add someone by username. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsTab(nav: NavHostController) {
    val app = LocalContext.current.ember
    val contacts by app.repo.contacts.collectAsState()
    val users by app.repo.users.collectAsState()
    val me by app.repo.me.collectAsState()
    val scope = rememberCoroutineScope()
    val openChat = org.hearth.ember.ui.home.LocalOpenChat.current
    var adding by remember { mutableStateOf(false) }
    val people = remember(users, contacts, me) {
        users.values.filter { it.id != me?.id }.sortedBy { it.displayName.lowercase() }
    }
    var busy by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("People", fontWeight = FontWeight.Bold) },
            actions = { IconButton(onClick = { adding = true }) { Icon(Icons.Filled.PersonAdd, "Add a contact") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            if (contacts.incoming.isNotEmpty()) {
                item { SectionLabel("Wants to add you") }
                items(contacts.incoming, key = { "in-" + it.id }) { u ->
                    PersonRow(u, subtitle = "@${u.username}") {
                        FilledTonalButton(onClick = { scope.launch { runCatching { app.repo.acceptContact(u.id) } } }) { Text("Accept") }
                        IconButton(onClick = { scope.launch { runCatching { app.repo.removeContact(u.id) } } }) { Icon(Icons.Filled.Close, "Decline") }
                    }
                }
            }
            if (contacts.outgoing.isNotEmpty()) {
                item { SectionLabel("Waiting for an answer") }
                items(contacts.outgoing, key = { "out-" + it.id }) { u ->
                    PersonRow(u, subtitle = "@${u.username}") {
                        TextButton(onClick = { scope.launch { runCatching { app.repo.removeContact(u.id) } } }) { Text("Cancel") }
                    }
                }
            }
            item { SectionLabel(if (people.isEmpty()) "No one here yet" else "Everyone you know (${people.size})") }
            if (people.isEmpty()) item {
                Text(
                    "Add someone by their username and they will see your request. You only see people you have added or share a chat with.",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(people, key = { it.id }) { u ->
                PersonRow(
                    u,
                    subtitle = u.statusMessage.ifBlank { if (u.isOnline) "Online" else "@${u.username}" },
                    modifier = Modifier.clickable(enabled = busy == null) {
                        busy = u.id
                        scope.launch {
                            runCatching { app.repo.openDm(u.id) }.onSuccess { openChat(it.id) }
                            busy = null
                        }
                    },
                )
            }
        }
    }
    if (adding) AddContactDialog(onDismiss = { adding = false })
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
fun PersonRow(user: User, subtitle: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        UserAvatar(user, 46.dp, showStatus = true)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(user.displayName.ifBlank { user.username }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        trailing?.invoke()
    }
}

@Composable
private fun AddContactDialog(onDismiss: () -> Unit) {
    val app = LocalContext.current.ember
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a contact") },
        text = {
            Column {
                Text("Type their username. They will see your request and can accept it.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Username") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (note != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(note!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching { app.repo.addContact(name.trim().removePrefix("@")) }
                            // The same words whether or not that username exists: the screen must not reveal who is on the server.
                            .onSuccess { note = "Request sent. If they are here, they will see it."; name = "" }
                            .onFailure { note = it.message }
                        busy = false
                    }
                },
            ) { Text("Send request") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Start a one-to-one chat with someone you know. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(nav: NavHostController) {
    val app = LocalContext.current.ember
    val users by app.repo.users.collectAsState()
    val me by app.repo.me.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val people = remember(users, me) { users.values.filter { it.id != me?.id }.sortedBy { it.displayName.lowercase() } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New chat") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { nav.navigate("newgroup") }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Group, null, tint = MaterialTheme.colorScheme.onPrimary) }
                    Spacer(Modifier.width(14.dp))
                    Text("New group", style = MaterialTheme.typography.titleMedium)
                }
            }
            item { SectionLabel("People") }
            if (people.isEmpty()) item {
                Text(
                    "No one to chat with yet. Add someone from the People tab.",
                    modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(people, key = { it.id }) { u ->
                PersonRow(u, subtitle = u.statusMessage.ifBlank { if (u.isOnline) "Online" else "@${u.username}" }, modifier = Modifier.clickable(enabled = !busy) {
                    busy = true
                    scope.launch {
                        runCatching { app.repo.openDm(u.id) }.onSuccess { room ->
                            nav.popBackStack()
                            nav.navigate("chat/${room.id}")
                        }
                        busy = false
                    }
                })
            }
        }
    }
}

/** Pick people and a name for a group chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGroupScreen(nav: NavHostController) {
    val app = LocalContext.current.ember
    val users by app.repo.users.collectAsState()
    val me by app.repo.me.collectAsState()
    val scope = rememberCoroutineScope()
    val chosen = remember { mutableStateListOf<String>() }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val people = remember(users, me) { users.values.filter { it.id != me?.id }.sortedBy { it.displayName.lowercase() } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New group") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            if (name.isNotBlank() && chosen.isNotEmpty()) FloatingActionButton(
                onClick = {
                    if (busy) return@FloatingActionButton
                    busy = true
                    scope.launch {
                        runCatching { app.repo.createGroup(name.trim(), chosen.toList()) }
                            .onSuccess { room -> nav.popBackStack(); nav.navigate("chat/${room.id}") }
                            .onFailure { error = it.message }
                        busy = false
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
            ) { Icon(Icons.Filled.Check, "Create group") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text("Group name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            SectionLabel("Add people (${chosen.size})")
            LazyColumn(Modifier.fillMaxSize()) {
                items(people, key = { it.id }) { u ->
                    PersonRow(
                        u, subtitle = "@${u.username}",
                        modifier = Modifier.clickable { if (u.id in chosen) chosen.remove(u.id) else chosen.add(u.id) },
                        trailing = { Checkbox(checked = u.id in chosen, onCheckedChange = { if (it) chosen.add(u.id) else chosen.remove(u.id) }) },
                    )
                }
            }
        }
    }
}
