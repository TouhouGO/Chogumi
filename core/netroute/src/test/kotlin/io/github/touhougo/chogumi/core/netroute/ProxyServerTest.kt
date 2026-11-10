package io.github.touhougo.chogumi.core.netroute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyServerTest {
    @Test
    fun `地址框只留主机那一段`() {
        assertEquals("192.168.1.5", proxyHostInput("  192.168.1.5 "))
        assertEquals("proxy.lan", proxyHostInput("socks5://user:pass@proxy.lan:1080/path"))
        assertEquals("127.0.0.1", proxyHostInput("http://127.0.0.1:7890"))
        assertEquals("proxy.lan", proxyHostInput("proxy.lan:8080"))
        assertEquals("a", proxyHostInput("a b"))
        assertEquals("[::1]", proxyHostInput("[::1]"))
        assertEquals("fe80::1", proxyHostInput("fe80::1"))
        assertEquals("proxylan", proxyHostInput("proxy_lan"))
    }

    @Test
    fun `端口框只留数字且最多五位`() {
        assertEquals("1080", proxyPortInput("1080"))
        assertEquals("65535", proxyPortInput("655356"))
        assertEquals("80", proxyPortInput("8a0 "))
    }

    @Test
    fun `填好的主机与端口才组成代理`() {
        assertEquals(ProxyServer(ProxyType.SOCKS5, "127.0.0.1", 1080), proxyServerOf(ProxyType.SOCKS5, "127.0.0.1", "1080"))
        assertEquals(ProxyServer(ProxyType.HTTP, "::1", 8080), proxyServerOf(ProxyType.HTTP, "[::1]", "8080"))
        assertNull("端口为 0", proxyServerOf(ProxyType.HTTP, "127.0.0.1", "0"))
        assertNull("端口超出 65535", proxyServerOf(ProxyType.HTTP, "127.0.0.1", "65536"))
        assertNull("没填端口", proxyServerOf(ProxyType.HTTP, "127.0.0.1", ""))
        assertNull("没填主机", proxyServerOf(ProxyType.HTTP, "", "1080"))
        assertNull("主机以点结尾", proxyServerOf(ProxyType.HTTP, "proxy.", "1080"))
    }

    @Test
    fun `WebView 的代理规则按类型写协议_IPv6 加方括号`() {
        assertEquals("http://127.0.0.1:8080", ruleOf(ProxyServer(ProxyType.HTTP, "127.0.0.1", 8080)))
        assertEquals("socks://proxy.lan:1080", ruleOf(ProxyServer(ProxyType.SOCKS5, "proxy.lan", 1080)))
        assertEquals("socks://[::1]:1080", ruleOf(ProxyServer(ProxyType.SOCKS5, "::1", 1080)))
    }

    @Test
    fun `局域网与主机名要局域网权限_本机回环与公网 IP 不要`() {
        fun needs(host: String) = ProxyServer(ProxyType.SOCKS5, host, 1080).needsLocalNetwork()
        listOf("192.168.1.5", "10.0.2.2", "172.16.0.1", "172.31.255.1", "169.254.1.1", "100.64.0.1", "fd00::1", "fe80::1", "proxy.lan", "nas.local")
            .forEach { assertEquals("$it 算局域网", true, needs(it)) }
        listOf("127.0.0.1", "127.8.8.8", "localhost", "::1", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:db8::1")
            .forEach { assertEquals("$it 不算局域网", false, needs(it)) }
    }
}
