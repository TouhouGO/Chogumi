package io.github.touhougo.chogumi.core.netroute

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreUrlTest {
    @Test
    fun `主机进第一段路径，路径与查询原样`() {
        assertEquals(
            "http://127.0.0.1:4242/api.bgm.tv/v0/subjects/12?a=1&b=%E4%B8%AD",
            coreUrlOf("https://api.bgm.tv/v0/subjects/12?a=1&b=%E4%B8%AD".toHttpUrl(), 4242).toString(),
        )
    }

    @Test
    fun `根路径与结尾斜杠保留`() {
        assertEquals("http://127.0.0.1:1/bgm.tv/", coreUrlOf("https://bgm.tv/".toHttpUrl(), 1).toString())
        assertEquals("http://127.0.0.1:1/bgm.tv/subject/12/", coreUrlOf("https://bgm.tv/subject/12/".toHttpUrl(), 1).toString())
    }

    @Test
    fun `只认 bgm 点 tv 与它的子域名`() {
        assertTrue(isBgmHost("bgm.tv"))
        assertTrue(isBgmHost("lain.bgm.tv"))
        assertFalse(isBgmHost("bgm.tv.example.com"))
        assertFalse(isBgmHost("notbgm.tv"))
    }
}
