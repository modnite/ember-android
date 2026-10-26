package org.hearth.ember

import org.hearth.ember.data.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerAddressTest {
    @Test fun bareNameGetsHttps() = assertEquals("https://call.example.com", SessionStore.normalize("call.example.com/"))
    @Test fun httpToARealServerIsUpgraded() = assertEquals("https://call.example.com", SessionStore.normalize("http://call.example.com"))
    @Test fun localMachinesKeepHttp() {
        assertEquals("http://localhost:18200", SessionStore.normalize("http://localhost:18200"))
        assertEquals("http://192.168.1.20:8080", SessionStore.normalize("http://192.168.1.20:8080"))
        assertEquals("http://hearth.local", SessionStore.normalize("http://hearth.local"))
    }
}
