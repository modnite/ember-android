package org.hearth.ember.data

import android.content.Context
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.hearth.ember.R

enum class MediaKind { Gif, Sticker }

/**
 * One result of an online search. [previewUrl] is what the grid draws (small, may be null when [glyph] is drawn instead), [sendUrl] the
 * file that is downloaded and sent when it is picked. [ext] says what that file is (gif or webp).
 */
data class OnlineItem(val id: String, val previewUrl: String?, val sendUrl: String, val ext: String, val title: String = "", val glyph: String? = null)

class OnlineException(message: String) : Exception(message)

/** The keys for the services that need one, and where each service lives (changeable so a test server can stand in). */
class OnlineSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ember_online", Context.MODE_PRIVATE)
    var giphyKey: String
        get() = prefs.getString("giphyKey", "").orEmpty()
        set(v) = prefs.edit().putString("giphyKey", v.trim()).apply()
    var imgurId: String
        get() = prefs.getString("imgurId", "").orEmpty()
        set(v) = prefs.edit().putString("imgurId", v.trim()).apply()
    val giphyBase get() = prefs.getString("giphyBase", "https://api.giphy.com/v1").orEmpty()
    val imgurBase get() = prefs.getString("imgurBase", "https://api.imgur.com/3").orEmpty()
    val commonsBase get() = prefs.getString("commonsBase", "https://commons.wikimedia.org/w/api.php").orEmpty()
}

/** A place to search for GIFs or stickers. */
interface OnlineSource {
    val id: String
    val label: String
    val kinds: Set<MediaKind>

    /** False while a key is missing: the picker then explains where to get one. */
    fun ready(): Boolean

    /** An empty [query] gives what is popular. */
    suspend fun search(kind: MediaKind, query: String): List<OnlineItem>
}

/** The JSON answers of each service turned into [OnlineItem]s. Free of Android, so tested on their own. */
object OnlineParsing {
    private val parser = EmberJson

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.text(): String? = (this as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonObject.number(key: String): Long = this[key].text()?.toLongOrNull() ?: 0L

    /** Giphy's `data` list. A sticker is the animated WebP (small, with see-through parts); a GIF is the medium sized GIF. */
    fun giphy(json: String, kind: MediaKind): List<OnlineItem> {
        val data = runCatching { parser.parseToJsonElement(json).jsonObject["data"] as? JsonArray }.getOrNull() ?: return emptyList()
        return data.mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val id = o["id"].text() ?: return@mapNotNull null
            val images = o["images"].obj() ?: return@mapNotNull null
            val title = o["title"].text().orEmpty()
            if (kind == MediaKind.Sticker) {
                val send = images["fixed_height"].obj()?.get("webp").text() ?: return@mapNotNull null
                val preview = images["fixed_width_small"].obj()?.get("webp").text() ?: images["fixed_width"].obj()?.get("webp").text() ?: send
                OnlineItem(id, preview, send, "webp", title)
            } else {
                val send = images["downsized_medium"].obj()?.get("url").text() ?: images["fixed_height"].obj()?.get("url").text() ?: images["original"].obj()?.get("url").text()
                    ?: return@mapNotNull null
                val preview = images["fixed_width_small"].obj()?.get("url").text() ?: images["fixed_width"].obj()?.get("url").text() ?: send
                OnlineItem(id, preview, send, "gif", title)
            }
        }
    }

    /** Imgur's gallery answer: every entry is a post or an album; the animated GIFs inside are the results, nothing over a size a chat should carry. */
    fun imgur(json: String, maxBytes: Long = 8L * 1024 * 1024): List<OnlineItem> {
        val data = runCatching { parser.parseToJsonElement(json).jsonObject["data"] as? JsonArray }.getOrNull() ?: return emptyList()
        val out = ArrayList<OnlineItem>()
        val seen = HashSet<String>()
        for (e in data) {
            val post = e.obj() ?: continue
            val candidates = buildList {
                add(post)
                (post["images"] as? JsonArray)?.forEach { it.obj()?.let(::add) }
            }
            for (c in candidates) {
                val link = c["link"].text() ?: continue
                val isGif = c["type"].text() == "image/gif" || link.endsWith(".gif", ignoreCase = true)
                if (!isGif || c["animated"].text() == "false") continue
                val size = c.number("size")
                if (size > maxBytes) continue
                val id = c["id"].text() ?: link
                if (!seen.add(id)) continue
                val secure = if (link.startsWith("http://i.imgur.com/") || link.startsWith("http://imgur.com/")) link.replaceFirst("http://", "https://") else link
                out.add(OnlineItem(id, secure, secure, "gif", c["title"].text() ?: post["title"].text().orEmpty()))
            }
        }
        return out
    }

    /** Wikimedia Commons: files whose type is GIF; the thumbnail is the picture itself scaled down. */
    fun commons(json: String, maxBytes: Long = 8L * 1024 * 1024): List<OnlineItem> {
        val pages = runCatching { parser.parseToJsonElement(json).jsonObject["query"].obj()?.get("pages").obj() }.getOrNull() ?: return emptyList()
        return pages.values.mapNotNull { p ->
            val page = p.obj() ?: return@mapNotNull null
            val info = (page["imageinfo"] as? JsonArray)?.firstOrNull().obj() ?: return@mapNotNull null
            if (info["mime"].text() != "image/gif" || info.number("size") > maxBytes) return@mapNotNull null
            val url = info["url"].text() ?: return@mapNotNull null
            val title = page["title"].text().orEmpty().removePrefix("File:").removeSuffix(".gif")
            OnlineItem(page["pageid"].text() ?: url, info["thumburl"].text() ?: url, url, "gif", title)
        }.sortedBy { it.title }
    }

    /** The animated emoji list: `code<TAB>unicode name` per line. */
    fun animatedEmoji(lines: List<String>): List<Pair<String, String>> = lines.mapNotNull { l ->
        val parts = l.split('\t', limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
    }

    /** The emoji itself from its file code: "2764_fe0f" is the heart, "1f600" the grin. */
    fun glyph(code: String): String = code.split('_').joinToString("") { String(Character.toChars(it.toInt(16))) }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

private fun OkHttpClient.fetch(request: Request): String =
    newBuilder().cookieJar(CookieJar.NO_COOKIES).build().newCall(request).execute().use { r ->
        if (r.code == 401 || r.code == 403) throw OnlineException("The service refused the key. Check it in Settings.")
        if (r.code == 429) throw OnlineException("Too many searches. Try again in a minute.")
        if (!r.isSuccessful) throw OnlineException("The service answered ${r.code}.")
        r.body?.string().orEmpty()
    }

private class GiphySource(private val client: OkHttpClient, private val settings: OnlineSettings) : OnlineSource {
    override val id = "giphy"
    override val label = "Giphy"
    override val kinds = setOf(MediaKind.Gif, MediaKind.Sticker)
    override fun ready() = settings.giphyKey.isNotBlank()
    override suspend fun search(kind: MediaKind, query: String): List<OnlineItem> = withContext(Dispatchers.IO) {
        val path = if (kind == MediaKind.Sticker) "stickers" else "gifs"
        val key = enc(settings.giphyKey)
        val url = if (query.isBlank()) "${settings.giphyBase}/$path/trending?api_key=$key&limit=36&rating=pg-13"
        else "${settings.giphyBase}/$path/search?api_key=$key&q=${enc(query)}&limit=36&rating=pg-13&lang=en"
        OnlineParsing.giphy(client.fetch(Request.Builder().url(url).build()), kind)
    }
}

private class ImgurSource(private val client: OkHttpClient, private val settings: OnlineSettings) : OnlineSource {
    override val id = "imgur"
    override val label = "Imgur"
    override val kinds = setOf(MediaKind.Gif)
    override fun ready() = settings.imgurId.isNotBlank()
    override suspend fun search(kind: MediaKind, query: String): List<OnlineItem> = withContext(Dispatchers.IO) {
        val q = query.ifBlank { "reaction" }
        val request = Request.Builder().url("${settings.imgurBase}/gallery/search/viral/all/0?q=${enc(q)}&q_type=gif")
            .header("Authorization", "Client-ID " + settings.imgurId).build()
        OnlineParsing.imgur(client.fetch(request))
    }
}

private class CommonsSource(private val client: OkHttpClient, private val settings: OnlineSettings) : OnlineSource {
    override val id = "commons"
    override val label = "Wikimedia"
    override val kinds = setOf(MediaKind.Gif)
    override fun ready() = true
    override suspend fun search(kind: MediaKind, query: String): List<OnlineItem> = withContext(Dispatchers.IO) {
        val q = query.ifBlank { "animated" }
        val url = "${settings.commonsBase}?action=query&generator=search&gsrsearch=${enc("filetype:bitmap $q gif")}&gsrnamespace=6&gsrlimit=36" +
            "&prop=imageinfo&iiprop=url%7Cmime%7Csize&iiurlwidth=240&format=json&origin=*"
        OnlineParsing.commons(client.fetch(Request.Builder().url(url).header("User-Agent", "Ember/0.2 (Hearth messenger)").build()))
    }
}

/** Google's animated Noto emoji: no key, always on. The grid shows the phone's own emoji; the animation is fetched when one is sent. */
private class AnimatedEmojiSource(private val context: Context) : OnlineSource {
    override val id = "emoji"
    override val label = "Animated emoji"
    override val kinds = setOf(MediaKind.Sticker)
    override fun ready() = true

    private val all: List<Pair<String, String>> by lazy {
        val lines = context.resources.openRawResource(R.raw.animated_emoji).bufferedReader().readLines()
        // Faces and gestures first, the rest by code.
        OnlineParsing.animatedEmoji(lines).sortedBy { (code, _) -> if (code.startsWith("1f6") && code.length == 5 && code[3] <= '4') 0 else 1 }
    }

    override suspend fun search(kind: MediaKind, query: String): List<OnlineItem> {
        val q = query.trim().lowercase()
        return all.filter { q.isEmpty() || it.second.contains(q) || OnlineParsing.glyph(it.first) == q }.take(120).map { (code, name) ->
            OnlineItem(code, null, "https://fonts.gstatic.com/s/e/notoemoji/latest/$code/512.webp", "webp", name, OnlineParsing.glyph(code))
        }
    }
}

/** Every place that can be searched, and the download of a chosen result. */
class OnlineMedia(context: Context, private val client: OkHttpClient) {
    val settings = OnlineSettings(context)
    val sources: List<OnlineSource> = listOf(GiphySource(client, settings), ImgurSource(client, settings), CommonsSource(client, settings), AnimatedEmojiSource(context))

    fun sourcesFor(kind: MediaKind) = sources.filter { kind in it.kinds }

    /** The picture to send or keep, as bytes. Refuses anything bigger than a chat should carry. */
    suspend fun download(item: OnlineItem, maxBytes: Long = 12L * 1024 * 1024): ByteArray = withContext(Dispatchers.IO) {
        client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build().newCall(Request.Builder().url(item.sendUrl).header("User-Agent", "Ember/0.2 (Hearth messenger)").build()).execute().use { r ->
            if (!r.isSuccessful) throw OnlineException("Could not download that (${r.code}).")
            val body = r.body ?: throw OnlineException("Could not download that.")
            if (body.contentLength() > maxBytes) throw OnlineException("That one is too big to send.")
            val bytes = body.bytes()
            if (bytes.size > maxBytes) throw OnlineException("That one is too big to send.")
            bytes
        }
    }

    companion object {
        /** What the sent file is called: a sticker keeps the sticker- prefix every client knows. */
        fun fileName(item: OnlineItem, kind: MediaKind): String {
            val safe = item.id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(40).ifEmpty { "x" }
            return (if (kind == MediaKind.Sticker) "sticker-" else "gif-") + safe + "." + item.ext
        }

        fun mime(item: OnlineItem) = if (item.ext == "webp") "image/webp" else "image/gif"
    }
}
