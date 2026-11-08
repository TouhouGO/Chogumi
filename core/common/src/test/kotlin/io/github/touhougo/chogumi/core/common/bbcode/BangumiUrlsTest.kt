package io.github.touhougo.chogumi.core.common.bbcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BangumiUrlsTest {

    @Test
    fun `站内域名的 http 图片改走 https`() {
        assertEquals("https://lain.bgm.tv/pic/a.jpg", BangumiUrls.resolveImageUrl("http://lain.bgm.tv/pic/a.jpg"))
        assertEquals("https://bgm.tv/img/smiles/1.gif", BangumiUrls.resolveImageUrl("http://bgm.tv/img/smiles/1.gif"))
    }

    @Test
    fun `别的站点的 http 图片保持原样`() {
        assertEquals("http://example.com/a.jpg", BangumiUrls.resolveImageUrl("http://example.com/a.jpg"))
        assertEquals("http://lain.bgm.tv.evil.test/a.jpg", BangumiUrls.resolveImageUrl("http://lain.bgm.tv.evil.test/a.jpg"))
    }

    @Test
    fun `https 与协议相对与站内相对路径照旧`() {
        assertEquals("https://example.com/a.jpg", BangumiUrls.resolveImageUrl("https://example.com/a.jpg"))
        assertEquals("https://lain.bgm.tv/x.png", BangumiUrls.resolveImageUrl("//lain.bgm.tv/x.png"))
        assertEquals("https://lain.bgm.tv/img/smiles/1.gif", BangumiUrls.resolveImageUrl("/img/smiles/1.gif"))
        assertNull(BangumiUrls.resolveImageUrl(""))
    }
}
