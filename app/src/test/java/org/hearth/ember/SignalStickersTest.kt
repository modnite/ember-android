package org.hearth.ember

import org.hearth.ember.data.OnlineParsing
import org.hearth.ember.data.SignalFormat
import org.hearth.ember.data.SignalPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A real pack from Signal's servers (manifest and its first sticker, as downloaded) and the key the public directory lists for it. */
class SignalStickersTest {
    private val key = "1111b7791c629c620ad00d798681412cefad7b16e8dc355ed9ded428ab68bac2"
    private fun resource(name: String) = javaClass.getResourceAsStream("/signal/$name")!!.readBytes()

    @Test fun opensARealManifest() {
        val m = SignalFormat.manifest(SignalFormat.open(key, resource("manifest.bin")))
        assertTrue(m.title.isNotBlank())
        assertTrue(m.stickers.isNotEmpty())
        assertTrue(m.stickers.any { it.id == m.cover })
    }

    @Test fun opensARealStickerAndRecognisesItsKind() {
        val clear = SignalFormat.open(key, resource("sticker0.bin"))
        assertTrue(clear.size > 100)
        assertTrue(SignalFormat.extension(clear) in listOf("webp", "png"))
    }

    @Test fun refusesTheWrongKeyOrATamperedFile() {
        try { SignalFormat.open("00".repeat(32), resource("manifest.bin")); fail() } catch (e: IllegalArgumentException) { }
        val broken = resource("manifest.bin").also { it[20] = (it[20] + 1).toByte() }
        try { SignalFormat.open(key, broken); fail() } catch (e: IllegalArgumentException) { }
    }

    private val yaml = """
        ---
        # comment
        038d31e33e5626779c8dd98e5aa99ac1:
          key: 1111b7791c629c620ad00d798681412cefad7b16e8dc355ed9ded428ab68bac2
          tags:
            - 'Scooby'
            - 'Dog'
          source: 'https://t.me/addstickers/stickers4_scoob'

        9d2e2d3f778763bcbe16453a18a2899a:
          key: 03aa3110f878c154d5930630fd73d67d1efb79841572bc79e4b50afa5e1b56ee
          nsfw: true
          tags:
            - 'Cat'

        aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:
          key: 03aa3110f878c154d5930630fd73d67d1efb79841572bc79e4b50afa5e1b56ee
          tags:
            - 'Cat'
            - 'nsfw'

        bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb:
          key: 03aa3110f878c154d5930630fd73d67d1efb79841572bc79e4b50afa5e1b56ee
          tags:
            - "Cat"
            - 'Funny Cat'
    """.trimIndent()

    @Test fun readsTheDirectoryAndLeavesOutAdultPacks() {
        val packs = SignalFormat.directory(yaml)
        assertEquals(listOf("038d31e33e5626779c8dd98e5aa99ac1", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), packs.map { it.id })
        assertEquals(listOf("Scooby", "Dog"), packs[0].tags)
        assertEquals(listOf("Cat", "Funny Cat"), packs[1].tags)
    }

    @Test fun searchesByTagBestFirst() {
        val packs = listOf(SignalPack("a", "k", listOf("Dog", "Cartoon")), SignalPack("b", "k", listOf("Funny Cat")), SignalPack("c", "k", listOf("Cat")))
        assertEquals(listOf("c", "b"), SignalFormat.search(packs, "cat").map { it.id })
        assertEquals(listOf("a"), SignalFormat.search(packs, "dog cartoon").map { it.id })
        assertEquals(3, SignalFormat.search(packs, "").size)
        assertEquals(emptyList<SignalPack>(), SignalFormat.search(packs, "zebra"))
    }

    @Test fun nekosReactionsBecomeGifs() {
        val json = """{"results":[{"anime_name":"Noragami","url":"https://nekos.best/api/v2/hug/c08992e3.gif","dimensions":{"width":498,"height":258}}]}"""
        val items = OnlineParsing.nekos(json, "hug")
        assertEquals("c08992e3", items[0].id)
        assertEquals("gif", items[0].ext)
        assertEquals("hug · Noragami", items[0].title)
    }
}
