package org.hearth.ember.ui

import org.hearth.ember.ui.chat.Wallpapers

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.put

enum class ThemeMode { System, Light, Dark, Black }

/** Small settings that belong to this phone: how it looks, how loud notifications are. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("ember_prefs", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(ThemeMode.entries.getOrElse(p.getInt("theme", 0)) { ThemeMode.System })
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()
    fun setTheme(mode: ThemeMode) {
        p.edit().putInt("theme", mode.ordinal).apply()
        _theme.value = mode
    }

    // Which emoji get used for reactions, and how often: the quick bar always shows the six used most (the usual ones fill the gaps).
    private val usage = LinkedHashMap<String, Int>().also { m ->
        p.getString("emojiUse", "")?.lineSequence()?.forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 2) parts[1].toIntOrNull()?.let { m[parts[0]] = it }
        }
    }
    private fun topSix(): List<String> {
        val used = usage.entries.sortedByDescending { it.value }.map { it.key }.take(6)
        return (used + listOf("👍", "❤️", "😂", "😮", "😢", "🙏")).distinct().take(6)
    }
    private val _quick = MutableStateFlow(topSix())
    val quickReactions: StateFlow<List<String>> = _quick.asStateFlow()
    fun useEmoji(emoji: String) {
        usage[emoji] = (usage[emoji] ?: 0) + 1
        // Keep the list from growing for ever: the 60 most used.
        if (usage.size > 60) usage.entries.sortedBy { it.value }.take(usage.size - 60).forEach { usage.remove(it.key) }
        p.edit().putString("emojiUse", usage.entries.joinToString("\n") { it.key + "\t" + it.value }).apply()
        _quick.value = topSix()
    }

    // Sounds: null is the phone's default; "silent" is none; otherwise the chosen sound's address.
    private val _messageSound = MutableStateFlow(p.getString("messageSound", null))
    val messageSound: StateFlow<String?> = _messageSound.asStateFlow()
    fun setMessageSound(uri: String?) { p.edit().putString("messageSound", uri).apply(); _messageSound.value = uri }
    private val _callSound = MutableStateFlow(p.getString("callSound", null))
    val callSound: StateFlow<String?> = _callSound.asStateFlow()
    fun setCallSound(uri: String?) { p.edit().putString("callSound", uri).apply(); _callSound.value = uri }

    /** Everything on this page of settings, for a backup. */
    fun dump(): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {
        for ((k, v) in p.all) when (v) {
            is Boolean -> put(k, v)
            is Int -> put(k, v)
            is String -> put(k, v)
            is Long -> put(k, v)
        }
    }

    /** Put a backup's settings back, and tell the screens. */
    fun restore(json: kotlinx.serialization.json.JsonObject) {
        val e = p.edit()
        for ((k, v) in json) {
            val prim = (v as? kotlinx.serialization.json.JsonPrimitive) ?: continue
            when {
                prim.isString -> e.putString(k, prim.content)
                prim.content == "true" || prim.content == "false" -> e.putBoolean(k, prim.content == "true")
                else -> prim.content.toIntOrNull()?.let { e.putInt(k, it) }
            }
        }
        e.apply()
        _theme.value = ThemeMode.entries.getOrElse(p.getInt("theme", 0)) { ThemeMode.System }
        _sounds.value = p.getBoolean("sounds", true)
        _accent.value = if (p.contains("accent")) p.getInt("accent", 0) else null
        _notify.value = p.getBoolean("notify", true)
        _preview.value = p.getBoolean("preview", true)
        _enter.value = p.getBoolean("enterSends", false)
        _dynamic.value = p.getBoolean("dynamic", false)
        _messageSound.value = p.getString("messageSound", null)
        _callSound.value = p.getString("callSound", null)
        usage.clear()
        p.getString("emojiUse", "")?.lineSequence()?.forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 2) parts[1].toIntOrNull()?.let { usage[parts[0]] = it }
        }
        _quick.value = topSix()
    }

    /** One switch for the sounds inside a chat (a message arriving, one sent, someone typing). Notification sounds and the ringtone have their own settings. */
    private val _sounds = MutableStateFlow(p.getBoolean("sounds", true))
    val soundsOn: StateFlow<Boolean> = _sounds.asStateFlow()
    fun setSoundsOn(on: Boolean) { p.edit().putBoolean("sounds", on).apply(); _sounds.value = on }

    /** Whether a photo or video is sent in HD unless you pick otherwise for that one (the preview before sending has the choice). */
    private val _sendHd = MutableStateFlow(p.getBoolean("sendHd", false))
    val sendHd: StateFlow<Boolean> = _sendHd.asStateFlow()
    fun setSendHd(on: Boolean) { p.edit().putBoolean("sendHd", on).apply(); _sendHd.value = on }

    /** A small floating nudge button over the chat (the nudge is also in the paperclip menu). */
    private val _nudgeButton = MutableStateFlow(p.getBoolean("nudgeButton", true))
    val nudgeButton: StateFlow<Boolean> = _nudgeButton.asStateFlow()
    fun setNudgeButton(on: Boolean) { p.edit().putBoolean("nudgeButton", on).apply(); _nudgeButton.value = on }

    /**
     * Echo cancelling in calls. Off (the default): the phone's own canceller and noise suppressor, as calls always used. On: WebRTC's software ones, which
     * suit a phone whose own does badly on speakerphone (the other person hears themselves). Read when a call starts.
     */
    private val _softwareEcho = MutableStateFlow(p.getBoolean("softwareEcho", false))
    val softwareEcho: StateFlow<Boolean> = _softwareEcho.asStateFlow()
    fun setSoftwareEcho(on: Boolean) { p.edit().putBoolean("softwareEcho", on).apply(); _softwareEcho.value = on }

    /** The accent colour (an ARGB number), or null for Ember's own orange. */
    private val _accent = MutableStateFlow(if (p.contains("accent")) p.getInt("accent", 0) else null)
    val accent: StateFlow<Int?> = _accent.asStateFlow()
    fun setAccent(argb: Int?) {
        if (argb == null) p.edit().remove("accent").apply() else p.edit().putInt("accent", argb).apply()
        _accent.value = argb
    }

    private val _notify = MutableStateFlow(p.getBoolean("notify", true))
    val notificationsOn: StateFlow<Boolean> = _notify.asStateFlow()
    fun setNotifications(on: Boolean) {
        p.edit().putBoolean("notify", on).apply()
        _notify.value = on
    }

    /**
     * Instant (the default): a connection kept open in the background, which Android makes show one small silent notification. Off: Ember
     * looks for new messages every 15 minutes and shows nothing while it waits (calls do not ring while the app is closed).
     */
    private val _instant = MutableStateFlow(p.getBoolean("instantDelivery", true))
    val instantDelivery: StateFlow<Boolean> = _instant.asStateFlow()
    fun setInstantDelivery(on: Boolean) { p.edit().putBoolean("instantDelivery", on).apply(); _instant.value = on }

    /** The picture behind chats (see Wallpapers): a preset's name or "photo", how much a photo is darkened, and a counter that changes when the photo does. */
    private val _wallpaper = MutableStateFlow(p.getString("wallpaper", Wallpapers.DOODLE) ?: Wallpapers.DOODLE)
    val wallpaper: StateFlow<String> = _wallpaper.asStateFlow()
    fun setWallpaper(v: String) { p.edit().putString("wallpaper", v).apply(); _wallpaper.value = v }
    private val _wallpaperDim = MutableStateFlow(p.getInt("wallpaperDim", 25))
    val wallpaperDim: StateFlow<Int> = _wallpaperDim.asStateFlow()
    fun setWallpaperDim(v: Int) { p.edit().putInt("wallpaperDim", v.coerceIn(0, 70)).apply(); _wallpaperDim.value = v.coerceIn(0, 70) }
    private val _wallpaperVersion = MutableStateFlow(0)
    val wallpaperVersion: StateFlow<Int> = _wallpaperVersion.asStateFlow()
    fun photoChanged() { _wallpaperVersion.value++ }

    private val _preview = MutableStateFlow(p.getBoolean("preview", true))

    /** Show the message text in notifications (off: just "New message"). */
    val previews: StateFlow<Boolean> = _preview.asStateFlow()
    fun setPreviews(on: Boolean) {
        p.edit().putBoolean("preview", on).apply()
        _preview.value = on
    }

    private val _enter = MutableStateFlow(p.getBoolean("enterSends", false))
    val enterSends: StateFlow<Boolean> = _enter.asStateFlow()
    fun setEnterSends(on: Boolean) {
        p.edit().putBoolean("enterSends", on).apply()
        _enter.value = on
    }

    private val _dynamic = MutableStateFlow(p.getBoolean("dynamic", false))

    /** Use the phone's own wallpaper colours (Material You) instead of Ember orange. */
    val dynamicColor: StateFlow<Boolean> = _dynamic.asStateFlow()
    fun setDynamicColor(on: Boolean) {
        p.edit().putBoolean("dynamic", on).apply()
        _dynamic.value = on
    }
}
