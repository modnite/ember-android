package org.hearth.ember.data

import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.hearth.ember.EmberApp

/**
 * A copy of your conversations to keep: every chat's messages as readable text, and (if asked) the pictures, videos and files in them, in one
 * zip. The messages themselves live on the Hearth server, so this is not a restore point, it is yours to keep if the server is ever gone.
 * With a passphrase each file inside is sealed (see [ArchiveCipher]), and chats that are end-to-end encrypted are written out as readable text
 * only into a sealed archive, never into a plain one.
 */
class HistoryArchive(private val app: EmberApp) {
    /** Larger files than this are left out of the copy (and listed as skipped), so a phone never runs out of memory holding one. */
    private val mediaLimit = 64L * 1024 * 1024

    suspend fun build(file: File, cipher: ArchiveCipher, withMedia: Boolean, progress: (String) -> Unit) {
        val rooms = app.repo.rooms.value
        val sealedOnly = !cipher.encrypted
        val chats = ArrayList<JsonObject>()
        val media = ArrayList<Triple<String, String, String>>() // attachment id, room id, envelope
        val skipped = ArrayList<String>()
        for ((i, room) in rooms.withIndex()) {
            if (room.encrypted && sealedOnly) { skipped.add("${room.title(app.store.userId)}: an end-to-end encrypted chat is not written into a plain archive. Turn encryption on to include it."); continue }
            progress("Reading ${room.title(app.store.userId)} (${i + 1} of ${rooms.size})…")
            val messages = all(room.id)
            chats.add(buildJsonObject {
                put("id", room.id); put("name", room.title(app.store.userId)); put("kind", room.kind); put("encrypted", room.encrypted)
                put("members", buildJsonArray { for (m in room.members) add(JsonPrimitive(m.displayName.ifBlank { m.username })) })
                put("messages", buildJsonArray {
                    for (m in messages) add(buildJsonObject {
                        put("id", m.id); put("from", m.user?.displayName ?: app.repo.userName(m.userId)); put("at", m.createdAt)
                        put("text", if (m.deletedAt != null) "" else app.repo.textOf(room.id, m.body) ?: "")
                        if (m.editedAt != null) put("editedAt", m.editedAt)
                        if (m.deletedAt != null) put("deleted", true)
                        put("attachments", buildJsonArray {
                            for (a in m.attachments) {
                                add(buildJsonObject { put("id", a.id); put("filename", a.filename); put("mime", if (a.encrypted != 0) app.e2ee.mimeOf(a.envelope) ?: a.mime else a.mime); put("size", a.size) })
                                if (withMedia && m.deletedAt == null) media.add(Triple(a.id, room.id, if (a.encrypted != 0) a.envelope else ""))
                            }
                        })
                    })
                })
            })
        }
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("archive.json", cipher.header().toString().toByteArray())
            put("README.txt", readme(cipher.encrypted).toByteArray())
            var done = 0
            for ((id, roomId, envelope) in media) {
                done++
                progress("Saving pictures and files ($done of ${media.size})…")
                val bytes = runCatching {
                    val raw = app.api.downloadAttachment(id)
                    if (envelope.isNotEmpty()) app.e2ee.openFile(roomId, envelope, raw) else raw
                }.getOrNull()
                when {
                    bytes == null -> skipped.add("$id: could not be downloaded")
                    bytes.size > mediaLimit -> skipped.add("$id: larger than ${mediaLimit / 1024 / 1024} MB")
                    else -> put("media/$id", cipher.seal(bytes))
                }
            }
            val index = buildJsonObject { put("chats", JsonArray(chats)); put("skipped", buildJsonArray { skipped.forEach { add(JsonPrimitive(it)) } }); put("madeAt", System.currentTimeMillis()) }
            put("chats.json", cipher.seal(index.toString().toByteArray()))
        }
    }

    /** Every message of a chat, oldest first, fetched a page at a time. */
    private suspend fun all(roomId: String): List<Message> {
        val out = LinkedHashMap<String, Message>()
        var before: Long? = null
        while (true) {
            val page = runCatching { app.api.messages(roomId, before) }.getOrNull().orEmpty()
            if (page.isEmpty()) break
            val oldest = page.minOf { it.createdAt }
            val before0 = before
            for (m in page) out.putIfAbsent(m.id, m)
            if (before0 != null && oldest >= before0) break
            before = oldest
            if (page.size < 50) break
        }
        return out.values.sortedBy { it.createdAt }
    }

    private fun readme(encrypted: Boolean) = buildString {
        appendLine("An Ember copy of your conversations.")
        appendLine()
        appendLine("chats.json   every chat with its messages, as text")
        appendLine("media/       the pictures, videos and files in the chats, named by their id (the names and types are in chats.json)")
        appendLine("archive.json says whether the files are sealed")
        appendLine()
        if (encrypted) {
            appendLine("The files are sealed with your backup passphrase: AES-256-GCM, a key from PBKDF2-SHA256 (rounds and salt are in archive.json),")
            appendLine("and each file is its 12 byte nonce followed by the sealed data. ember-android/tools/open_archive.py opens them.")
        } else appendLine("The files are not sealed: chats.json is plain JSON and the files in media/ are the originals.")
    }
}
