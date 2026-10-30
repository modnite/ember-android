package org.hearth.ember.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import org.hearth.ember.data.Message
import org.hearth.ember.data.Room
import org.hearth.ember.ember
import org.hearth.ember.ui.components.RoomAvatar
import org.hearth.ember.ui.contacts.ContactsTab
import org.hearth.ember.ui.listTime
import org.hearth.ember.ui.settings.SettingsTab
import org.hearth.ember.ui.theme.EmberTheme

private enum class Tab(val label: String, val icon: ImageVector, val selected: ImageVector) {
    Chats("Chats", Icons.Outlined.Chat, Icons.Filled.Chat),
    Contacts("People", Icons.Outlined.People, Icons.Filled.People),
    Settings("Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
}

/** Opens a chat: a new screen on a phone held upright, the pane beside the list on a wide screen. */
val LocalOpenChat = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Wide enough to keep the list and the open chat side by side: a phone on its side, a tablet, a foldable. */
private val WideBreakpoint = 1000.dp

@Composable
fun HomeScreen(nav: NavHostController) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= WideBreakpoint) WideHome(nav) else NarrowHome(nav)
    }
}

/** Landscape and tablets: a rail of tabs, the list, and the chat open beside it, so nothing has to be left to read a reply. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WideHome(nav: NavHostController) {
    val app = LocalContext.current.ember
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val rooms by app.repo.rooms.collectAsState()
    val contacts by app.repo.contacts.collectAsState()
    val selected by app.selectedRoom.collectAsState()
    val connected by app.repo.connected.collectAsState()
    val unread = rooms.filter { !it.archived }.sumOf { it.unread }
    val waiting = contacts.incoming.size
    CompositionLocalProvider(LocalOpenChat provides { id -> app.selectedRoom.value = id }) {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxHeight().width(76.dp)) {
                Spacer(Modifier.weight(1f))
                Tab.entries.forEachIndexed { i, t ->
                    val count = when (t) { Tab.Chats -> unread; Tab.Contacts -> waiting; else -> 0 }
                    NavigationRailItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { BadgedBox(badge = { if (count > 0) Badge { Text(if (count > 99) "99+" else "$count") } }) { Icon(if (tab == i) t.selected else t.icon, t.label) } },
                        label = { Text(t.label) },
                        alwaysShowLabel = false,
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            Scaffold(
                modifier = Modifier.width(360.dp).fillMaxHeight(),
                containerColor = MaterialTheme.colorScheme.background,
                // The tab's own top bar already clears the status bar; counting it here too left a gap above the list.
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                floatingActionButton = {
                    if (tab == 0) FloatingActionButton(
                        onClick = { nav.navigate("newchat") },
                        containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) { Icon(Icons.Filled.Add, "New chat") }
                },
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when (tab) {
                        0 -> ChatsTab(nav, connected)
                        1 -> ContactsTab(nav)
                        else -> SettingsTab(nav)
                    }
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val id = selected
                if (id != null && rooms.any { it.id == id }) key(id) { org.hearth.ember.ui.chat.ChatScreen(nav, id, embedded = true) }
                else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Outlined.Chat, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("Pick a chat to start", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NarrowHome(nav: NavHostController) {
    val app = LocalContext.current.ember
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val rooms by app.repo.rooms.collectAsState()
    val contacts by app.repo.contacts.collectAsState()
    val unread = rooms.filter { !it.archived }.sumOf { it.unread }
    val waiting = contacts.incoming.size
    val connected by app.repo.connected.collectAsState()

    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // The bars below add their own top inset; the content box only needs the sides and bottom.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        bottomBar = {
            NavigationBar(modifier = if (landscape) Modifier.height(56.dp) else Modifier, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEachIndexed { i, t ->
                    val count = when (t) { Tab.Chats -> unread; Tab.Contacts -> waiting; else -> 0 }
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            BadgedBox(badge = { if (count > 0) Badge { Text(if (count > 99) "99+" else "$count") } }) {
                                Icon(if (tab == i) t.selected else t.icon, t.label)
                            }
                        },
                        label = if (landscape) null else ({ Text(t.label) }),
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == 0) FloatingActionButton(
                onClick = { nav.navigate("newchat") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Filled.Add, "New chat") }
        },
    ) { padding ->
        CompositionLocalProvider(LocalOpenChat provides { id -> nav.navigate("chat/$id") }) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    0 -> ChatsTab(nav, connected)
                    1 -> ContactsTab(nav)
                    else -> SettingsTab(nav)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsTab(nav: NavHostController, connected: Boolean) {
    val app = LocalContext.current.ember
    val rooms by app.repo.rooms.collectAsState()
    val me by app.repo.me.collectAsState()
    val loaded by app.repo.loaded.collectAsState()
    val typing by app.repo.typing.collectAsState()
    val calls by app.repo.calls.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val openChat = LocalOpenChat.current
    val meId = me?.id ?: ""
    val matching = remember(rooms, query) {
        if (query.isBlank()) rooms else rooms.filter { r ->
            r.title(meId).contains(query, true) || (r.lastMessage?.body?.contains(query, true) == true)
        }
    }
    // Archived chats leave the main list; one row at the bottom opens them and carries their unread count.
    val shown = remember(matching) { matching.filter { !it.archived } }
    val archivedRooms = remember(matching) { matching.filter { it.archived } }
    val archivedUnread = archivedRooms.sumOf { it.unread }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val archiveOpen = showArchived || (query.isNotBlank() && archivedRooms.isNotEmpty())
    var menuRoom by remember { mutableStateOf<Room?>(null) }

    // Messages that match, found on the server (plain chats) and in what this phone already holds (encrypted chats cannot be searched there).
    var hits by remember { mutableStateOf<List<Pair<Room, Message>>>(emptyList()) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 3) { hits = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(350)
        val found = LinkedHashMap<String, Pair<Room, Message>>()
        for (room in rooms) for (m in app.repo.messagesFlow(room.id).value) {
            val text = app.repo.textOf(room.id, m.body)
            if (text != null && text.contains(q, true)) found[m.id] = room to m
        }
        runCatching { app.api.search(q) }.getOrNull()?.forEach { r ->
            val room = rooms.firstOrNull { it.id == (r.room?.id ?: r.message.roomId) } ?: return@forEach
            found.putIfAbsent(r.message.id, room to r.message)
        }
        hits = found.values.sortedByDescending { it.second.createdAt }.take(40)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (searching) {
                    TextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Search chats") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Column {
                        Text("Ember", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        if (!connected) Text("Connecting…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            actions = {
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, if (searching) "Close search" else "Search")
                }
                if (!searching) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Group, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("New group") }, onClick = { menu = false; nav.navigate("newgroup") })
                            DropdownMenuItem(text = { Text("New chat") }, onClick = { menu = false; nav.navigate("newchat") })
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        when {
            !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
            shown.isEmpty() && archivedRooms.isEmpty() && hits.isEmpty() -> EmptyChats(query.isNotBlank()) { nav.navigate("newchat") }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (hits.isNotEmpty() && shown.isNotEmpty()) item("chats-label") { SectionLabel("Chats") }
                items(shown, key = { it.id }) { room ->
                    ChatRow(
                        room = room, meId = meId,
                        typingNames = typing[room.id].orEmpty().filter { it != meId }.map { app.repo.userName(it) },
                        inCall = calls[room.id] != null,
                        onClick = { openChat(room.id) },
                        onLongClick = { menuRoom = room },
                    )
                }
                if (archivedRooms.isNotEmpty()) {
                    item("archived-row") {
                        Row(
                            Modifier.fillMaxWidth().clickable { showArchived = !showArchived }.padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Archive, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(16.dp))
                            Text("Archived (${archivedRooms.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (archivedUnread > 0) Text("$archivedUnread", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (archiveOpen) items(archivedRooms, key = { "arch-" + it.id }) { room ->
                        ChatRow(
                            room = room, meId = meId,
                            typingNames = emptyList(),
                            inCall = false,
                            onClick = { openChat(room.id) },
                            onLongClick = { menuRoom = room },
                        )
                    }
                }
                if (hits.isNotEmpty()) {
                    item("messages-label") { SectionLabel("Messages") }
                    items(hits, key = { "hit-" + it.second.id }) { (room, message) ->
                        Column(Modifier.fillMaxWidth().clickable { openChat(room.id) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Row {
                                Text(room.title(meId), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listTime(message.createdAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                (app.repo.textOf(room.id, message.body) ?: message.body).lines().joinToString(" "),
                                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    menuRoom?.let { r ->
        val scope = rememberCoroutineScope()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { menuRoom = null },
            title = { Text(r.title(meId)) },
            text = { Text(if (r.archived) "Move this chat back to your chats." else "Archived chats leave your list and stay quiet. Their unread count is kept, and they stay archived when new messages arrive.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { menuRoom = null; scope.launch { runCatching { app.repo.archive(r.id, !r.archived) } } }) {
                    Text(if (r.archived) "Unarchive" else "Archive")
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { menuRoom = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun EmptyChats(searching: Boolean, onNew: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.Chat, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text(if (searching) "No chats match" else "No chats yet", style = MaterialTheme.typography.titleMedium)
        if (!searching) {
            Spacer(Modifier.height(6.dp))
            Text("Tap the + button to start one.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** One conversation in the list: picture, name, the last thing said, when, and how many are unread. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(room: Room, meId: String, typingNames: List<String>, inCall: Boolean, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    val app = LocalContext.current.ember
    val last = room.lastMessage
    val unread = room.unread
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoomAvatar(room, meId, 54.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    room.title(meId), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Medium,
                )
                if (last != null) Text(
                    listTime(last.createdAt), style = MaterialTheme.typography.labelMedium,
                    color = if (unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (inCall) {
                    Icon(Icons.Filled.Call, null, Modifier.size(16.dp).padding(end = 4.dp), tint = EmberTheme.colors.online)
                }
                val revision by app.e2ee.revision.collectAsState()
                val preview = remember(room, last, typingNames, revision) { preview(room, last, meId, typingNames, { a -> if (a.encrypted != 0) app.e2ee.mimeOf(a.envelope) ?: a.mime else a.mime }) { app.repo.textOf(room.id, it) } }
                Text(
                    preview.first, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (preview.second) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.defaultMinSize(minWidth = 22.dp, minHeight = 22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (unread > 99) "99+" else "$unread", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** The grey line under a chat's name. Second value: true when it should be highlighted (someone typing). */
private fun preview(room: Room, last: Message?, meId: String, typingNames: List<String>, mimeOf: (org.hearth.ember.data.Attachment) -> String = { it.mime }, textOf: (String) -> String? = { it }): Pair<String, Boolean> {
    if (typingNames.isNotEmpty()) {
        return (if (room.isDm) "typing…" else typingNames.first() + " is typing…") to true
    }
    if (last == null) return "No messages yet" to false
    val who = when {
        last.userId == meId -> "You: "
        !room.isDm -> (last.user?.displayName?.substringBefore(' ') ?: "") .let { if (it.isNotEmpty()) "$it: " else "" }
        else -> ""
    }
    val text = when {
        last.deletedAt != null -> "Message deleted"
        last.attachments.isNotEmpty() -> {
            val a = last.attachments.first()
            val mime = mimeOf(a)
            val kind = when {
                mime.startsWith("image/") -> "Photo"
                mime.startsWith("video/") -> "Video"
                mime.startsWith("audio/") -> "Voice message"
                else -> a.filename.ifBlank { "File" }
            }
            if (last.body.isNotBlank()) "$kind  ${last.body}" else kind
        }
        else -> textOf(last.body) ?: "Encrypted message"
    }
    return (who + text.replace('\n', ' ')) to false
}
