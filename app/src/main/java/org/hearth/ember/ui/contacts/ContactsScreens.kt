package org.hearth.ember.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Block
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

/**
 * The People tab: your friend code, requests waiting for you (accept, reject or block), everyone you know (press and hold to block), and the
 * people you blocked (unblock). Someone is found by exact username or friend code.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContactsTab(nav: NavHostController) {
    val app = LocalContext.current.ember
    val contacts by app.repo.contacts.collectAsState()
    val users by app.repo.users.collectAsState()
    val me by app.repo.me.collectAsState()
    val scope = rememberCoroutineScope()
    val openChat = org.hearth.ember.ui.home.LocalOpenChat.current
    var adding by remember { mutableStateOf(false) }
    val blocked by app.repo.blocked.collectAsState()
    var blocking by remember { mutableStateOf<User?>(null) }
    var code by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(contacts) {
        runCatching { app.repo.refreshBlocked() }
        if (code == null) runCatching { app.api.friendCode() }.onSuccess { code = it.display }
    }
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
            code?.let { c ->
                item {
                    Text(
                        "Your friend code: $c. Share it so people can add you without knowing your username.",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (contacts.incoming.isNotEmpty()) {
                item { SectionLabel("Friend requests (${contacts.incoming.size})") }
                items(contacts.incoming, key = { "in-" + it.id }) { u ->
                    PersonRow(u, subtitle = u.username) {
                        FilledTonalButton(onClick = { scope.launch { runCatching { app.repo.acceptContact(u.id) } } }) { Text("Accept") }
                        IconButton(onClick = { scope.launch { runCatching { app.repo.removeContact(u.id) } } }) { Icon(Icons.Filled.Close, "Reject") }
                        IconButton(onClick = { blocking = u }) { Icon(Icons.Filled.Block, "Block") }
                    }
                }
            }
            if (contacts.outgoing.isNotEmpty()) {
                item { SectionLabel("Waiting for an answer") }
                items(contacts.outgoing, key = { "out-" + it.id }) { u ->
                    PersonRow(u, subtitle = u.username) {
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
                    subtitle = u.statusMessage.ifBlank { if (u.isOnline) "Online" else u.username },
                    modifier = Modifier.combinedClickable(
                        enabled = busy == null,
                        onLongClick = { blocking = u },
                        onClick = {
                            busy = u.id
                            scope.launch {
                                runCatching { app.repo.openDm(u.id) }.onSuccess { openChat(it.id) }
                                busy = null
                            }
                        },
                    ),
                )
            }
            if (blocked.isNotEmpty()) {
                item { SectionLabel("Blocked (${blocked.size})") }
                items(blocked, key = { "blocked-" + it.id }) { u ->
                    PersonRow(u, subtitle = u.username) {
                        TextButton(onClick = { scope.launch { runCatching { app.repo.unblock(u.id) } } }) { Text("Unblock") }
                    }
                }
            }
        }
    }
    if (adding) AddContactDialog(onDismiss = { adding = false })
    blocking?.let { u ->
        AlertDialog(
            onDismissRequest = { blocking = null },
            title = { Text("Block ${u.displayName.ifBlank { u.username }}?") },
            text = { Text("They are removed from your contacts and cannot message you or send you requests. You can unblock them any time from the People tab.") },
            confirmButton = { TextButton(onClick = { blocking = null; scope.launch { runCatching { app.repo.block(u.id) } } }) { Text("Block") } },
            dismissButton = { TextButton(onClick = { blocking = null }) { Text("Cancel") } },
        )
    }
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
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<org.hearth.ember.data.LookupResult?>(null) }
    var searched by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    // Searching closes the keyboard so the result is not hidden behind it (in landscape it covers most of the dialog).
    val view = androidx.compose.ui.platform.LocalView.current
    fun closeKeyboard() {
        keyboard?.hide()
        // The dialog is its own window, so the keyboard is also asked to leave through that window.
        view.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
        focus.clearFocus()
    }
    fun find() {
        if (query.isBlank() || busy) return
        busy = true
        scope.launch {
            runCatching { app.api.lookup(query.trim()) }
                .onSuccess { found = it; searched = true; note = null }
                .onFailure { note = it.message }
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find someone") },
        text = {
            // Scrolls so that in landscape, with the keyboard up, the field and the result can still be reached.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // What the search found sits above the field, not below it: in landscape the keyboard covers everything under the field.
                val user = found?.user
                if (user != null) {
                    PersonRow(user, subtitle = user.username) {
                        when (found?.relation) {
                            "self" -> Text("That is you", style = MaterialTheme.typography.bodySmall)
                            "contact" -> Text("Already added", style = MaterialTheme.typography.bodySmall)
                            "outgoing" -> Text("Request sent", style = MaterialTheme.typography.bodySmall)
                            "incoming" -> FilledTonalButton(onClick = { scope.launch { runCatching { app.repo.acceptContact(user.id) }.onSuccess { note = "Added."; found = null } } }) { Text("Accept") }
                            else -> FilledTonalButton(onClick = {
                                scope.launch {
                                    runCatching { app.repo.addContact(user.username) }
                                        .onSuccess { note = "Request sent."; found = null; query = "" }
                                        .onFailure { note = it.message }
                                }
                            }) { Text("Add") }
                        }
                    }
                } else {
                    val message = note ?: if (searched) "No one found with that username or code." else "Type their exact username or friend code (like ABC-234)."
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = if (note != null || searched) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it; found = null; searched = false; note = null }, singleLine = true, label = { Text("Username or friend code") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { find(); closeKeyboard() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = query.isNotBlank() && !busy, onClick = { find(); closeKeyboard() }) { Text("Find") }
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
                PersonRow(u, subtitle = u.statusMessage.ifBlank { if (u.isOnline) "Online" else u.username }, modifier = Modifier.clickable(enabled = !busy) {
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
                        u, subtitle = u.username,
                        modifier = Modifier.clickable { if (u.id in chosen) chosen.remove(u.id) else chosen.add(u.id) },
                        trailing = { Checkbox(checked = u.id in chosen, onCheckedChange = { if (it) chosen.add(u.id) else chosen.remove(u.id) }) },
                    )
                }
            }
        }
    }
}
