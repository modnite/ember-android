package org.hearth.ember.reader

import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Getting the backup key the way Ember's phone does: sign in to the Hearth server that the backups belong to, and ask it for this account's key.
 * The server only gives it to the signed-in owner. Nothing is kept: not the password, not the key.
 */
object Account {
    /** "chat.example.com" and "https://chat.example.com/" mean the same server; plain http is only kept for machines on the local network. */
    fun normalize(input: String): String {
        var s = input.trim()
        if (s.isEmpty()) return ""
        if (!s.contains("://")) s = "https://$s"
        if (s.startsWith("http://", ignoreCase = true)) {
            val host = s.substring(7).substringBefore('/').substringBefore(':').lowercase()
            val local = host == "localhost" || host == "127.0.0.1" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".test") ||
                host.startsWith("192.168.") || host.startsWith("10.") || Regex("""172\.(1[6-9]|2\d|3[01])\..*""").matches(host)
            if (!local) s = "https://" + s.substring(7)
        }
        return s.trimEnd('/')
    }

    fun fetchKey(server: String, username: String, password: String): AccountKey {
        val base = normalize(server)
        if (base.isEmpty()) throw ReaderException("Enter the server address.")
        if (username.isBlank() || password.isEmpty()) throw ReaderException("Enter your username and password.")
        val client = HttpClient.newBuilder().cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build()
        val login = HttpRequest.newBuilder(URI("$base/api/auth/login")).timeout(Duration.ofSeconds(20)).header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(buildJsonObject { put("username", username.trim()); put("password", password) }.toString())).build()
        val signedIn = send(client, login)
        when (signedIn.statusCode()) {
            200 -> {}
            401 -> throw ReaderException("Wrong username or password.")
            429 -> throw ReaderException("Too many tries. Wait a little and try again.")
            else -> throw ReaderException("The server answered ${signedIn.statusCode()}.")
        }
        val response = send(client, HttpRequest.newBuilder(URI("$base/api/backup/key")).timeout(Duration.ofSeconds(20)).GET().build())
        if (response.statusCode() == 404) throw ReaderException("That server is too old to hold backup keys. Update Hearth, or use the recovery key from Ember.")
        if (response.statusCode() != 200) throw ReaderException("The server would not give the backup key (${response.statusCode()}).")
        val o = Json.parseToJsonElement(response.body()).jsonObject
        val bytes = Base64.getDecoder().decode(o["key"]!!.jsonPrimitive.content)
        return AccountKey(bytes, o["id"]?.jsonPrimitive?.content ?: AccountKey.fingerprint(bytes))
    }

    private fun send(client: HttpClient, request: HttpRequest): HttpResponse<String> = try {
        client.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (e: java.io.IOException) {
        throw ReaderException("Could not reach the server. Check the address and your connection.")
    }
}
