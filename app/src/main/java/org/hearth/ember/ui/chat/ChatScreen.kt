package org.hearth.ember.ui.chat

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
    // Nudge as often as you like: the chat shakes every time. The server only turns the first one a minute into a notification, and a nudge
    // never adds a line to the conversation.
    val sendNudge: () -> Unit = {
        app.realtime.nudge(roomId)
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
    val pickedMessages = remember(messages, picked) { picked.mapNotNull { id -> messages.firstOrNull { it.id == id } } }
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    LaunchedEffect(reversed.firstOrNull()?.id) { if (atBottom) listState.animateScrollToItem(0) }
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
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            sending = true
            for (uri in uris) runCatching { app.repo.sendUri(roomId, uri) }.onFailure { notice = it.message }
            sending = false
        }
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

    Column(Modifier.fillMaxSize().offset(x = shake.value.dp).background(EmberTheme.colors.chatBackground).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
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
        ) else TopAppBar(
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
                    IconButton(onClick = { nav.navigate("call/$roomId?video=true") }) { Icon(Icons.Filled.Videocam, "Video call") }
                    IconButton(onClick = { nav.navigate("call/$roomId?video=false") }) {
                        Icon(Icons.Filled.Call, if (live != null) "Join call" else "Voice call", tint = if (live != null) EmberTheme.colors.online else MaterialTheme.colorScheme.onSurface)
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

        // A live call in this chat, one tap to join.
        calls[roomId]?.let { call ->
            Surface(color = EmberTheme.colors.online.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth().clickable { nav.navigate("call/$roomId?video=false") }) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Call, null, tint = EmberTheme.colors.online, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (call.participants.size <= 1) "${call.startedByName} is calling" else "${call.participants.size} people in a call",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    )
                    Text("Join", color = EmberTheme.colors.online, fontWeight = FontWeight.Bold)
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (room == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
            } else {
                LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxHeight().widthIn(max = 880.dp).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(reversed, key = { _, m -> m.id }) { index, message ->
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
                                    val target = reversed.indexOfFirst { it.id == id }
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
            Composer(
                text = draft, onText = { draft = it }, enterSends = enterSends, sending = sending,
                onSend = { send() },
                onEmoji = {
                    if (emojiForDraft) { emojiForDraft = false; fieldFocus.requestFocus(); keyboard?.show() }
                    else { focusManager.clearFocus(); keyboard?.hide(); emojiForDraft = true }
                },
                onNudge = sendNudge, onCamera = { capture(it) },
                fieldFocus = fieldFocus, onFieldFocused = { emojiForDraft = false }, bottomInset = !emojiForDraft, panelOpen = emojiForDraft,
                onRecord = {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) startRecording()
                    else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                },
                attachMenu = attachMenu, onAttachMenu = { attachMenu = it },
                onPhoto = { attachMenu = false; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                onFile = { attachMenu = false; pickFile.launch(arrayOf("*/*")) },
            )
            if (emojiForDraft) EmojiPanel(
                height = minOf(keyboardDp, androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.5f).dp,
                onSticker = { f -> sendFavourite(f, "image/webp") }, onGif = { f -> sendFavourite(f, "image/gif") },
                onOnline = { item, kind -> sendOnline(item, kind) }, onBytes = { bytes, name, mime -> sendReady(bytes, name, mime) },
                onSettings = { emojiForDraft = false; nav.navigate("settings/online") },
            ) { e -> draft += e }
        }
    }
    outgoing?.let { list ->
        MediaPreview(
            list, onCancel = { outgoing = null },
            onSend = { caption ->
                outgoing = null
                scope.launch {
                    sending = true
                    for ((i, uri) in list.withIndex()) {
                        runCatching { app.repo.sendUri(roomId, uri, if (i == 0) caption else "") }
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

@Composable
private fun Composer(
    text: String, onText: (String) -> Unit, enterSends: Boolean, sending: Boolean, onSend: () -> Unit, onEmoji: () -> Unit, onNudge: () -> Unit, onRecord: () -> Unit,
    attachMenu: Boolean, onAttachMenu: (Boolean) -> Unit, onPhoto: () -> Unit, onFile: () -> Unit, onCamera: (video: Boolean) -> Unit,
    fieldFocus: androidx.compose.ui.focus.FocusRequester, onFieldFocused: () -> Unit, bottomInset: Boolean, panelOpen: Boolean,
) {
    var cameraMenu by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.widthIn(max = 880.dp).fillMaxWidth().then(if (bottomInset) Modifier.navigationBarsPadding() else Modifier).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
        Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                // Emoji, stickers and GIFs live behind the face at the left, as in the messengers you know.
                IconButton(onClick = onEmoji, modifier = Modifier.size(44.dp)) {
                    Icon(if (panelOpen) Icons.Filled.Keyboard else Icons.Filled.EmojiEmotions, if (panelOpen) "Keyboard" else "Emoji, stickers and GIFs", tint = muted)
                }
                TextField(
                    value = text, onValueChange = onText, placeholder = { Text("Message") },
                    modifier = Modifier.weight(1f).heightIn(max = 160.dp).focusRequester(fieldFocus).onFocusChanged { if (it.isFocused) onFieldFocused() }.onPreviewKeyEvent { e ->
                        // A physical keyboard: Enter sends, Shift+Enter makes a new line (the on-screen keyboard keeps its own Enter).
                        if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed) {
                            onSend(); true
                        } else false
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = if (enterSends) ImeAction.Send else ImeAction.Default, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onSend() }),
                    maxLines = 6,
                )
                Box {
                    IconButton(onClick = { onAttachMenu(true) }, modifier = Modifier.size(44.dp)) { Icon(Icons.Filled.AttachFile, "Attach", tint = muted) }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { onAttachMenu(false) }) {
                        DropdownMenuItem(text = { Text("Photos and videos") }, leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null) }, onClick = onPhoto)
                        DropdownMenuItem(text = { Text("File") }, leadingIcon = { Icon(Icons.Filled.AttachFile, null) }, onClick = onFile)
                        DropdownMenuItem(text = { Text("Nudge") }, leadingIcon = { Icon(Icons.Filled.WavingHand, null) }, onClick = { onAttachMenu(false); onNudge() })
                    }
                }
                // Nothing typed yet: the camera sits in the field, a thumb away.
                if (text.isBlank()) Box {
                    IconButton(onClick = { cameraMenu = true }, modifier = Modifier.size(44.dp)) { Icon(Icons.Filled.PhotoCamera, "Camera", tint = muted) }
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

/**
 * Emoji, stickers and GIFs in one sheet. Emoji: tabs by kind and a search by name, a tap uses one (typed into the message, or as a
 * reaction). Stickers and GIFs (only when sending a message, not when reacting) are the ones you keep; a tap sends one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        EmojiBody(null, onPick = onPick)
    }
}

/**
 * The drawer under the message box, where the keyboard was: the box stays in view above it, and tapping the box brings the keyboard back (the
 * face button switches the same way). It is as tall as the keyboard was, so nothing jumps.
 */
@Composable
private fun EmojiPanel(
    height: androidx.compose.ui.unit.Dp, onSticker: (File) -> Unit, onGif: (File) -> Unit, onOnline: (org.hearth.ember.data.OnlineItem, MediaKind) -> Unit,
    onBytes: (ByteArray, String, String) -> Unit, onSettings: () -> Unit, onPick: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().widthIn(max = 880.dp)) {
        Column(Modifier.navigationBarsPadding()) {
            EmojiBody(height, onSticker, onGif, onOnline, onBytes, onSettings, onPick)
        }
    }
}

/**
 * Emoji, stickers and GIFs. Emoji: tabs by kind and a search by name, a tap uses one (typed into the message, or as a reaction). Stickers and
 * GIFs (only when sending a message, not when reacting) are the ones you keep, or search online; a tap sends one. With a [height] it is a
 * panel of exactly that height (the grids scroll inside it); without, a sheet's worth.
 */
@Composable
private fun EmojiBody(
    height: androidx.compose.ui.unit.Dp?, onSticker: ((File) -> Unit)? = null, onGif: ((File) -> Unit)? = null,
    onOnline: ((org.hearth.ember.data.OnlineItem, MediaKind) -> Unit)? = null, onBytes: (ByteArray, String, String) -> Unit = { _, _, _ -> },
    onSettings: () -> Unit = {}, onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    var section by remember { mutableStateOf(0) }
    val list = remember(query, tab) {
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) AllEmoji.filter { it.name.contains(q) } else AllEmoji.filter { it.category == tab }
    }
    Column(Modifier.fillMaxWidth().then(if (height != null) Modifier.height(height) else Modifier)) {
        val sections = onSticker != null && onGif != null
        if (sections) androidx.compose.material3.TabRow(selectedTabIndex = section, containerColor = Color.Transparent) {
            listOf("Emoji", "Stickers", "GIFs").forEachIndexed { i, name -> androidx.compose.material3.Tab(selected = section == i, onClick = { section = i }, text = { Text(name) }) }
        }
        val room = height?.let { it - 48.dp }
        if (sections && section == 1) { MediaPicker(MediaKind.Sticker, onSticker!!, { onOnline?.invoke(it, MediaKind.Sticker) }, onBytes, onSettings, room); return@Column }
        if (sections && section == 2) { MediaPicker(MediaKind.Gif, onGif!!, { onOnline?.invoke(it, MediaKind.Gif) }, onBytes, onSettings, room); return@Column }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search emoji (heart, cat, pizza…)") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
        if (query.isBlank()) androidx.compose.material3.ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            EmojiCategories.forEachIndexed { i, name -> androidx.compose.material3.Tab(selected = tab == i, onClick = { tab = i }, text = { Text(name) }) }
        }
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(48.dp),
            modifier = Modifier.fillMaxWidth().then(if (height != null) Modifier.weight(1f) else Modifier.heightIn(max = 420.dp)).padding(horizontal = 12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp, top = 8.dp),
        ) {
            items(list.size) { i -> Text(list[i].glyph, fontSize = 28.sp, modifier = Modifier.clip(CircleShape).clickable { onPick(list[i].glyph) }.padding(8.dp)) }
        }
    }
}
