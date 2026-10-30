package org.hearth.ember.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.UUID

/**
 * Everything the app must remember about being signed in: which server, the session cookie and the notification device. Kept in
 * encrypted preferences, so a copied backup or a rooted peek does not hand over the session.
 */
class SessionStore(context: Context) {
    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "ember_session", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (_: Exception) {
        // Keystore trouble (a restored backup on a new phone): start clean rather than crash.
        context.deleteSharedPreferences("ember_session")
        context.getSharedPreferences("ember_session_plain", Context.MODE_PRIVATE)
    }

    /** Has this install already been asked whether to restore a backup after the first sign-in? Asked once. */
    var restoreOffered: Boolean
        get() = prefs.getBoolean("restoreOffered", false)
        set(v) = prefs.edit().putBoolean("restoreOffered", v).apply()

    var server: String
        get() = prefs.getString("server", "") ?: ""
        set(v) = prefs.edit().putString("server", normalize(v)).apply()

    var userId: String
        get() = prefs.getString("userId", "") ?: ""
        set(v) = prefs.edit().putString("userId", v).apply()

    var cookie: String
        get() = prefs.getString("cookie", "") ?: ""
        set(v) = prefs.edit().putString("cookie", v).apply()

    /** Stable id of this install, sent as the call/socket "device" so two phones never displace each other. */
    val deviceId: String
        get() = prefs.getString("deviceId", null) ?: ("ember-" + UUID.randomUUID().toString().take(8)).also {
            prefs.edit().putString("deviceId", it).apply()
        }

    /** This phone's end-to-end encryption identity (a private X25519 key). It stays on the phone, and stays across sign-outs. */
    var e2eeSecret: String
        get() = prefs.getString("e2eeSecret", "") ?: ""
        set(v) = prefs.edit().putString("e2eeSecret", v).apply()

    /** Where backups go on a WebDAV or Nextcloud server (the password is kept in the same encrypted store as the sign-in). */
    var webdavUrl: String
        get() = prefs.getString("webdavUrl", "") ?: ""
        set(v) = prefs.edit().putString("webdavUrl", v.trim()).apply()
    var webdavUser: String
        get() = prefs.getString("webdavUser", "") ?: ""
        set(v) = prefs.edit().putString("webdavUser", v.trim()).apply()
    var webdavPass: String
        get() = prefs.getString("webdavPass", "") ?: ""
        set(v) = prefs.edit().putString("webdavPass", v).apply()

    var notifyDeviceId: String
        get() = prefs.getString("notifyDeviceId", "") ?: ""
        set(v) = prefs.edit().putString("notifyDeviceId", v).apply()

    var notifyToken: String
        get() = prefs.getString("notifyToken", "") ?: ""
        set(v) = prefs.edit().putString("notifyToken", v).apply()

    var notifyCursor: Long
        get() = prefs.getLong("notifyCursor", 0)
        set(v) = prefs.edit().putLong("notifyCursor", v).apply()

    val signedIn get() = server.isNotEmpty() && cookie.isNotEmpty() && userId.isNotEmpty()

    fun signOut() {
        val id = deviceId
        val secret = e2eeSecret
        prefs.edit().clear().putString("deviceId", id).putString("server", server).putString("e2eeSecret", secret).apply()
    }

    companion object {
        /** "chat.example.com" and "https://chat.example.com/" both mean the same server. */
        fun normalize(input: String): String {
            var s = input.trim()
            if (s.isEmpty()) return ""
            if (!s.contains("://")) s = "https://$s"
            // A real server redirects http to https, and a sign-in cannot follow that redirect ("Moved Permanently"), so only machines on
            // the local network keep plain http.
            if (s.startsWith("http://", ignoreCase = true)) {
                val host = s.substring(7).substringBefore('/').substringBefore(':').lowercase()
                if (!isLocalHost(host)) s = "https://" + s.substring(7)
            }
            return s.trimEnd('/')
        }

        fun isLocalHost(host: String): Boolean =
            host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".test") || host.endsWith(".localhost") ||
                Regex("""^(127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.).*""").matches(host) || host == "[::1]"
    }
}

/** Keeps the session cookie across restarts. The server sets one cookie, `hearth_session`. */
class PersistentCookieJar(private val store: SessionStore) : CookieJar {
    @Volatile private var memory: Cookie? = null

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            if (c.name != "hearth_session") continue
            if (c.expiresAt < System.currentTimeMillis()) {
                memory = null
                store.cookie = ""
            } else {
                memory = c
                store.cookie = c.value
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val value = memory?.value ?: store.cookie
        if (value.isEmpty()) return emptyList()
        return listOf(Cookie.Builder().name("hearth_session").value(value).domain(url.host).path("/").build())
    }

    fun clear() {
        memory = null
        store.cookie = ""
    }
}
