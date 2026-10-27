package org.hearth.ember

import org.hearth.ember.data.MediaKind
import org.hearth.ember.data.OnlineItem
import org.hearth.ember.data.OnlineMedia
import org.hearth.ember.data.OnlineParsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineParsingTest {
    private val giphy = """{"data":[
        {"id":"abc","title":"cat","images":{
          "fixed_width_small":{"url":"https://m/abc/100w.gif","webp":"https://m/abc/100w.webp"},
          "fixed_width":{"url":"https://m/abc/200w.gif","webp":"https://m/abc/200w.webp"},
          "fixed_height":{"url":"https://m/abc/200.gif","webp":"https://m/abc/200.webp"},
          "downsized_medium":{"url":"https://m/abc/giphy.gif"},
          "original":{"url":"https://m/abc/original.gif"}}},
        {"id":"nowebp","title":"x","images":{"fixed_height":{"url":"https://m/n/200.gif"},"original":{"url":"https://m/n/o.gif"}}},
        {"title":"no id"}],"meta":{"status":200}}"""

    @Test fun giphyGifsUseTheMediumGif() {
        val items = OnlineParsing.giphy(giphy, MediaKind.Gif)
        assertEquals(listOf("abc", "nowebp"), items.map { it.id })
        assertEquals("https://m/abc/giphy.gif", items[0].sendUrl)
        assertEquals("https://m/abc/100w.gif", items[0].previewUrl)
        assertEquals("gif", items[0].ext)
        assertEquals("https://m/n/200.gif", items[1].sendUrl)
    }

    @Test fun giphyStickersAreAnimatedWebpAndSkipWhatHasNone() {
        val items = OnlineParsing.giphy(giphy, MediaKind.Sticker)
        assertEquals(listOf("abc"), items.map { it.id })
        assertEquals("https://m/abc/200.webp", items[0].sendUrl)
        assertEquals("webp", items[0].ext)
    }

    @Test fun badJsonGivesNothing() {
        assertEquals(emptyList<OnlineItem>(), OnlineParsing.giphy("not json", MediaKind.Gif))
        assertEquals(emptyList<OnlineItem>(), OnlineParsing.imgur("{}"))
        assertEquals(emptyList<OnlineItem>(), OnlineParsing.commons("{\"batchcomplete\":\"\"}"))
    }

    @Test fun imgurKeepsOnlyAnimatedGifsFromPostsAndAlbums() {
        val json = """{"data":[
          {"id":"album1","title":"Album","images":[
            {"id":"i1","type":"image/gif","animated":true,"link":"http://i.imgur.com/i1.gif","size":500000},
            {"id":"i2","type":"image/jpeg","animated":false,"link":"https://i.imgur.com/i2.jpg","size":1000},
            {"id":"i3","type":"image/gif","animated":true,"link":"https://i.imgur.com/i3.gif","size":99000000}]},
          {"id":"post2","title":"Post","type":"image/gif","animated":true,"link":"https://i.imgur.com/post2.gif","size":1000},
          {"id":"post3","type":"image/png","animated":false,"link":"https://i.imgur.com/post3.png"},
          {"id":"i1","type":"image/gif","animated":true,"link":"https://i.imgur.com/i1.gif","size":1}],"success":true,"status":200}"""
        val items = OnlineParsing.imgur(json)
        assertEquals(listOf("i1", "post2"), items.map { it.id })
        assertEquals("https://i.imgur.com/i1.gif", items[0].sendUrl) // http is upgraded
    }

    @Test fun commonsKeepsGifsOnly() {
        val json = """{"query":{"pages":{
          "1":{"pageid":1,"title":"File:Spinning cat.gif","imageinfo":[{"size":2000,"mime":"image/gif","url":"https://u/cat.gif","thumburl":"https://u/t/cat.gif"}]},
          "2":{"pageid":2,"title":"File:Photo.jpg","imageinfo":[{"size":2000,"mime":"image/jpeg","url":"https://u/p.jpg"}]},
          "3":{"pageid":3,"title":"File:Huge.gif","imageinfo":[{"size":900000000,"mime":"image/gif","url":"https://u/h.gif"}]}}}}"""
        val items = OnlineParsing.commons(json)
        assertEquals(1, items.size)
        assertEquals("Spinning cat", items[0].title)
        assertEquals("https://u/t/cat.gif", items[0].previewUrl)
    }

    @Test fun emojiCodesBecomeEmoji() {
        assertEquals("\uD83D\uDE00", OnlineParsing.glyph("1f600"))
        assertEquals("\u2764\uFE0F", OnlineParsing.glyph("2764_fe0f"))
        assertEquals(listOf("1f600" to "grinning face"), OnlineParsing.animatedEmoji(listOf("1f600\tgrinning face", "", "junk")))
    }

    @Test fun sentFilesKeepTheNamesEveryClientReads() {
        val item = OnlineItem("a/b c", null, "https://x/y.webp", "webp")
        assertEquals("sticker-abc.webp", OnlineMedia.fileName(item, MediaKind.Sticker))
        assertEquals("gif-abc.webp", OnlineMedia.fileName(item, MediaKind.Gif))
        assertEquals("image/webp", OnlineMedia.mime(item))
        assertEquals("image/gif", OnlineMedia.mime(item.copy(ext = "gif")))
        assertNull(item.glyph)
    }
}
