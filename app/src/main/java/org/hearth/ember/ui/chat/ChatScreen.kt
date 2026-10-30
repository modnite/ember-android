package org.hearth.ember.ui.chat

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.statusBarsPadding
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.hearth.ember.data.Attachment
import org.hearth.ember.data.Message
import org.hearth.ember.ember
import org.hearth.ember.data.MediaKind
import org.hearth.ember.ui.components.RoomAvatar
import org.hearth.ember.ui.dayLabel
import org.hearth.ember.ui.sameDay
import org.hearth.ember.ui.theme.EmberTheme
import java.io.File

private val QuickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(nav: NavHostController, roomId: String, embedded: Boolean = false) {
    val context = LocalContext.current
    val app = context.ember
    val scope = rememberCoroutineScope()
    val rooms by app.repo.rooms.collectAsState()
    val room = rooms.firstOrNull { it.id == roomId }
    val me by app.repo.me.collectAsState()
    val messages by app.repo.messagesFlow(roomId).collectAsState()
    val typing by app.repo.typing.collectAsState()

    // Small sounds in the open chat: a message from someone else arriving, and someone starting to type.
    var heardUpTo by remember(roomId) { mutableStateOf<String?>(null) }
    LaunchedEffect(roomId, messages.lastOrNull()?.id) {
        val last = messages.lastOrNull()
        val opening = heardUpTo == null
        heardUpTo = last?.id ?: ""
        // Only something that just arrived makes the sound: not the history that fills in when the chat opens.
        if (!opening && last != null && last.userId != (app.repo.me.value?.id ?: "") && System.currentTimeMillis() - last.createdAt < 15_000) {
            app.sounds.play(org.hearth.ember.notify.SoundPlayer.Kind.In)
        }
    }
    val someoneTyping = typing[roomId].orEmpty().any { it != (app.repo.me.value?.id ?: "") }
    LaunchedEffect(roomId, someoneTyping) { if (someoneTyping) app.sounds.play(org.hearth.ember.notify.SoundPlayer.Kind.Typing) }
    val calls by app.repo.calls.collectAsState()
    val enterSends by app.prefs.enterSends.collectAsState()
    val e2eeStatus by app.e2ee.status.collectAsState()
    val meId = me?.id ?: ""
    val listState = rememberLazyListState()

    var draft by remember(roomId) { mutableStateOf("") }
    var replyTo by remember(roomId) { mutableStateOf<Message?>(null) }
    var editing by remember(roomId) { mutableStateOf<Message?>(null) }
    // The messages picked: a tap or a long press picks one (the top bar then offers what you can do with it, and a bar of reactions floats
    // beside it); with others picked already, a tap adds or removes it.
    var picked by remember(roomId) { mutableStateOf(listOf<String>()) }
    var confirmDelete by remember { mutableStateOf<List<Message>?>(null) }
    var forwardingAll by remember { mutableStateOf<List<Message>?>(null) }
    var videoViewer by remember { mutableStateOf<Attachment?>(null) }
    var stickerSheet by remember { mutableStateOf<Attachment?>(null) }
    androidx.activity.compose.BackHandler(enabled = picked.isNotEmpty()) { picked = emptyList() }
    var actionAnchor by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var emojiFor by remember { mutableStateOf<Message?>(null) }
    var emojiForDraft by remember { mutableStateOf(false) }
    // The emoji search is open. Some keyboards (Samsung's) hand focus back to the message box as they open for the search box; that must not close the panel.
    var emojiSearching by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val fieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // The drawer is as tall as the keyboard was, so switching between them does not move the message box.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeBottom = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(density)
    var keyboardDp by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(300f) }
    LaunchedEffect(imeBottom) { val dp = with(density) { imeBottom.toDp().value }; if (dp > 150f) keyboardDp = dp }
    androidx.activity.compose.BackHandler(enabled = emojiForDraft) { emojiForDraft = false }
    var viewer by remember { mutableStateOf<Attachment?>(null) }
    var forwarding by remember { mutableStateOf<Message?>(null) }
    var highlight by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // Nudges: the whole chat shakes and the phone buzzes, like the old messenger's. Nothing plays a sound.
    val shake = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(roomId) {
        app.repo.nudges.collect { (room, _) ->
            if (room != roomId) return@collect
            buzz(context)
            app.sounds.nudge()
            for (step in listOf(14f, -14f, 11f, -11f, 7f, -7f, 3f, 0f)) shake.animateTo(step, androidx.compose.animation.core.tween(55))
        }
    }
    val quick by app.prefs.quickReactions.collectAsState()
    val reactWith: suspend (Message, String) -> Unit = { m, e -> app.prefs.useEmoji(e); app.repo.react(m, e) }
    // Nudge as often as you like: the chat shakes every time. The server only turns the first one a minute into a notification. A nudge
    // is shown as a line in the chat ("You sent a nudge") but is never a message.
    val sendNudge: () -> Unit = {
        app.realtime.nudge(roomId)
        app.repo.noteNudge(roomId, app.repo.me.value?.id ?: "")
        buzz(context)
        app.sounds.nudge()
        scope.launch { for (step in listOf(10f, -10f, 6f, -6f, 0f)) shake.animateTo(step, androidx.compose.animation.core.tween(50)) }
    }

    DisposableEffect(roomId) {
        app.repo.enterRoom(roomId)
        app.notifier.cancelRoom(roomId)
        onDispose { app.repo.leaveRoomView(roomId) }
    }

    // The list is newest-first (reverse layout), so index 0 is the bottom.
    val reversed = remember(messages) { messages.asReversed() }
    // The nudge lines sit among the messages by time, newest first like the messages.
    val nudgeNotes by app.repo.nudgeNotes.collectAsState()
    val roomNotes = nudgeNotes[roomId].orEmpty()
    val timeline = remember(reversed, roomNotes) {
        val out = ArrayList<Any>(reversed.size + roomNotes.size)
        var m = 0
        var n = 0
        while (m < reversed.size || n < roomNotes.size) {
            val takeNote = n < roomNotes.size && (m >= reversed.size || roomNotes[n].at >= reversed[m].createdAt)
            if (takeNote) out.add(roomNotes[n++]) else out.add(reversed[m++])
        }
        out
    }
    val reversedIndex = remember(reversed) { reversed.withIndex().associate { it.value.id to it.index } }
    val pickedMessages = remember(messages, picked) { picked.mapNotNull { id -> messages.firstOrNull { it.id == id } } }
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    LaunchedEffect(timeline.firstOrNull().let { (it as? Message)?.id ?: (it as? org.hearth.ember.data.NudgeNote)?.id }) { if (atBottom) listState.animateScrollToItem(0) }
    LaunchedEffect(listState, reversed.size) {
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (reversed.isNotEmpty() && last >= reversed.size - 4) app.repo.loadOlder(roomId)
        }
    }

    // Tell the others while typing, and stop a few seconds after the last key.
    LaunchedEffect(draft) {
        if (draft.isNotEmpty()) {
            app.repo.typing(roomId, true)
            delay(3000)
            app.repo.typing(roomId, false)
        }
    }

    // Pictures and videos wait in a preview (with a caption box) before they go: from the gallery, or just taken with the camera.
    var outgoing by remember(roomId) { mutableStateOf<List<android.net.Uri>?>(null) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) outgoing = uris
    }
    var captureTarget by remember { mutableStateOf<android.net.Uri?>(null) }
    var wantVideo by remember { mutableStateOf(false) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val target = captureTarget
        if (ok && target != null) outgoing = listOf(target)
    }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val target = captureTarget
        if (ok && target != null) outgoing = listOf(target)
    }
    fun launchCapture(video: Boolean) {
        val dir = File(context.cacheDir, "shared").also { it.mkdirs() }
        val file = File(dir, "capture-" + System.currentTimeMillis() + if (video) ".mp4" else ".jpg")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        captureTarget = uri
        if (video) takeVideo.launch(uri) else takePhoto.launch(uri)
    }
    // The camera app can only be asked for a picture once this app (which lists the camera in its manifest) has been allowed to use it.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCapture(wantVideo) else notice = "Allow the camera to take photos and videos."
    }
    fun capture(video: Boolean) {
        wantVideo = video
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) launchCapture(video)
        else cameraPermission.launch(android.Manifest.permission.CAMERA)
    }
    fun sendFavourite(file: File, mime: String) {
        emojiForDraft = false
        scope.launch {
            sending = true
            runCatching { app.repo.sendBytes(roomId, file.readBytes(), file.name, mime) }
                .onSuccess { app.sounds.play(org.hearth.ember.notify.SoundPlayer.Kind.Out) }
                .onFailure { notice = it.message }
            sending = false
        }
    }
    fun sendReady(bytes: ByteArray, name: String, mime: String) {
        emojiForDraft = false
        scope.launch {
            sending = true
            runCatching { app.repo.sendBytes(roomId, bytes, name, mime) }.onSuccess { app.sounds.play(org.hearth.ember.notify.SoundPlayer.Kind.Out) }.onFailure { notice = it.message }
            sending = false
        }
    }

    fun sendOnline(item: org.hearth.ember.data.OnlineItem, kind: MediaKind) {
        emojiForDraft = false
        scope.launch {
            sending = true
            runCatching {
                val (bytes, name, mime) = app.online.fetchForSending(item, kind)
                app.repo.sendBytes(roomId, bytes, name, mime)
            }.onSuccess { app.sounds.play(org.hearth.ember.notify.SoundPlayer.Kind.Out) }.onFailure { notice = it.message }
            sending = false
        }
    }
    // A file goes through the same preview as a picture, so it can have a caption too.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) outgoing = uris
    }
    var attachMenu by remember { mutableStateOf(false) }

    // Voice messages: tap the microphone, speak, tap send (or the bin to throw it away).
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    fun startRecording() {
        if (recorder.start()) recording = true else notice = "The microphone could not start."
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else notice = "Allow the microphone to record voice messages."
    }
    DisposableEffect(Unit) { onDispose { if (recording) recorder.cancel() } }
    fun sendRecording() {
        val file = recorder.finish()
        recording = false
        if (file == null) return
        scope.launch {
            sending = true
            runCatching { app.repo.sendBytes(roomId, file.readBytes(), "voice-" + System.currentTimeMillis() + ".aac", "audio/aac") }
                .onSuccess { app.sounds.play(org.hearth.ember.notify.SoundPlayer.Kind.Out) }
                .onFailure { notice = it.message }
            file.delete()
            sending = false
        }
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        val edit = editing
        val reply = replyTo
        draft = ""
        editing = null
        replyTo = null
        scope.launch {
            sending = true
            runCatching { if (edit != null) app.repo.edit(edit, text) else app.repo.send(roomId, text, reply?.id) }
                .onSuccess { if (edit == null) app.sounds.play(org.hearth.ember.notify.SoundPlayer.Kind.Out) }
                .onFailure { notice = it.message; draft = text }
            sending = false
        }
    }

    Column(Modifier.fillMaxSize().offset(x = shake.value.dp).chatWallpaper().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (pickedMessages.isNotEmpty()) SelectionBar(
            messages = pickedMessages, meId = meId,
            onClose = { picked = emptyList() },
            onReply = { replyTo = pickedMessages.first(); editing = null; picked = emptyList() },
            onCopy = {
                val text = pickedMessages.mapNotNull { m -> app.repo.textOf(roomId, m.body)?.takeIf { it.isNotBlank() } }.joinToString("\n")
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("messages", text))
                picked = emptyList()
            },
            onForward = { forwardingAll = pickedMessages },
            onDelete = { confirmDelete = pickedMessages },
            onPin = { val m = pickedMessages.first(); picked = emptyList(); scope.launch { runCatching { app.repo.pin(m) } } },
            onEdit = { val m = pickedMessages.first(); picked = emptyList(); editing = m; replyTo = null; draft = m.body },
        ) else if (emojiSearching && androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500) Spacer(Modifier.statusBarsPadding()) else TopAppBar(
            navigationIcon = { if (!embedded) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = {
                if (room != null) Row(Modifier.clickable { nav.navigate("room/$roomId") }, verticalAlignment = Alignment.CenterVertically) {
                    RoomAvatar(room, meId, 38.dp, showStatus = false)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(room.title(meId), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        val typers = typing[roomId].orEmpty().filter { it != meId }
                        val sub = when {
                            typers.isNotEmpty() -> if (room.isDm) "typing…" else app.repo.userName(typers.first()) + " is typing…"
                            room.isDm -> room.other(meId)?.let { if (it.isOnline) "online" else "" } ?: ""
                            else -> "${room.members.size} members"
                        }
                        if (sub.isNotEmpty()) Text(
                            sub, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            color = if (typers.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            actions = {
                if (room != null) {
                    val live = calls[roomId]
                    // The nudge sits where the video call button used to be, in the bar and clear of the messages.
                    val nudgeInBar by app.prefs.nudgeButton.collectAsState()
                    if (nudgeInBar) IconButton(onClick = sendNudge) { Icon(Icons.Filled.WavingHand, "Nudge", tint = MaterialTheme.colorScheme.primary) }
                    // One call button, as in WhatsApp: a call already going is joined by a tap, otherwise it asks voice or video.
                    var callMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { if (live != null) nav.navigate("call/$roomId?video=false") else callMenu = true }) {
                            Icon(Icons.Filled.Call, if (live != null) "Join call" else "Call", tint = if (live != null) EmberTheme.colors.online else MaterialTheme.colorScheme.onSurface)
                        }
                        DropdownMenu(expanded = callMenu, onDismissRequest = { callMenu = false }) {
                            DropdownMenuItem(text = { Text("Voice call") }, leadingIcon = { Icon(Icons.Filled.Call, null) }, onClick = { callMenu = false; nav.navigate("call/$roomId?video=false") })
                            DropdownMenuItem(text = { Text("Video call") }, leadingIcon = { Icon(Icons.Filled.Videocam, null) }, onClick = { callMenu = false; nav.navigate("call/$roomId?video=true") })
                        }
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (room?.isDm == false) DropdownMenuItem(text = { Text("Leave group") }, onClick = {
                            menu = false
                            scope.launch { runCatching { app.api.leaveRoom(roomId) }.onSuccess { if (embedded) app.selectedRoom.value = null else nav.popBackStack() } }
                        })
                        DropdownMenuItem(text = { Text("Mark as read") }, onClick = { menu = false; app.repo.markReadIfViewing(roomId) })
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )

        // A call in this chat, as the web client's bar shows it: what state it is in and who is really in it, one tap to join. Nothing about who
        // placed it once it is going, and nothing at all while this phone is already in it (the bar across the top of the app returns to it).
        val inCallHere = app.callController?.state?.collectAsState()?.value?.let { it.roomId == roomId && (it.phase == org.hearth.ember.call.Phase.Connected || it.phase == org.hearth.ember.call.Phase.Connecting) } == true
        val users by app.repo.users.collectAsState()
        calls[roomId]?.takeIf { !inCallHere }?.let { call ->
            val meId = me?.id
            val mineElsewhere = meId != null && meId in call.participants
            val names = call.participants.map { id -> if (id == meId) "You" else users[id]?.displayName ?: "Someone" }
            Surface(color = EmberTheme.colors.online.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth().clickable { nav.navigate("call/$roomId?video=false") }) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Call, null, tint = EmberTheme.colors.online, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                call.ringing && call.startedBy.substringBefore(':') == meId -> "Ringing…"
                                call.ringing -> "${call.startedByName} is calling"
                                call.participants.isEmpty() -> "Call ended"
                                else -> "Call in progress"
                            },
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        )
                        if (!call.ringing && names.isNotEmpty()) Text(
                            names.take(3).joinToString(", ") + (if (names.size > 3) " +${names.size - 3}" else ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(if (mineElsewhere) "Switch" else "Join", color = EmberTheme.colors.online, fontWeight = FontWeight.Bold)
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (room == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
            } else {
                LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxHeight().widthIn(max = 880.dp).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(timeline, key = { _, e -> if (e is Message) e.id else (e as org.hearth.ember.data.NudgeNote).id }) { _, entry ->
                        if (entry is org.hearth.ember.data.NudgeNote) {
                            DayChip(entry.text)
                            return@itemsIndexed
                        }
                        val message = entry as Message
                        val index = reversedIndex[message.id] ?: 0
                        // Neighbours in time: older is index+1, newer is index-1 (the list is reversed).
                        val older = reversed.getOrNull(index + 1)
                        val newer = reversed.getOrNull(index - 1)
                        val firstOfGroup = older == null || older.userId != message.userId || !sameDay(older.createdAt, message.createdAt) || message.createdAt - older.createdAt > 3 * 60_000
                        val lastOfGroup = newer == null || newer.userId != message.userId || !sameDay(newer.createdAt, message.createdAt) || newer.createdAt - message.createdAt > 3 * 60_000
                        Column {
                            if (older == null || !sameDay(older.createdAt, message.createdAt)) DayChip(dayLabel(message.createdAt))
                            MessageBubble(
                                message = message, room = room, meId = meId, firstOfGroup = firstOfGroup, lastOfGroup = lastOfGroup,
                                highlighted = highlight == message.id,
                                selected = message.id in picked, selecting = picked.isNotEmpty(),
                                onSelect = { rect ->
                                    picked = if (message.id in picked) picked - message.id else picked + message.id
                                    if (picked.size == 1) actionAnchor = rect
                                },
                                onReply = { replyTo = message; editing = null },
                                onLongPress = { rect -> if (message.id !in picked) picked = picked + message.id; if (picked.size == 1) actionAnchor = rect },
                                onOpenImage = { viewer = it },
                                onOpenVideo = { videoViewer = it },
                                onOpenSticker = { stickerSheet = it },
                                onForward = { forwardingAll = listOf(message) },
                                onOpenFile = { a -> scope.launch { openFile(context, a) { notice = it } } },
                                onJumpTo = { id ->
                                    val target = timeline.indexOfFirst { it is Message && it.id == id }
                                    if (target >= 0) scope.launch { listState.animateScrollToItem(target); highlight = id; delay(1200); highlight = null }
                                },
                                onReact = { emoji -> scope.launch { runCatching { reactWith(message, emoji) } } },
                            )
                        }
                    }
                    if (room.encrypted) item { EncryptedNotice() }
                }
                androidx.compose.animation.AnimatedVisibility(visible = !atBottom, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                    SmallFloatingActionButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }, containerColor = MaterialTheme.colorScheme.surface) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Latest")
                    }
                }
            }
        }

        notice?.let {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().clickable { notice = null }) {
                Text(it, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Replying or editing: a bar above the composer saying so.
        val context2 = replyTo ?: editing
        AnimatedVisibility(visible = context2 != null) {
            context2?.let { m ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (editing != null) Icons.Filled.Edit else Icons.AutoMirrored.Filled.Reply, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (editing != null) "Edit message" else "Reply to ${if (m.userId == meId) "yourself" else m.user?.displayName ?: "message"}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Text(m.body.ifBlank { "Attachment" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { replyTo = null; editing = null; if (editing != null) draft = "" }) { Icon(Icons.Filled.Close, "Cancel") }
                    }
                }
            }
        }

        if (room?.encrypted == true && e2eeStatus != org.hearth.ember.data.E2eeStatus.Ready) {
            EncryptionGate(e2eeStatus) { create -> scope.launch { runCatching { app.e2ee.init(createIfMissing = create) } } }
        } else if (recording) {
            RecordingBar(recorder, onCancel = { recorder.cancel(); recording = false }, onSend = { sendRecording() })
        } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val shortWindow = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500
            if (!(emojiSearching && shortWindow)) Composer(
                text = draft, onText = { draft = it }, enterSends = enterSends, sending = sending,
                onSend = { send() },
                onEmoji = {
                    if (emojiForDraft) { emojiForDraft = false; fieldFocus.requestFocus(); keyboard?.show() }
                    else { focusManager.clearFocus(); keyboard?.hide(); emojiForDraft = true }
                },
                onNudge = sendNudge, onCamera = { capture(it) },
                fieldFocus = fieldFocus, onFieldFocused = { if (!emojiSearching) emojiForDraft = false }, bottomInset = !emojiForDraft, panelOpen = emojiForDraft,
                onRecord = {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) startRecording()
                    else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                },
                attachMenu = attachMenu, onAttachMenu = { attachMenu = it },
                onPhoto = { attachMenu = false; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                onFile = { attachMenu = false; pickFile.launch(arrayOf("*/*")) },
            )
            if (emojiForDraft) EmojiPanel(
                height = keyboardDp.coerceAtMost(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.55f).coerceAtLeast(if (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500) 210f else 260f).dp,
                onSticker = { f -> sendFavourite(f, "image/webp") }, onGif = { f -> sendFavourite(f, "image/gif") },
                onOnline = { item, kind -> sendOnline(item, kind) }, onBytes = { bytes, name, mime -> sendReady(bytes, name, mime) },
                onSettings = { emojiForDraft = false; nav.navigate("settings/online") },
                onSearching = { emojiSearching = it },
            ) { e -> draft += e }
        }
    }
    outgoing?.let { list ->
        MediaPreview(
            list, onCancel = { outgoing = null },
            onSend = { caption, quality ->
                outgoing = null
                scope.launch {
                    sending = true
                    for ((i, uri) in list.withIndex()) {
                        runCatching { app.repo.sendUri(roomId, uri, if (i == 0) caption else "", quality) }
                            .onSuccess { app.sounds.play(org.hearth.ember.notify.SoundPlayer.Kind.Out) }
                            .onFailure { notice = it.message }
                    }
                    sending = false
                }
            },
        )
    }
    emojiFor?.let { m -> EmojiSheet(onDismiss = { emojiFor = null }) { e -> emojiFor = null; scope.launch { runCatching { reactWith(m, e) } } } }
    // One message picked: its reactions float beside it (more than one picked: nothing floats, the top bar handles them).
    pickedMessages.singleOrNull()?.takeIf { it.deletedAt == null }?.let { m ->
        ReactionBar(
            mine = m.userId == meId, quick = quick, anchor = actionAnchor,
            onReact = { emoji -> picked = emptyList(); scope.launch { runCatching { reactWith(m, emoji) } } },
            onMore = { picked = emptyList(); emojiFor = m },
        )
    }

    stickerSheet?.let { a -> StickerSheet(a, roomId, onDismiss = { stickerSheet = null }) }
    videoViewer?.let { VideoViewer(it, roomId) { videoViewer = null } }

    // What the top bar's buttons lead to.
    confirmDelete?.let { list ->
        val mineOnly = list.filter { it.userId == (app.repo.me.value?.id ?: "") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (mineOnly.size == 1) "Delete this message?" else "Delete ${mineOnly.size} messages?") },
            text = { Text("They are removed for everyone and cannot be brought back." + if (mineOnly.size < list.size) " Messages from other people stay: only yours can be deleted." else "") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDelete = null; picked = emptyList()
                    scope.launch { for (m in mineOnly) runCatching { app.repo.delete(m) }.onFailure { notice = it.message } }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    forwardingAll?.let { list ->
        ForwardSheet(list.first(), onDismiss = { forwardingAll = null }) { target ->
            forwardingAll = null; picked = emptyList()
            scope.launch { for (m in list) runCatching { app.repo.forward(m, target) }.onFailure { notice = it.message } }
        }
    }
    forwarding?.let { m ->
        ForwardSheet(m, onDismiss = { forwarding = null }) { target ->
            forwarding = null
            scope.launch { runCatching { app.repo.forward(m, target) }.onFailure { notice = it.message } }
        }
    }

    viewer?.let { ImageViewer(it) { viewer = null } }
}

@Composable
private fun DayChip(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), shadowElevation = 0.5.dp) {
            Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EncryptedNotice() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("End-to-end encrypted", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    text: String, onText: (String) -> Unit, enterSends: Boolean, sending: Boolean, onSend: () -> Unit, onEmoji: () -> Unit, onNudge: () -> Unit, onRecord: () -> Unit,
    attachMenu: Boolean, onAttachMenu: (Boolean) -> Unit, onPhoto: () -> Unit, onFile: () -> Unit, onCamera: (video: Boolean) -> Unit,
    fieldFocus: androidx.compose.ui.focus.FocusRequester, onFieldFocused: () -> Unit, bottomInset: Boolean, panelOpen: Boolean,
) {
    var cameraMenu by remember { mutableStateOf(false) }
    // The box keeps its own cursor (the chat only holds the words) so a suggestion can be put in at the right place.
    var tfv by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(text)) }
    if (tfv.text != text) tfv = androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val context = LocalContext.current
    val spellOn by context.ember.prefs.spellCheck.collectAsState()
    val checker = remember { SpellChecker(context) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { checker.close() } }
    var errors by remember { mutableStateOf<List<SpellError>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(text, spellOn) {
        if (!spellOn) { errors = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(350) // not on every key
        errors = checker.check(text)
    }
    val errorColor = MaterialTheme.colorScheme.error
    val here = if (spellOn && tfv.selection.collapsed) SpellChecker.at(errors, tfv.selection.start) else null
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500
    val buttonHeight = if (compact) 44.dp else 56.dp
    val nudgeButton by LocalContext.current.ember.prefs.nudgeButton.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.widthIn(max = 880.dp).fillMaxWidth()) {
    if (here != null && here.suggestions.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (suggestion in here.suggestions) androidx.compose.material3.SuggestionChip(
            onClick = { val (t, cursor) = SpellChecker.apply(text, here, suggestion); tfv = androidx.compose.ui.text.input.TextFieldValue(t, androidx.compose.ui.text.TextRange(cursor)); onText(t) },
            label = { Text(suggestion) },
        )
    }
    Row(Modifier.fillMaxWidth().then(if (bottomInset) Modifier.navigationBarsPadding() else Modifier).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
        Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                // Emoji, stickers and GIFs live behind the face at the left, as in the messengers you know.
                IconButton(onClick = onEmoji, modifier = Modifier.size(width = 44.dp, height = buttonHeight)) {
                    Icon(if (panelOpen) Icons.Filled.Keyboard else Icons.Filled.EmojiEmotions, if (panelOpen) "Keyboard" else "Emoji, stickers and GIFs", tint = muted)
                }
                val fieldModifier = Modifier.weight(1f).heightIn(max = 160.dp).focusRequester(fieldFocus).onFocusChanged { if (it.isFocused) onFieldFocused() }.onPreviewKeyEvent { e ->
                    // A physical keyboard: Enter sends, Shift+Enter makes a new line (the on-screen keyboard keeps its own Enter).
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed) {
                        onSend(); true
                    } else false
                }
                val options = KeyboardOptions(imeAction = if (enterSends) ImeAction.Send else ImeAction.Default, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences, autoCorrectEnabled = true)
                val actions = androidx.compose.foundation.text.KeyboardActions(onSend = { onSend() })
                androidx.compose.foundation.text.BasicTextField(
                    value = tfv, modifier = fieldModifier, maxLines = 6,
                    onValueChange = { tfv = it; if (it.text != text) onText(it.text) },
                    onTextLayout = { layout = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = options, keyboardActions = actions,
                    decorationBox = { inner ->
                        TextFieldDefaults.DecorationBox(
                            value = tfv.text,
                            // The squiggles are drawn under the text itself, so they line up with it at any size.
                            innerTextField = { Box(Modifier.drawBehind { drawSpellErrors(layout, errors, errorColor) }) { inner() } },
                            enabled = true, singleLine = false,
                            visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            placeholder = { org.hearth.ember.ui.components.PlaceholderText("Message") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                            // Sideways with the keyboard up there is barely room for one line: less padding, so the text is never cut.
                            contentPadding = if (compact) TextFieldDefaults.contentPaddingWithoutLabel(top = 8.dp, bottom = 8.dp) else TextFieldDefaults.contentPaddingWithoutLabel(),
                        )
                    },
                )
                Box {
                    IconButton(onClick = { onAttachMenu(true) }, modifier = Modifier.size(width = 44.dp, height = buttonHeight)) { Icon(Icons.Filled.AttachFile, "Attach", tint = muted) }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { onAttachMenu(false) }) {
                        DropdownMenuItem(text = { Text("Photos and videos") }, leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null) }, onClick = onPhoto)
                        DropdownMenuItem(text = { Text("File") }, leadingIcon = { Icon(Icons.Filled.AttachFile, null) }, onClick = onFile)
                        // The floating nudge button does this; the menu only keeps it for when that button is switched off.
                        if (!nudgeButton) DropdownMenuItem(text = { Text("Nudge") }, leadingIcon = { Icon(Icons.Filled.WavingHand, null) }, onClick = { onAttachMenu(false); onNudge() })
                    }
                }
                // Nothing typed yet: the camera sits in the field, a thumb away.
                if (text.isBlank()) Box {
                    IconButton(onClick = { cameraMenu = true }, modifier = Modifier.size(width = 44.dp, height = buttonHeight)) { Icon(Icons.Filled.PhotoCamera, "Camera", tint = muted) }
                    DropdownMenu(expanded = cameraMenu, onDismissRequest = { cameraMenu = false }) {
                        DropdownMenuItem(text = { Text("Take a photo") }, leadingIcon = { Icon(Icons.Filled.PhotoCamera, null) }, onClick = { cameraMenu = false; onCamera(false) })
                        DropdownMenuItem(text = { Text("Record a video") }, leadingIcon = { Icon(Icons.Filled.Videocam, null) }, onClick = { cameraMenu = false; onCamera(true) })
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        val hasText = text.isNotBlank()
        Box(
            Modifier.padding(bottom = 4.dp).size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(enabled = !sending) { if (hasText) onSend() else onRecord() },
            contentAlignment = Alignment.Center,
        ) {
            // Nothing typed: the button is the microphone. Something typed: it sends.
            Icon(if (hasText) Icons.AutoMirrored.Filled.Send else Icons.Filled.Mic, if (hasText) "Send" else "Record a voice message", tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
    }
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForwardSheet(message: Message, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val app = LocalContext.current.ember
    val rooms by app.repo.rooms.collectAsState()
    val me by app.repo.me.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Forward to", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold)
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            itemsIndexed(rooms.filter { !it.encrypted }, key = { _, r -> r.id }) { _, r ->
                Row(Modifier.fillMaxWidth().clickable { onPick(r.id) }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoomAvatar(r, me?.id ?: "", 42.dp, showStatus = false)
                    Spacer(Modifier.width(14.dp))
                    Text(r.title(me?.id ?: ""), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Download an attachment and hand it to whatever app opens it. */
private suspend fun openFile(context: Context, a: Attachment, fail: (String) -> Unit) {
    val app = context.ember
    runCatching {
        val bytes = app.api.downloadAttachment(a.id)
        val dir = File(context.cacheDir, "shared").also { it.mkdirs() }
        val file = File(dir, a.filename.ifBlank { a.id }.replace('/', '_'))
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, a.mime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(intent, a.filename).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { fail(it.message ?: "Could not open that file.") }
}

/** Shown in place of the message box in an encrypted chat this phone cannot read yet. */
@Composable
private fun EncryptionGate(status: org.hearth.ember.data.E2eeStatus, onTry: (createKey: Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    when (status) {
                        org.hearth.ember.data.E2eeStatus.None -> "This chat is end-to-end encrypted, and nobody has set up the key yet. Set it up on this phone to start."
                        org.hearth.ember.data.E2eeStatus.Waiting -> "This phone is waiting to be let in. Open Hearth on a phone or computer that can already read this chat and it will be added automatically."
                        else -> "Checking encryption…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedButton(onClick = { onTry(false) }) { Text("Check again") }
                if (status == org.hearth.ember.data.E2eeStatus.None) androidx.compose.material3.Button(onClick = { onTry(true) }) { Text("Set up on this phone") }
            }
        }
    }
}

/** A short strong buzz, for a nudge. */
private fun buzz(context: Context) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
    if (android.os.Build.VERSION.SDK_INT >= 26) vibrator.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 90, 60, 90, 60, 160), -1))
    else @Suppress("DEPRECATION") vibrator.vibrate(longArrayOf(0, 90, 60, 90, 60, 160), -1)
}

private val Emoji = ("😀 😃 😄 😁 😆 😅 😂 🤣 🙂 😉 😊 😇 🥰 😍 🤩 😘 😋 😛 😜 🤪 🤔 🤨 😐 😑 😶 🙄 😏 😣 😥 😮 🤐 😯 😪 😫 🥱 😴 😌 😎 🤓 🥳 😕 🙁 ☹️ 😟 😢 😭 😤 😠 😡 🤬 😱 😨 😰 😳 🥺 😬 🤗 🤭 🫡 🫠 " +
    "👍 👎 👌 ✌️ 🤞 🤟 🤘 👏 🙌 🙏 🤝 💪 👋 🤙 ☝️ 👆 👇 👈 👉 🖐️ ✋ 🫶 ❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 ❣️ 💕 💞 💖 💗 💯 🔥 ✨ ⭐ 🌟 🎉 🎊 🎁 🏆 ☀️ 🌙 🌈 ☕ 🍕 🍔 🍺 🍷 🎵 🎶 " +
    "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐸 🐵 🐔 🐧 🦄 🐢 🦋 🌹 🌸 🌻 🌲 ✅ ❌ ❓ ❗ 💬 👀 💤 🚀 💡 📌 📷 🎮").split(' ')

private class EmojiItem(val glyph: String, val name: String, val category: Int)

private val EmojiCategories = listOf("Popular", "Faces", "Nature and objects", "Travel", "More", "Symbols")

/** The phone's own emoji (drawn by its font), grouped by Unicode block, each with its Unicode name so it can be searched. */
private val AllEmoji: List<EmojiItem> by lazy {
    val paint = android.graphics.Paint()
    val items = ArrayList<EmojiItem>()
    val seen = HashSet<String>()
    for (e in Emoji) if (seen.add(e)) items.add(EmojiItem(e, "", 0))
    fun block(category: Int, vararg ranges: IntRange) {
        for (range in ranges) for (cp in range) {
            val g = String(Character.toChars(cp))
            if (seen.add(g) && paint.hasGlyph(g)) items.add(EmojiItem(g, android.icu.lang.UCharacter.getName(cp)?.lowercase() ?: "", category))
        }
    }
    block(1, 0x1F600..0x1F64F, 0x1F910..0x1F92F, 0x1F970..0x1F97F)
    block(2, 0x1F300..0x1F5FF)
    block(3, 0x1F680..0x1F6FF)
    block(4, 0x1F900..0x1F9FF, 0x1FA70..0x1FAFF)
    block(5, 0x2600..0x27BF)
    items
}

/** The sheet used for picking a reaction: the emoji only, a tap uses one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        EmojiBody(380.dp, onPick = onPick)
        Spacer(Modifier.navigationBarsPadding())
    }
}

/**
 * The drawer under the message box, where the keyboard was, laid out like WhatsApp's and Signal's: the message box stays in view above it, and
 * tapping the box (or the face button) brings the keyboard back. It is [height] tall, with the system bar below it in addition, so none of it is hidden.
 */
@Composable
private fun EmojiPanel(
    height: androidx.compose.ui.unit.Dp, onSticker: (File) -> Unit, onGif: (File) -> Unit, onOnline: (org.hearth.ember.data.OnlineItem, MediaKind) -> Unit,
    onBytes: (ByteArray, String, String) -> Unit, onSettings: () -> Unit, onSearching: (Boolean) -> Unit = {}, onPick: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().widthIn(max = 880.dp)) {
        Column {
            EmojiBody(height, onSticker, onGif, onOnline, onBytes, onSettings, onPick, onSearching)
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

private val EmojiCategoryIcons = listOf("🕒", "⭐", "😀", "🐱", "🚗", "💡", "❤️")
private const val RecentEmojiMax = 24

/**
 * Emoji, GIFs and stickers (the last two only when sending a message, not when reacting). The emoji grid fills the panel and scrolls, with a row of
 * categories above it and the three kinds along the bottom. The magnifier turns the top row into a search whose answers are one strip of
 * suggestions, not the whole list; the panel shrinks to that strip. [height] is the panel's own height.
 */
@Composable
private fun EmojiBody(
    height: androidx.compose.ui.unit.Dp, onSticker: ((File) -> Unit)? = null, onGif: ((File) -> Unit)? = null,
    onOnline: ((org.hearth.ember.data.OnlineItem, MediaKind) -> Unit)? = null, onBytes: (ByteArray, String, String) -> Unit = { _, _, _ -> },
    onSettings: () -> Unit = {}, onPick: (String) -> Unit, onSearching: (Boolean) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val recentPrefs = remember { context.getSharedPreferences("ember_emoji", android.content.Context.MODE_PRIVATE) }
    // The last 24 emoji used, newest first, kept across launches; the first tab shows them.
    val recents = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll((recentPrefs.getString("recent", "") ?: "").split(' ').filter { it.isNotEmpty() }.take(RecentEmojiMax)) } }
    var category by remember { mutableStateOf(if (recents.isEmpty()) 1 else 0) }
    val pick: (String) -> Unit = { e ->
        recents.remove(e); recents.add(0, e)
        while (recents.size > RecentEmojiMax) recents.removeAt(recents.lastIndex)
        recentPrefs.edit().putString("recent", recents.joinToString(" ")).apply()
        onPick(e)
    }
    var searching by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(0) } // 0 emoji, 1 GIFs, 2 stickers
    val kinds = onSticker != null && onGif != null
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    LaunchedEffect(category) { gridState.scrollToItem(0) }
    // Tell the chat while the search is open, and when this panel goes away with it open.
    LaunchedEffect(searching) { onSearching(searching) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onSearching(false) } }
    // A short wait lets the panel settle before the keyboard is asked for, so the two do not fight over the window.
    LaunchedEffect(searching) { if (searching) { kotlinx.coroutines.delay(120); focus.requestFocus(); keyboard?.show() } }
    val suggestions = remember(query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) emptyList() else AllEmoji.filter { it.name.contains(q) }.take(40)
    }
    val barHeight = if (kinds) (if (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500) 40.dp else 48.dp) else 0.dp
    // While searching the panel is as tall as its content (the field, a line of text and a strip of answers), whatever the text size is.
    Column(Modifier.fillMaxWidth().then(if (searching) Modifier else Modifier.height(height))) {
        Box(Modifier.then(if (searching) Modifier else Modifier.weight(1f)).fillMaxWidth()) {
            when {
                section == 1 && kinds -> MediaPicker(MediaKind.Gif, onGif!!, { onOnline?.invoke(it, MediaKind.Gif) }, onBytes, onSettings, height - barHeight, onSearching)
                section == 2 && kinds -> MediaPicker(MediaKind.Sticker, onSticker!!, { onOnline?.invoke(it, MediaKind.Sticker) }, onBytes, onSettings, height - barHeight, onSearching)
                // Sideways there is room for one row above the keyboard: the field and its answers share it.
                searching && androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500 -> Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { searching = false; query = ""; focusManager.clearFocus(); keyboard?.hide() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close the search") }
                    androidx.compose.material3.TextField(
                        value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search emoji", maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).focusRequester(focus),
                        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    )
                    androidx.compose.foundation.lazy.LazyRow(Modifier.weight(1.3f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        items(suggestions.size) { i -> Text(suggestions[i].glyph, fontSize = 28.sp, modifier = Modifier.clip(CircleShape).clickable { pick(suggestions[i].glyph) }.padding(6.dp)) }
                    }
                }
                searching -> Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { searching = false; query = ""; focusManager.clearFocus(); keyboard?.hide() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close the search") }
                        androidx.compose.material3.TextField(
                            value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search emoji", maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp).focusRequester(focus),
                            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                        )
                    }
                    if (suggestions.isEmpty()) Text(
                        if (query.isBlank()) "Type a word: heart, cat, pizza…" else "No emoji for that.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    ) else androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                        items(suggestions.size) { i -> Text(suggestions[i].glyph, fontSize = 30.sp, modifier = Modifier.clip(CircleShape).clickable { pick(suggestions[i].glyph) }.padding(8.dp)) }
                    }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search emoji") }
                        androidx.compose.foundation.lazy.LazyRow(Modifier.weight(1f)) {
                            items(EmojiCategoryIcons.size) { i ->
                                Box(
                                    Modifier.padding(horizontal = 2.dp).size(40.dp).clip(CircleShape)
                                        .background(if (category == i) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent).clickable { category = i },
                                    contentAlignment = Alignment.Center,
                                ) { Text(EmojiCategoryIcons.getOrElse(i) { "•" }, fontSize = 20.sp) }
                            }
                        }
                    }
                    val list: List<String> = if (category == 0) recents.toList() else remember(category) { AllEmoji.filter { it.category == category - 1 }.map { it.glyph } }
                    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
                    if (category == 0 && list.isEmpty()) Text("Emoji you use show up here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = if (wide) androidx.compose.foundation.lazy.grid.GridCells.Adaptive(46.dp) else androidx.compose.foundation.lazy.grid.GridCells.Fixed(8), state = gridState,
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp, top = 2.dp),
                    ) {
                        items(list.size) { i -> Text(list[i], fontSize = 26.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().clip(CircleShape).clickable { pick(list[i]) }.padding(vertical = 8.dp)) }
                    }
                }
            }
        }
        // The three kinds along the bottom, as in the messengers; hidden while searching so the strip sits right above the keyboard.
        if (kinds && !searching) Row(Modifier.fillMaxWidth().height(barHeight), verticalAlignment = Alignment.CenterVertically) {
            listOf(Icons.Filled.EmojiEmotions to "Emoji", Icons.Filled.Gif to "GIFs", Icons.AutoMirrored.Filled.StickyNote2 to "Stickers").forEachIndexed { i, (icon, label) ->
                Box(Modifier.weight(1f).fillMaxHeight().clickable { section = i }, contentAlignment = Alignment.Center) {
                    Icon(icon, label, tint = if (section == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (section == i) Box(Modifier.align(Alignment.BottomCenter).width(36.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}
