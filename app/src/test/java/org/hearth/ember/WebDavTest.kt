package org.hearth.ember

import java.util.Base64
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.hearth.ember.data.BackupException
import org.hearth.ember.data.BackupFormat
import org.hearth.ember.data.WebDav
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** A stand-in for a WebDAV folder (PUT, PROPFIND, GET, basic sign-in), so the real requests are exercised. */
class WebDavTest {
    private val files = LinkedHashMap<String, ByteArray>()
    private val server = MockWebServer()
    private val good = "Basic " + Base64.getEncoder().encodeToString("nina:secret".toByteArray())

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != good) return MockResponse().setResponseCode(401)
                val name = request.path!!.substringAfterLast('/')
                return when (request.method) {
                    "PUT" -> { files[name] = request.body.readByteArray(); MockResponse().setResponseCode(201) }
                    "PROPFIND" -> MockResponse().setResponseCode(207).setBody(
                        "<d:multistatus>" + files.keys.joinToString("") { "<d:response><d:href>/dav/$it</d:href></d:response>" } + "</d:multistatus>",
                    )
                    "GET" -> files[name]?.let { MockResponse().setBody(Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun dav(user: String = "nina", pass: String = "secret") = WebDav(OkHttpClient(), server.url("/dav").toString(), user, pass)

    @Test fun backupGoesUpAndTheNewestComesBack() {
        dav().upload("ember-backup-20261025-0900.embkup", BackupFormat.seal("""{"a":1}""", null))
        dav().upload("ember-backup-20261025-1030.embkup", BackupFormat.seal("""{"a":2}""", "pw"))
        assertEquals("""{"a":2}""", BackupFormat.open(dav().latest(), "pw"))
    }

    @Test fun wrongPasswordSaysSo() {
        try { dav(pass = "nope").upload("ember-backup-20261025-0900.embkup", ByteArray(1)); fail() } catch (e: BackupException) { assertTrue(e.message!!.contains("username and password")) }
    }

    @Test fun emptyFolderSaysSo() {
        try { dav().latest(); fail() } catch (e: BackupException) { assertTrue(e.message!!.contains("No Ember backup")) }
    }
}
