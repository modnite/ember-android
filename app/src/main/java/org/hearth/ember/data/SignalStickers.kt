package org.hearth.ember.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

/** One sticker pack of the public Signal sticker directory: where it lives (id) and the key that opens it, with the tags people searched it by. */
data class SignalPack(val id: String, val key: String, val tags: List<String>)

class SignalSticker(val id: Int, val emoji: String)

/** What a pack says about itself once opened: its name, who made it and the stickers in it. */
class SignalManifest(val title: String, val author: String, val cover: Int, val stickers: List<SignalSticker>)

/**
 * Signal sticker packs are public but sealed: anyone holding the pack's key (the directory lists it) can fetch and open them, no account and
 * no signup. The key makes two keys with HKDF (an AES key and a MAC key); every file is a 16 byte IV, the AES-CBC data and a 32 byte MAC.
 * Pure Kotlin, so it is tested without a phone.
 */
object SignalFormat {
    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** HKDF-SHA256 with a zero salt and the label "Sticker Pack": 64 bytes, split into the AES key and the MAC key. */
    fun keys(packKey: String): Pair<ByteArray, ByteArray> {
        val prk = hmac(ByteArray(32), hex(packKey))
        val info = "Sticker Pack".toByteArray()
        val t1 = hmac(prk, info, byteArrayOf(1))
        val t2 = hmac(prk, t1, info, byteArrayOf(2))
        return t1 to t2
    }

    /** The clear bytes of a sealed file, or an exception when it was tampered with or the key is wrong. */
    fun open(packKey: String, blob: ByteArray): ByteArray {
        require(blob.size > 16 + 32) { "Too short to be a sealed sticker file." }
        val (aes, macKey) = keys(packKey)
        val body = blob.copyOfRange(0, blob.size - 32)
        val tag = blob.copyOfRange(blob.size - 32, blob.size)
        require(MessageDigest.isEqual(hmac(macKey, body), tag)) { "Sticker file does not match its key." }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), IvParameterSpec(body, 0, 16))
        return cipher.doFinal(body, 16, body.size - 16)
    }

    private class Reader(val b: ByteArray) {
        var i = 0
        fun more() = i < b.size
        fun varint(): Long {
            var shift = 0
            var out = 0L
            while (true) {
                val x = b[i++].toInt() and 0xff
                out = out or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return out
                shift += 7
            }
        }
        fun bytes(): ByteArray { val n = varint().toInt(); val r = b.copyOfRange(i, i + n); i += n; return r }
        fun skip(wire: Int) { when (wire) { 0 -> varint(); 1 -> i += 8; 2 -> bytes(); 5 -> i += 4; else -> i = b.size } }
    }

    private fun sticker(b: ByteArray): SignalSticker {
        val r = Reader(b)
        var id = 0
        var emoji = ""
        while (r.more()) {
            val tag = r.varint().toInt()
            when (tag) { 8 -> id = r.varint().toInt(); 18 -> emoji = String(r.bytes()); else -> r.skip(tag and 7) }
        }
        return SignalSticker(id, emoji)
    }

    /** The pack's description (protobuf: title 1, author 2, cover 3, stickers 4). */
    fun manifest(clear: ByteArray): SignalManifest {
        val r = Reader(clear)
        var title = ""
        var author = ""
        var cover = 0
        val stickers = ArrayList<SignalSticker>()
        while (r.more()) {
            val tag = r.varint().toInt()
            when (tag) {
                10 -> title = String(r.bytes())
                18 -> author = String(r.bytes())
                26 -> cover = sticker(r.bytes()).id
                34 -> stickers.add(sticker(r.bytes()))
                else -> r.skip(tag and 7)
            }
        }
        return SignalManifest(title, author, cover, stickers)
    }

    /** The directory's YAML: an entry per pack (32 hex id, its key, tags), without the ones marked not safe for work. Only the little this needs is read. */
    fun directory(yaml: String): List<SignalPack> {
        val out = ArrayList<SignalPack>()
        var id: String? = null
        var key: String? = null
        var tags = ArrayList<String>()
        var nsfw = false
        var inTags = false
        fun flush() {
            val i = id
            val k = key
            if (i != null && k != null && !nsfw && tags.none { it.equals("nsfw", true) }) out.add(SignalPack(i, k, tags))
            id = null; key = null; tags = ArrayList(); nsfw = false; inTags = false
        }
        val header = Regex("^([0-9a-f]{32}):\\s*$")
        for (line in yaml.lineSequence()) {
            val start = header.find(line)
            if (start != null) { flush(); id = start.groupValues[1]; continue }
            val t = line.trim()
            when {
                t.startsWith("key:") -> { key = t.removePrefix("key:").trim().trim('\'', '"').takeIf { it.length == 64 }; inTags = false }
                t.startsWith("nsfw:") -> { nsfw = t.removePrefix("nsfw:").trim().equals("true", true); inTags = false }
                t.startsWith("tags:") -> inTags = true
                inTags && t.startsWith("- ") -> tags.add(t.removePrefix("- ").trim().trim('\'', '"'))
                t.isNotEmpty() && !t.startsWith("#") -> inTags = false
            }
        }
        flush()
        return out
    }

    /** Packs whose tags match every word of [query], the best matches first. An empty query gives the newest. */
    fun search(packs: List<SignalPack>, query: String, limit: Int = 40): List<SignalPack> {
        val words = query.lowercase().split(' ', ',').filter { it.isNotBlank() }
        if (words.isEmpty()) return packs.take(limit)
        return packs.mapNotNull { p ->
            val tags = p.tags.map { it.lowercase() }
            var score = 0
            for (w in words) {
                val s = when { tags.any { it == w } -> 3; tags.any { it.contains(w) } -> 1; else -> return@mapNotNull null }
                score += s
            }
            p to score
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    /** What a sticker file is, from its first bytes. */
    fun extension(clear: ByteArray): String = when {
        clear.size > 12 && String(clear, 0, 4, Charsets.US_ASCII) == "RIFF" && String(clear, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        clear.size > 4 && clear[0] == 0x89.toByte() && clear[1] == 'P'.code.toByte() -> "png"
        else -> "webp"
    }
}

/** Fetches the directory, packs and stickers from Signal's public servers and keeps what it opened on disk. */
class SignalStickers(context: Context, private val client: OkHttpClient) {
    private val root = File(context.cacheDir, "signal").also { it.mkdirs() }
    private val plain = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
    private var packs: List<SignalPack>? = null
    private val manifests = HashMap<String, SignalManifest>()

    private fun get(url: String): ByteArray = plain.newCall(Request.Builder().url(url).header("User-Agent", "Ember (Hearth messenger)").build()).execute().use { r ->
        if (!r.isSuccessful) throw OnlineException("Signal's server answered ${r.code}.")
        r.body?.bytes() ?: throw OnlineException("Signal's server sent nothing.")
    }

    /** The public directory of packs, from GitHub; refreshed about once a week. */
    private suspend fun directory(): List<SignalPack> = withContext(Dispatchers.IO) {
        packs ?: run {
            val file = File(root, "directory.yml")
            if (!file.exists() || System.currentTimeMillis() - file.lastModified() > 7L * 24 * 3600 * 1000) {
                runCatching { file.writeBytes(get(DIRECTORY_URL)) }.onFailure { if (!file.exists()) throw OnlineException("Could not load the list of sticker packs.") }
            }
            SignalFormat.directory(file.readText()).also { packs = it }
        }
    }

    suspend fun search(query: String): List<SignalPack> = SignalFormat.search(directory(), query)

    suspend fun manifest(pack: SignalPack): SignalManifest = withContext(Dispatchers.IO) {
        manifests[pack.id] ?: run {
            val file = File(root, "${pack.id}.manifest")
            val clear = if (file.exists()) file.readBytes() else SignalFormat.open(pack.key, get("$CDN/${pack.id}/manifest.proto")).also { file.writeBytes(it) }
            SignalFormat.manifest(clear).also { manifests[pack.id] = it }
        }
    }

    /** One sticker of a pack, opened. */
    suspend fun sticker(pack: SignalPack, id: Int): ByteArray = withContext(Dispatchers.IO) {
        val file = File(root, "${pack.id}-$id")
        if (file.exists()) file.readBytes() else SignalFormat.open(pack.key, get("$CDN/${pack.id}/full/$id")).also { file.writeBytes(it) }
    }

    companion object {
        const val CDN = "https://cdn-ca.signal.org/stickers"
        const val DIRECTORY_URL = "https://raw.githubusercontent.com/signalstickers/stickers/master/src/stickers.yml"
    }
}
