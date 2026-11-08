package io.github.touhougo.chogumi.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 全 App 打开网页只经过 [webUrlOf]：哪些地址能打开、没写协议的怎么补。 */
class WebUrlTest {

    @Test
    fun `http 与 https 原样 协议大小写不论`() {
        assertEquals("https://bgm.tv/user/fixture_user", webUrlOf("https://bgm.tv/user/fixture_user"))
        assertEquals("http://example.com", webUrlOf("http://example.com"))
        assertEquals("HTTPS://Example.com", webUrlOf("HTTPS://Example.com"))
    }

    @Test
    fun `没写协议的补 https 协议相对的也补`() {
        assertEquals("https://example.com", webUrlOf("example.com"))
        assertEquals("https://example.com:8080/a", webUrlOf("example.com:8080/a"))
        assertEquals("https://example.com:8080", webUrlOf("example.com:8080"))
        assertEquals("https://example.com/a", webUrlOf("//example.com/a"))
        assertEquals("https://example.com", webUrlOf("  example.com  "))
    }

    @Test
    fun `别的协议与空地址不打开`() {
        assertNull(webUrlOf("file:///sdcard/x"))
        assertNull(webUrlOf("intent://scan/#Intent;end"))
        assertNull(webUrlOf("javascript:alert(1)"))
        assertNull(webUrlOf("mailto:a@b.c"))
        assertNull(webUrlOf("ftp://example.com"))
        assertNull(webUrlOf(""))
        assertNull(webUrlOf("   "))
    }
}
