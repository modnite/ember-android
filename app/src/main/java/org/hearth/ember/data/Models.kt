package org.hearth.ember.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the Hearth server calls a person. */
@Serializable
data class User(
    val id: String,
    val username: String = "",
    val displayName: String = "",
    val avatarColor: String = "#7c6cff",
    val avatar: String? = null,
    val wallpaper: String? = null,
    val status: String = "offline",
    val statusMessage: String = "",
) {
    val isOnline get() = status == "online" || status == "away" || status == "busy"
}

@Serializable
data class Attachment(
    val id: String,
    val filename: String = "",
    val mime: String = "",
    val size: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val encrypted: Int = 0,
    val envelope: String = "",
) {
    val isImage get() = mime.startsWith("image/") && encrypted == 0
    val isAudio get() = mime.startsWith("audio/")
    val isVideo get() = mime.startsWith("video/")

    /** A sticker is an ordinary picture whose file name says so (sticker-….webp), so every other client simply shows it as a picture. */
    val isSticker get() = filename.startsWith("sticker-") && (filename.endsWith(".webp") || filename.endsWith(".png") || filename.endsWith(".gif"))
}

@Serializable
data class Reaction(
    @SerialName("user_id") val userId: String,
    val emoji: String,
)

@Serializable
data class MessageQuote(
    val id: String,
    val userId: String = "",
    val body: String = "",
    val deleted: Boolean = false,
)

@Serializable
data class Message(
    val id: String,
    val roomId: String,
    val userId: String,
    val body: String = "",
    val createdAt: Long,
    val editedAt: Long? = null,
    val deletedAt: Long? = null,
    val replyTo: MessageQuote? = null,
    val forwarded: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val reactions: List<Reaction> = emptyList(),
    val user: User? = null,
)

@Serializable
data class RoomRead(
    val userId: String,
    val lastReadAt: Long = 0,
)

@Serializable
data class Room(
    val id: String,
    val kind: String = "group",
    val name: String = "",
    val topic: String = "",
    val avatar: String? = null,
    val encrypted: Boolean = false,
    val createdAt: Long = 0,
    val members: List<User> = emptyList(),
    val lastMessage: Message? = null,
    val unread: Int = 0,
    /** Archived by me: out of the main list and quiet, but the unread count still counts. */
    val archived: Boolean = false,
    val reads: List<RoomRead> = emptyList(),
    val pinned: List<Message> = emptyList(),
) {
    val isDm get() = kind == "dm"

    /** A direct chat is named after the other person; a group after itself. */
    fun title(meId: String): String =
        if (isDm) members.firstOrNull { it.id != meId }?.displayName?.ifBlank { null } ?: name.ifBlank { "Chat" }
        else name.ifBlank { "Group" }

    fun other(meId: String): User? = if (isDm) members.firstOrNull { it.id != meId } else null
}

@Serializable
data class ContactsResponse(
    val contacts: List<String> = emptyList(),
    val incoming: List<User> = emptyList(),
    val outgoing: List<User> = emptyList(),
)

@Serializable
data class UsersResponse(val users: List<User> = emptyList())

@Serializable
data class UserResponse(val user: User)

@Serializable
data class RoomsResponse(val rooms: List<Room> = emptyList())

@Serializable
data class RoomResponse(val room: Room)

@Serializable
data class MessagesResponse(val messages: List<Message> = emptyList())

@Serializable
data class MessageResponse(val message: Message)

@Serializable
data class CallToken(val url: String, val token: String, val room: String = "")

@Serializable
data class LinkPreview(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val image: String? = null,
    val siteName: String? = null,
)

@Serializable
data class PreviewResponse(val preview: LinkPreview? = null)

@Serializable
data class SearchResult(
    val message: Message,
    val room: SearchRoom? = null,
)

@Serializable
data class SearchRoom(val id: String, val kind: String = "", val name: String = "")

@Serializable
data class SearchResponse(val results: List<SearchResult> = emptyList())

@Serializable
data class NativeDevice(
    val deviceId: String,
    val token: String,
    val cursor: Long = 0,
    val expiresAt: Long = 0,
    val userId: String = "",
)

@Serializable
data class PushPayload(
    val title: String = "",
    val body: String = "",
    val roomId: String? = null,
    val url: String? = null,
    val tag: String? = null,
    val renotify: Boolean = false,
    val urgent: Boolean = false,
    val callStartedAt: Long? = null,
)

@Serializable
data class NativeEvent(
    val id: Long,
    val kind: String = "notification",
    val createdAt: Long = 0,
    val expiresAt: Long = 0,
    val payload: PushPayload = PushPayload(),
)

@Serializable
data class NativeEvents(
    val events: List<NativeEvent> = emptyList(),
    val cursor: Long = 0,
)

/** A call someone has going in a room, as the server reports it. */
data class ActiveCall(
    val roomId: String,
    val ringing: Boolean,
    val startedBy: String,
    val startedByName: String,
    val startedAt: Long,
    val participants: List<String>,
)

@Serializable
data class AuthConfig(val registration: String = "invite", val twoStep: Boolean = false)

@Serializable
data class TwoStepStatus(val enabled: Boolean = false, val recoveryLeft: Int = 0)

@Serializable
data class TwoStepSetup(val secret: String, val uri: String)

@Serializable
data class TwoStepRecovery(val recovery: List<String> = emptyList())

/** Someone found by exact username or friend code, or someone on the blocked list. */
@Serializable
data class LookupResult(val user: User? = null, val relation: String = "none")

@Serializable
data class FriendCode(val code: String, val display: String)

@Serializable
data class BlocksResponse(val blocked: List<User> = emptyList())
