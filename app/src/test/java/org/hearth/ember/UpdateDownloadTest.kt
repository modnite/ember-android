package org.hearth.ember

import java.io.File
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.hearth.ember.data.BackupException
import org.hearth.ember.data.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdateDownloadTest {
    @Test fun aReleaseDownloadThatRedirectsIsFollowed() {
        val server = MockWebServer().also { it.start() }
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/storage/Ember.apk").toString()))
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        // The app's own client never follows redirects; the download must still work.
        val appClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        val file = File.createTempFile("ember", ".apk")
        val progress = ArrayList<Float>()
        Updater.fetch(appClient, server.url("/releases/download/v1/Ember.apk").toString(), file, bytes.size.toLong()) { progress.add(it) }
        assertEquals(bytes.size.toLong(), file.length())
        assertTrue(file.readBytes().contentEquals(bytes))
        assertEquals(1.0f, progress.last(), 0.001f)
        server.shutdown()
    }

    @Test fun aFailedDownloadSaysSo() {
        val server = MockWebServer().also { it.start() }
        server.enqueue(MockResponse().setResponseCode(404))
        try { Updater.fetch(OkHttpClient(), server.url("/x.apk").toString(), File.createTempFile("ember", ".apk"), 0) {}; fail() }
        catch (e: BackupException) { assertTrue(e.message!!.contains("404")) }
        server.shutdown()
    }
}
