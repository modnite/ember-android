package org.hearth.ember

import org.hearth.ember.data.Versions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test fun versionsCompareNumberByNumber() {
        assertTrue(Versions.isNewer("0.2.0", "0.1.0"))
        assertTrue(Versions.isNewer("v0.10.0", "0.9.5"))
        assertTrue(Versions.isNewer("1.0", "0.9.9"))
        assertFalse(Versions.isNewer("0.1.0", "0.1.0"))
        assertFalse(Versions.isNewer("0.1.0", "0.1.0-debug"))
        assertFalse(Versions.isNewer("0.1", "0.1.0"))
    }

    @Test fun theLatestReleaseAnswerIsReadForItsApk() {
        val json = """{"tag_name":"v0.2.0","body":"Faster history.","assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":10},
            {"name":"Ember-0.2.0.apk","browser_download_url":"https://github.com/o/r/releases/download/v0.2.0/Ember-0.2.0.apk","size":41000000}]}"""
        val info = Versions.release(json)!!
        assertEquals("0.2.0", info.version)
        assertEquals("Faster history.", info.notes)
        assertTrue(info.apkUrl.endsWith("Ember-0.2.0.apk"))
        assertEquals(41000000L, info.size)
    }

    @Test fun aReleaseWithoutAnApkOrAnythingElseGivesNothing() {
        assertNull(Versions.release("""{"tag_name":"v0.3.0","assets":[]}"""))
        assertNull(Versions.release("not json"))
        assertNull(Versions.release("""{"message":"Not Found"}"""))
    }
}
