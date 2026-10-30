package org.hearth.ember.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The server said no, or could not be reached. The message is meant to be shown to a person. */
class ApiException(val status: Int, message: String) : Exception(message)

val EmberJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    isLenient = true
}

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

/** Talks to the Hearth server. One instance per signed-in server; the base URL comes from the session store. */
class Api(private val store: SessionStore, val client: OkHttpClient) {
    val base: String get() = store.server

    fun url(path: String) = if (path.startsWith("http")) path else base + path

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(ApiException(0, friendly(e)))
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
        cont.invokeOnCancellation { cancel() }
    }

    private fun friendly(e: IOException): String = when {
        e is java.net.UnknownHostException -> "Can't find that server. Check the address."
        e is java.net.SocketTimeoutException -> "The server took too long to answer."
        e is javax.net.ssl.SSLException -> "A secure connection could not be made to that server."
        else -> "Can't reach the server. Check your connection."
    }

    private suspend fun execute(request: Request): String {
        val call = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build().newCall(request)
        val res = call.await()
        // Reading the body is network work: off the main thread, or any response bigger than what was already buffered (a chat's history) fails.
        return withContext(Dispatchers.IO) {
            res.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    val message = runCatching { EmberJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                    throw ApiException(it.code, message ?: it.message.ifBlank { "Something went wrong (${it.code})." })
                }
                text
            }
        }
    }

    private fun json(body: JsonObject): RequestBody = body.toString().toRequestBody(JSON_TYPE)

    private suspend inline fun <reified T> get(path: String): T =
        EmberJson.decodeFromString(execute(Request.Builder().url(url(path)).get().build()))

    private suspend inline fun <reified T> post(path: String, body: JsonObject = buildJsonObject {}): T =
        EmberJson.decodeFromString(execute(Request.Builder().url(url(path)).post(json(body)).build()))

    private suspend fun send(method: String, path: String, body: JsonObject? = null) {
        val builder = Request.Builder().url(url(path))
        builder.method(method, body?.let { json(it) } ?: if (method == "GET") null else "{}".toRequestBody(JSON_TYPE))
        execute(builder.build())
    }

    // ------------------------------------------------------------------ account
    suspend fun me(): User = get<UserResponse>("/api/auth/me").user

    /** The key that seals this account's backups (the server makes it the first time it is asked for). */
    suspend fun backupKey(): BackupKeyResponse = get("/api/backup/key")

    suspend fun login(username: String, password: String, code: String? = null): User =
        post<UserResponse>("/api/auth/login", buildJsonObject { put("username", username); put("password", password); if (!code.isNullOrBlank()) put("code", code) }).user

    /** What the sign-in screen needs before anyone is signed in: whether the server lets anyone join without an invite code. */
    suspend fun authConfig(): AuthConfig = get("/api/auth/config")

    suspend fun twoStep(): TwoStepStatus = get("/api/auth/2fa")
    suspend fun twoStepSetup(): TwoStepSetup = post("/api/auth/2fa/setup")
    suspend fun twoStepEnable(code: String): TwoStepRecovery = post("/api/auth/2fa/enable", buildJsonObject { put("code", code) })
    suspend fun twoStepDisable(password: String, code: String) { send("POST", "/api/auth/2fa/disable", buildJsonObject { put("password", password); put("code", code) }) }

    suspend fun register(invite: String, username: String, displayName: String, password: String): User =
        post<UserResponse>(
            "/api/auth/register",
            buildJsonObject { put("invite", invite); put("username", username); put("displayName", displayName); put("password", password) },
        ).user

    suspend fun logout() = send("POST", "/api/auth/logout", buildJsonObject {})

    suspend fun updateMe(displayName: String? = null, statusMessage: String? = null): User =
        EmberJson.decodeFromString<UserResponse>(
            execute(
                Request.Builder().url(url("/api/auth/me")).patch(
                    json(buildJsonObject {
                        displayName?.let { put("displayName", it) }
                        statusMessage?.let { put("statusMessage", it) }
                    }),
                ).build(),
            ),
        ).user

    /** A group's picture, shown to every member. */
    suspend fun uploadRoomAvatar(roomId: String, bytes: ByteArray, mime: String): Room {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "group", bytes.toRequestBody(mime.toMediaType())).build()
        return EmberJson.decodeFromString<RoomResponse>(execute(Request.Builder().url(url("/api/rooms/$roomId/avatar")).post(form).build())).room
    }

    suspend fun deleteRoomAvatar(roomId: String): Room =
        EmberJson.decodeFromString<RoomResponse>(execute(Request.Builder().url(url("/api/rooms/$roomId/avatar")).delete().build())).room

    suspend fun uploadAvatar(bytes: ByteArray, mime: String): User {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "avatar", bytes.toRequestBody(mime.toMediaType())).build()
        return EmberJson.decodeFromString<UserResponse>(execute(Request.Builder().url(url("/api/auth/avatar")).post(form).build())).user
    }

    // ------------------------------------------------------------------ people
    suspend fun users(): List<User> = get<UsersResponse>("/api/users").users
    suspend fun contacts(): ContactsResponse = get("/api/contacts")
    suspend fun addContact(username: String) = send("POST", "/api/contacts", buildJsonObject { put("username", username) })
    /** An exact username or friend code. The server never lists people, so this is how someone is found. */
    suspend fun lookup(query: String): LookupResult = get("/api/users/lookup?q=" + java.net.URLEncoder.encode(query, "UTF-8"))
    suspend fun friendCode(): FriendCode = get("/api/friend-code")
    suspend fun newFriendCode(): FriendCode = post("/api/friend-code")
    suspend fun blocks(): BlocksResponse = get("/api/blocks")
    suspend fun block(userId: String) { send("POST", "/api/blocks", buildJsonObject { put("userId", userId) }) }
    suspend fun unblock(userId: String) { send("DELETE", "/api/blocks/$userId") }
    suspend fun changeUsername(username: String, password: String): User =
        post<UserResponse>("/api/auth/username", buildJsonObject { put("username", username); put("password", password) }).user
    suspend fun changePassword(current: String, next: String) {
        send("POST", "/api/auth/change-password", buildJsonObject { put("current", current); put("next", next) })
    }
    suspend fun archiveRoom(roomId: String, archived: Boolean): Room =
        post<RoomResponse>("/api/rooms/$roomId/archive", buildJsonObject { put("archived", archived) }).room
    suspend fun acceptContact(id: String) = send("POST", "/api/contacts/$id/accept", buildJsonObject {})
    suspend fun removeContact(id: String) = send("DELETE", "/api/contacts/$id")
    suspend fun openDm(userId: String): Room = post<RoomResponse>("/api/users/$userId/dm").room

    // ------------------------------------------------------------------ rooms
    suspend fun rooms(): List<Room> = get<RoomsResponse>("/api/rooms").rooms

    suspend fun createRoom(name: String, memberIds: List<String>, topic: String = ""): Room =
        post<RoomResponse>(
            "/api/rooms",
            buildJsonObject {
                put("name", name)
                put("topic", topic)
                put("encrypted", false)
                put("memberIds", kotlinx.serialization.json.JsonArray(memberIds.map { JsonPrimitive(it) }))
            },
        ).room

    suspend fun leaveRoom(id: String) = send("POST", "/api/rooms/$id/leave", buildJsonObject {})

    suspend fun addMembers(roomId: String, userIds: List<String>): AddMembersResponse =
        post("/api/rooms/$roomId/members", buildJsonObject { put("userIds", kotlinx.serialization.json.JsonArray(userIds.map { JsonPrimitive(it) })) })

    suspend fun removeMember(roomId: String, userId: String): Room =
        EmberJson.decodeFromString<RoomResponse>(execute(Request.Builder().url(url("/api/rooms/$roomId/members/$userId")).delete().build())).room

    suspend fun renameRoom(id: String, name: String? = null, topic: String? = null): Room =
        EmberJson.decodeFromString<RoomResponse>(
            execute(
                Request.Builder().url(url("/api/rooms/$id")).patch(
                    json(buildJsonObject {
                        name?.let { put("name", it) }
                        topic?.let { put("topic", it) }
                    }),
                ).build(),
            ),
        ).room

    // ------------------------------------------------------------------ messages
    suspend fun messages(roomId: String, before: Long? = null): List<Message> =
        get<MessagesResponse>("/api/rooms/$roomId/messages" + (before?.let { "?before=$it" } ?: "")).messages

    suspend fun sendMessage(roomId: String, body: String, replyTo: String? = null): Message =
        post<MessageResponse>(
            "/api/rooms/$roomId/messages",
            buildJsonObject { put("body", body); replyTo?.let { put("replyTo", it) } },
        ).message

    suspend fun editMessage(id: String, body: String): Message =
        EmberJson.decodeFromString<MessageResponse>(
            execute(Request.Builder().url(url("/api/messages/$id")).patch(json(buildJsonObject { put("body", body) })).build()),
        ).message

    suspend fun deleteMessage(id: String) = send("DELETE", "/api/messages/$id")
    suspend fun react(id: String, emoji: String): Message =
        post<MessageResponse>("/api/messages/$id/reactions", buildJsonObject { put("emoji", emoji) }).message

    suspend fun pin(id: String) = send("POST", "/api/messages/$id/pin", buildJsonObject {})
    suspend fun forward(id: String, roomId: String): Message =
        post<MessageResponse>("/api/messages/$id/forward", buildJsonObject { put("roomId", roomId) }).message

    suspend fun markRead(roomId: String) = send("POST", "/api/rooms/$roomId/read", buildJsonObject {})
    suspend fun search(query: String): List<SearchResult> =
        get<SearchResponse>("/api/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")).results

    suspend fun preview(url: String): LinkPreview? =
        post<PreviewResponse>("/api/preview", buildJsonObject { put("url", url) }).preview

    suspend fun upload(roomId: String, bytes: ByteArray, filename: String, mime: String, caption: String = "", envelope: String? = null): Message {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
        if (caption.isNotBlank()) form.addFormDataPart("body", caption)
        if (envelope != null) { form.addFormDataPart("encrypted", "1"); form.addFormDataPart("envelope", envelope) }
        form.addFormDataPart("file", filename, bytes.toRequestBody(mime.toMediaType()))
        val request = Request.Builder().url(url("/api/rooms/$roomId/attachments")).post(form.build()).build()
        // Uploads can be big and slow: no short read/write timeouts, a three minute limit for the whole call.
        val call = client.newBuilder().callTimeout(3, TimeUnit.MINUTES).readTimeout(2, TimeUnit.MINUTES).writeTimeout(2, TimeUnit.MINUTES).build().newCall(request)
        val started = System.currentTimeMillis()
        android.util.Log.i("Upload", "start ${bytes.size} bytes to $roomId")
        call.await().use { res ->
            android.util.Log.i("Upload", "answered ${res.code} after ${System.currentTimeMillis() - started} ms")
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw ApiException(res.code, runCatching { EmberJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: "The upload failed.")
            return EmberJson.decodeFromString<MessageResponse>(text).message
        }
    }

    suspend fun downloadAttachment(id: String): ByteArray {
        val call = client.newCall(Request.Builder().url(url("/api/attachments/$id")).build())
        val res = call.await()
        // A file is more than what is buffered with the headers: reading it must not happen on the main thread.
        return withContext(Dispatchers.IO) {
            res.use {
                if (!it.isSuccessful) throw ApiException(it.code, "Could not download that file.")
                it.body?.bytes() ?: ByteArray(0)
            }
        }
    }

    // ------------------------------------------------------------------ encryption
    suspend fun e2eeRegister(deviceId: String, publicKey: String): DeviceRegistration =
        post("/api/e2ee/device", buildJsonObject { put("deviceId", deviceId); put("publicKey", publicKey) })

    suspend fun e2eeDevices(): List<DeviceDto> = get<DevicesResponse>("/api/e2ee/devices").devices

    suspend fun e2eeWrap(sender: String, recipient: String, keyId: String, wrapped: WrapDto) {
        send(
            "POST", "/api/e2ee/wrap",
            buildJsonObject {
                put("senderDevice", sender); put("recipientDevice", recipient); put("keyId", keyId)
                put("wrapped", buildJsonObject { put("eph", wrapped.eph); put("n", wrapped.n); put("ct", wrapped.ct) })
            },
        )
    }

    // ------------------------------------------------------------------ calls
    suspend fun callToken(roomId: String, device: String): CallToken =
        post("/api/rooms/$roomId/call/token", buildJsonObject { put("device", device) })

    // ------------------------------------------------------------------ invites
    suspend fun createInvite(): String =
        EmberJson.parseToJsonElement(execute(Request.Builder().url(url("/api/invites")).post(json(buildJsonObject {})).build()))
            .jsonObject["code"]!!.jsonPrimitive.content

    // ------------------------------------------------------------------ notifications (native receiver)
    suspend fun registerNotificationDevice(deviceId: String?, label: String): NativeDevice =
        post("/api/native/notifications/register", buildJsonObject { deviceId?.takeIf { it.isNotBlank() }?.let { put("deviceId", it) }; put("label", label) })

    /** Long poll; the server answers within 25 s. Uses the receiver's own bearer token, not the session. */
    suspend fun notificationEvents(token: String, after: Long): NativeEvents {
        val request = Request.Builder().url(url("/api/native/notifications/events?after=$after&wait=25"))
            .header("Authorization", "Bearer $token").get().build()
        val call = client.newBuilder().callTimeout(40, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build().newCall(request)
        call.await().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw ApiException(res.code, "notifications ${res.code}")
            return EmberJson.decodeFromString(text)
        }
    }

    suspend fun ackNotifications(token: String, cursor: Long) {
        val request = Request.Builder().url(url("/api/native/notifications/ack"))
            .header("Authorization", "Bearer $token").post(json(buildJsonObject { put("cursor", cursor) })).build()
        client.newCall(request).await().use { /* nothing to read */ }
    }

    suspend fun revokeNotificationDevice(token: String) {
        val request = Request.Builder().url(url("/api/native/notifications/device"))
            .header("Authorization", "Bearer $token").delete("{}".toRequestBody(JSON_TYPE)).build()
        runCatching { client.newCall(request).await().use { } }
    }
}
