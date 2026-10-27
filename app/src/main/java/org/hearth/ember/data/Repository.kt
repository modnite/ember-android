package org.hearth.ember.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient

/** Someone is calling this phone and nobody has answered yet. */
data class Ring(val roomId: String, val fromUserId: String, val fromName: String, val startedAt: Long)

/**
 * The one place the app's state lives: who I am, my chats, the messages in them, who is online, typing, calling. The screens only
 * read these flows and call the functions; the server's live frames and the REST calls both end up here.
 */
class Repository(
    private val context: Context,
    val store: SessionStore,
    val api: Api,
    val realtime: Realtime,
    val client: OkHttpClient,
    private val scope: CoroutineScope,
    val e2ee: E2ee,
) {
    private val _me = MutableStateFlow<User?>(null)
    val me: StateFlow<User?> = _me.asStateFlow()

    private val _rooms = MutableStateFlow<List<Room>>(emptyList())
    val rooms: StateFlow<List<Room>> = _rooms.asStateFlow()

    private val _users = MutableStateFlow<Map<String, User>>(emptyMap())
    val users: StateFlow<Map<String, User>> = _users.asStateFlow()

    private val _contacts = MutableStateFlow(ContactsResponse())
    val contacts: StateFlow<ContactsResponse> = _contacts.asStateFlow()

    private val _typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val typing: StateFlow<Map<String, Set<String>>> = _typing.asStateFlow()

    private val _calls = MutableStateFlow<Map<String, ActiveCall>>(emptyMap())
    val calls: StateFlow<Map<String, ActiveCall>> = _calls.asStateFlow()

    private val _ring = MutableStateFlow<Ring?>(null)
    val ring: StateFlow<Ring?> = _ring.asStateFlow()

    private val _nudges = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)

    /** Someone nudged a chat (room id, who): the open chat shakes. */
    val nudges: kotlinx.coroutines.flow.SharedFlow<Pair<String, String>> = _nudges

    /** A line in a chat saying someone nudged ("B sent a nudge"), like the web client's. Kept while the app lives; a nudge is never a message. */
    class NudgeNote(val id: String, val text: String, val at: Long)

    private val _nudgeNotes = MutableStateFlow<Map<String, List<NudgeNote>>>(emptyMap())
    val nudgeNotes: StateFlow<Map<String, List<NudgeNote>>> = _nudgeNotes.asStateFlow()

    fun noteNudge(room: String, userId: String) {
        val mine = userId == _me.value?.id
        val name = if (mine) "You" else users.value[userId]?.displayName ?: "Someone"
        val note = NudgeNote("nudge-" + System.nanoTime(), if (mine) "You sent a nudge" else "$name sent a nudge", System.currentTimeMillis())
        _nudgeNotes.update { all -> all + (room to (listOf(note) + (all[room] ?: emptyList())).take(40)) }
    }

    private val _loaded = MutableStateFlow(false)
    /** True once the first list of chats has arrived (so the UI can tell "loading" from "empty"). */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _signedIn = MutableStateFlow(store.signedIn)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val connected get() = realtime.connected

    /** The chat the person is looking at right now: its messages are read as they arrive and it does not notify. */
    @Volatile var viewingRoom: String? = null
        private set
    @Volatile var appVisible = false

    private val messageFlows = HashMap<String, MutableStateFlow<List<Message>>>()
    private val exhausted = HashSet<String>()
    private val typingJobs = HashMap<String, Job>()

    fun messagesFlow(roomId: String): StateFlow<List<Message>> = flowFor(roomId).asStateFlow()

    private fun flowFor(roomId: String) = synchronized(messageFlows) { messageFlows.getOrPut(roomId) { MutableStateFlow(emptyList()) } }

    init {
        scope.launch { realtime.frames.collect { runCatching { handle(it) } } }
        if (store.signedIn) {
            scope.launch { restore() }
        }
    }

    // ------------------------------------------------------------------ sign in / out
    private suspend fun restore() {
        try {
            val user = api.me()
            _me.value = user
            store.userId = user.id
            start()
        } catch (e: ApiException) {
            if (e.status == 401) signedOut() else start() // offline: carry on with what the cookie allows
        }
    }

    suspend fun signIn(server: String, username: String, password: String) {
        store.server = server
        val user = api.login(username.trim(), password)
        afterSignIn(user)
    }

    suspend fun register(server: String, invite: String, username: String, displayName: String, password: String) {
        store.server = server
        val user = api.register(invite.trim(), username.trim(), displayName.trim(), password)
        afterSignIn(user)
    }

    private fun afterSignIn(user: User) {
        _me.value = user
        store.userId = user.id
        _signedIn.value = true
        scope.launch { start() }
    }

    private suspend fun start() {
        _signedIn.value = true
        realtime.start()
        refreshAll()
    }

    suspend fun refreshAll() {
        runCatching { e2ee.init() }
        runCatching {
            val rooms = api.rooms()
            _rooms.value = sorted(rooms)
            absorbUsers(rooms.flatMap { it.members })
            _loaded.value = true
        }.onFailure { if (_rooms.value.isEmpty()) _loaded.value = true }
        refreshPeople()
    }

    suspend fun refreshPeople() {
        runCatching {
            val users = api.users()
            _users.update { current -> current + users.associateBy { it.id } }
            _contacts.value = api.contacts()
        }
    }

    fun signOut() {
        scope.launch {
            runCatching { api.logout() }
            signedOut()
        }
    }

    private fun signedOut() {
        realtime.stop()
        e2ee.clear()
        store.signOut()
        (client.cookieJar as? PersistentCookieJar)?.clear()
        _me.value = null
        _rooms.value = emptyList()
        _users.value = emptyMap()
        _calls.value = emptyMap()
        _ring.value = null
        _loaded.value = false
        synchronized(messageFlows) { messageFlows.clear() }
        exhausted.clear()
        _signedIn.value = false
    }

    // ------------------------------------------------------------------ chats
    private fun sorted(rooms: List<Room>) = rooms.sortedByDescending { it.lastMessage?.createdAt ?: it.createdAt }

    private fun absorbUsers(list: List<User>) {
        if (list.isEmpty()) return
        _users.update { it + list.associateBy { u -> u.id } }
    }

    fun room(id: String): Room? = _rooms.value.firstOrNull { it.id == id }

    fun title(room: Room): String = room.title(_me.value?.id ?: "")

    fun userName(id: String): String = _users.value[id]?.displayName?.ifBlank { null } ?: "Someone"

    suspend fun openDm(userId: String): Room {
        val room = api.openDm(userId)
        upsertRoom(room)
        return room
    }

    suspend fun createGroup(name: String, members: List<String>): Room {
        val room = api.createRoom(name, members)
        upsertRoom(room)
        return room
    }

    private fun upsertRoom(room: Room) {
        absorbUsers(room.members)
        _rooms.update { list ->
            val index = list.indexOfFirst { it.id == room.id }
            val merged = if (index >= 0) list.toMutableList().also { it[index] = room } else list + room
            sorted(merged)
        }
    }

    /** The person opened a chat: load it, mark it read, and keep marking as messages arrive while it is open. */
    fun enterRoom(roomId: String) {
        viewingRoom = roomId
        scope.launch {
            if (flowFor(roomId).value.isEmpty()) loadLatest(roomId)
            markRead(roomId)
        }
    }

    fun leaveRoomView(roomId: String) {
        if (viewingRoom == roomId) viewingRoom = null
    }

    private suspend fun loadLatest(roomId: String) {
        runCatching { api.messages(roomId) }
            .onSuccess { list ->
                absorbUsers(list.mapNotNull { it.user })
                flowFor(roomId).value = merge(flowFor(roomId).value, list)
                if (list.size < 50) exhausted.add(roomId)
                android.util.Log.d("Repo", "loaded ${list.size} messages for $roomId, now ${flowFor(roomId).value.size}")
            }
            .onFailure { _error.value = it.message; android.util.Log.w("Repo", "history failed for $roomId", it) }
    }

    /** Older messages, when the person scrolls to the top. Returns false when there is nothing more. */
    suspend fun loadOlder(roomId: String): Boolean {
        if (roomId in exhausted) return false
        val oldest = flowFor(roomId).value.firstOrNull()?.createdAt ?: return false
        val older = runCatching { api.messages(roomId, oldest) }.getOrNull() ?: return false
        if (older.size < 50) exhausted.add(roomId)
        absorbUsers(older.mapNotNull { it.user })
        flowFor(roomId).value = merge(flowFor(roomId).value, older)
        return older.isNotEmpty()
    }

    private fun merge(a: List<Message>, b: List<Message>): List<Message> =
        (a + b).associateBy { it.id }.values.sortedBy { it.createdAt }

    private fun markRead(roomId: String) {
        _rooms.update { list -> list.map { if (it.id == roomId) it.copy(unread = 0) else it } }
        scope.launch { runCatching { api.markRead(roomId) } }
    }

    fun markReadIfViewing(roomId: String) {
        if (viewingRoom == roomId && appVisible) markRead(roomId)
    }

    // ------------------------------------------------------------------ sending
    private fun sealIfNeeded(roomId: String, text: String): String =
        if (room(roomId)?.encrypted == true) e2ee.encryptText(roomId, text) else text

    /** What a message says, in words: decrypted in an encrypted chat. Null when this phone cannot read it yet. */
    fun textOf(roomId: String, body: String): String? =
        if (body.startsWith("enc:v2:") || room(roomId)?.encrypted == true && body.startsWith("enc:")) e2ee.decryptText(roomId, body) else body

    suspend fun send(roomId: String, text: String, replyTo: String? = null) {
        val message = api.sendMessage(roomId, sealIfNeeded(roomId, text), replyTo)
        applyMessage(message, mine = true)
    }

    suspend fun sendUri(roomId: String, uri: Uri, caption: String = "") {
        val resolver = context.contentResolver
        var name = "file"
        resolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) name = c.getString(i) ?: name
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw ApiException(0, "Could not read that file.")
        sendBytes(roomId, bytes, name, mime, caption)
    }

    suspend fun sendBytes(roomId: String, bytes: ByteArray, name: String, mime: String, caption: String = "") {
        val message = if (room(roomId)?.encrypted == true) {
            val (envelope, sealed) = e2ee.sealFile(roomId, bytes, mime)
            api.upload(roomId, sealed, name, "application/octet-stream", if (caption.isNotBlank()) sealIfNeeded(roomId, caption) else "", envelope)
        } else api.upload(roomId, bytes, name, mime, caption)
        applyMessage(message, mine = true)
    }

    suspend fun edit(message: Message, text: String) = applyUpdate(api.editMessage(message.id, sealIfNeeded(message.roomId, text)))
    suspend fun delete(message: Message) = api.deleteMessage(message.id)
    suspend fun react(message: Message, emoji: String) = applyUpdate(api.react(message.id, emoji))
    suspend fun pin(message: Message) = api.pin(message.id)
    suspend fun forward(message: Message, roomId: String) {
        applyMessage(api.forward(message.id, roomId), mine = true)
    }

    fun typing(roomId: String, on: Boolean) = realtime.typing(roomId, on)

    // ------------------------------------------------------------------ people
    suspend fun addContact(username: String) {
        api.addContact(username)
        refreshPeople()
    }

    suspend fun acceptContact(id: String) {
        api.acceptContact(id)
        refreshPeople()
    }

    suspend fun removeContact(id: String) {
        api.removeContact(id)
        refreshPeople()
    }

    suspend fun updateProfile(displayName: String?, statusMessage: String?) {
        _me.value = api.updateMe(displayName, statusMessage)
    }

    suspend fun updateAvatar(bytes: ByteArray, mime: String) {
        _me.value = api.uploadAvatar(bytes, mime)
    }

    fun dismissRing() {
        _ring.value = null
    }

    fun clearError() {
        _error.value = null
    }

    // ------------------------------------------------------------------ live frames
    private fun JsonObject.str(key: String) = this[key]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }

    private fun applyMessage(message: Message, mine: Boolean) {
        val flow = flowFor(message.roomId)
        flow.update { merge(it, listOf(message)) }
        message.user?.let { absorbUsers(listOf(it)) }
        _rooms.update { list ->
            sorted(
                list.map { room ->
                    if (room.id != message.roomId) room
                    else {
                        val viewing = viewingRoom == room.id && appVisible
                        room.copy(
                            lastMessage = message,
                            unread = if (mine || viewing) 0 else room.unread + 1,
                        )
                    }
                },
            )
        }
    }

    private fun applyUpdate(message: Message) {
        flowFor(message.roomId).update { list -> list.map { if (it.id == message.id) message else it } }
        _rooms.update { list -> list.map { if (it.lastMessage?.id == message.id) it.copy(lastMessage = message) else it } }
    }

    private suspend fun handle(frame: JsonObject) {
        val myId = _me.value?.id
        when (frame.str("t")) {
            "auth.lost" -> signedOut()
            "ready" -> {
                frame["me"]?.let { runCatching { EmberJson.decodeFromJsonElement(User.serializer(), it) }.getOrNull() }?.let { _me.value = it }
                frame["online"]?.let { runCatching { EmberJson.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(User.serializer()), it) }.getOrNull() }
                    ?.let { absorbUsers(it) }
                _calls.value = frame["calls"]?.jsonArray?.mapNotNull { callOf(it.jsonObject) }?.associateBy { it.roomId } ?: emptyMap()
                // Anything that happened while the socket was down.
                refreshAll()
                viewingRoom?.let { id -> scope.launch { runCatching { api.messages(id) }.onSuccess { l -> flowFor(id).update { merge(it, l) } } } }
            }
            "presence" -> frame["user"]?.let { runCatching { EmberJson.decodeFromJsonElement(User.serializer(), it) }.getOrNull() }?.let {
                if (it.id != myId) absorbUsers(listOf(it))
            }
            "message" -> frame["message"]?.let { runCatching { EmberJson.decodeFromJsonElement(Message.serializer(), it) }.getOrNull() }?.let { m ->
                applyMessage(m, mine = m.userId == myId)
                if (m.userId != myId) {
                    _typing.update { t -> t[m.roomId]?.let { t + (m.roomId to (it - m.userId)) } ?: t }
                    markReadIfViewing(m.roomId)
                }
            }
            "message.deleted" -> {
                // Deleted for good: it leaves the conversation without a trace.
                val room = frame.str("roomId") ?: return
                val id = frame.str("id") ?: return
                val flow = flowFor(room)
                flow.value = flow.value.filterNot { it.id == id }
                _rooms.value = _rooms.value.map { r ->
                    if (r.id != room) r else r.copy(lastMessage = if (r.lastMessage?.id == id) flow.value.lastOrNull() else r.lastMessage, pinned = r.pinned.filterNot { it.id == id })
                }
            }
            "message.updated" -> frame["message"]?.let { runCatching { EmberJson.decodeFromJsonElement(Message.serializer(), it) }.getOrNull() }?.let { applyUpdate(it) }
            "room.updated" -> frame["room"]?.let { runCatching { EmberJson.decodeFromJsonElement(Room.serializer(), it) }.getOrNull() }?.let { upsertRoom(it) }
            "room.removed" -> frame.str("roomId")?.let { id -> _rooms.update { l -> l.filterNot { it.id == id } } }
            "typing" -> {
                val room = frame.str("roomId") ?: return
                val user = frame.str("userId") ?: return
                if (user == myId) return
                val on = frame["typing"]?.jsonPrimitive?.booleanOrNull ?: false
                setTyping(room, user, on)
            }
            "read" -> {
                val room = frame.str("roomId") ?: return
                val user = frame.str("userId") ?: return
                val at = frame["at"]?.jsonPrimitive?.longOrNull ?: return
                _rooms.update { list ->
                    list.map {
                        if (it.id != room) it
                        else it.copy(reads = it.reads.filterNot { r -> r.userId == user } + RoomRead(user, at))
                    }
                }
            }
            "pins" -> {
                val room = frame.str("roomId") ?: return
                val pinned = frame["pinned"]?.let { runCatching { EmberJson.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Message.serializer()), it) }.getOrNull() } ?: return
                _rooms.update { list -> list.map { if (it.id == room) it.copy(pinned = pinned) else it } }
            }
            "nudge" -> {
                val room = frame.str("roomId") ?: return
                val from = frame.str("userId") ?: return
                if (from != myId) { noteNudge(room, from); _nudges.tryEmit(room to from) }
            }
            "contacts.changed" -> refreshPeople()
            "call.active" -> callOf(frame)?.let { call ->
                _calls.update { it + (call.roomId to call) }
            }
            "call.end" -> frame.str("roomId")?.let { id ->
                _calls.update { it - id }
                if (_ring.value?.roomId == id) _ring.value = null
            }
            "call.ring" -> {
                val room = frame.str("roomId") ?: return
                val from = frame.str("fromUserId") ?: ""
                if (from != myId) _ring.value = Ring(room, from, frame.str("fromName") ?: userName(from), frame["startedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis())
            }
            "call.ring.stop", "call.accepted", "call.declined" -> frame.str("roomId")?.let { id -> if (_ring.value?.roomId == id) _ring.value = null }
        }
    }

    private fun callOf(f: JsonObject): ActiveCall? {
        val room = f.str("roomId") ?: return null
        return ActiveCall(
            roomId = room,
            ringing = f["ringing"]?.jsonPrimitive?.booleanOrNull ?: false,
            startedBy = f.str("startedBy") ?: "",
            startedByName = f.str("startedByName") ?: "Someone",
            startedAt = f["startedAt"]?.jsonPrimitive?.longOrNull ?: 0,
            participants = f["participants"]?.jsonArray?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }?.map { it.substringBefore(':') }?.distinct() ?: emptyList(),
        )
    }

    private fun setTyping(room: String, user: String, on: Boolean) {
        val key = "$room:$user"
        typingJobs.remove(key)?.cancel()
        _typing.update { t ->
            val set = (t[room] ?: emptySet()).let { if (on) it + user else it - user }
            t + (room to set)
        }
        if (on) typingJobs[key] = scope.launch {
            delay(6_000)
            setTyping(room, user, false)
        }
    }

    /** The app came back to the front: be live now, and catch up on anything missed. */
    fun onForeground() {
        appVisible = true
        realtime.nudge()
        realtime.presence(true)
        viewingRoom?.let { markRead(it) }
    }

    fun onBackground() {
        appVisible = false
        realtime.presence(false)
    }
}
