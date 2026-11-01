package org.hearth.ember.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * A server can have mirrors: other servers that hold a copy and take over by themselves when the main one is gone. The server tells its apps
 * who is in the group; this watches the one the app is using, moves to the first mirror that is serving when it stops answering, and moves
 * back to the main server once that is serving again. The sign-in comes along as it is: the server keeps a copy of the sessions on every mirror.
 */
class Failover(
    private val store: SessionStore,
    client: OkHttpClient,
    private val scope: CoroutineScope,
    /** The server in use changed: reconnect and reload from the new one. */
    private val moved: suspend () -> Unit,
) {
    private class Info(val writable: Boolean?, val nodes: List<Pair<String, Int>>)

    private val http = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).callTimeout(4, TimeUnit.SECONDS).build()
    private var job: Job? = null
    private var fails = 0

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(8_000)
                if (store.signedIn) runCatching { tick() }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private suspend fun info(base: String): Info? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$base/api/server/info").build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val cluster = EmberJson.parseToJsonElement(r.body?.string().orEmpty()).jsonObject["cluster"] as? JsonObject
                    ?: return@use Info(null, emptyList())
                val nodes = (cluster["nodes"] as? JsonArray).orEmpty().map { it.jsonObject["url"]!!.jsonPrimitive.content.trimEnd('/') to it.jsonObject["rank"]!!.jsonPrimitive.int }
                Info(cluster["writable"]?.jsonPrimitive?.boolean, nodes)
            }
        }.getOrNull()
    }

    private suspend fun tick() {
        val here = store.server.trimEnd('/')
        val mine = info(here)
        if (mine != null && mine.nodes.size >= 2) store.group = mine.nodes.sortedBy { it.second }.joinToString("\n") { it.first }
        val nodes = store.group.split('\n').filter { it.isNotBlank() }
        if (nodes.size < 2) return
        if (mine != null && mine.writable != false) {
            fails = 0
            // On a mirror that is standing in: go back as soon as the main server is serving again.
            val main = nodes.first()
            if (main != here) {
                val m = info(main)
                if (m != null && m.writable != false) moveTo(main)
            }
            return
        }
        fails += 1
        if (fails < 2) return
        for (n in nodes) {
            if (n == here) continue
            val i = info(n)
            if (i != null && i.writable != false) {
                moveTo(n)
                return
            }
        }
    }

    private suspend fun moveTo(server: String) {
        fails = 0
        store.server = server
        moved()
    }
}
