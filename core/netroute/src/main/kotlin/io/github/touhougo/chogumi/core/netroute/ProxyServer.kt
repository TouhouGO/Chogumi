package io.github.touhougo.chogumi.core.netroute

import java.net.InetSocketAddress
import java.net.Proxy

/** 代理的类型：HTTP（CONNECT 隧道）或 SOCKS5。两种都由代理那边解析域名。 */
enum class ProxyType { HTTP, SOCKS5 }

/** 一个代理：类型、主机（主机名或 IP，不带协议、路径、账号）、端口（1..65535）。 */
data class ProxyServer(val type: ProxyType, val host: String, val port: Int) {
    /**
     * OkHttp 用的代理。地址不在这里解析：HTTP 代理由 OkHttp 照常解析代理主机；SOCKS 由 JVM 的 SOCKS 实现连代理主机，
     * 要访问的站点名原样交给代理（远端解析，本机的 DNS 不经手 bgm.tv）。
     */
    internal fun toProxy(): Proxy = Proxy(
        if (type == ProxyType.HTTP) Proxy.Type.HTTP else Proxy.Type.SOCKS,
        InetSocketAddress.createUnresolved(host, port),
    )
}

/**
 * 地址框里输入的样子：只留主机那一段。去掉前后空白与 `xxx://` 前缀；粘贴进来的整行网址（`socks5://a@b:1080/x`）只留主机，
 * 空格、`/`、`@` 及其后的都不要；`主机:端口` 去掉端口；IPv6 的方括号形式（`[::1]`）原样留着。只收字母、数字、`.`、`-`、`:`、`[`、`]`。
 */
fun proxyHostInput(text: String): String {
    var host = text.trim().replace(SCHEME, "")
    host = host.substringAfterLast('@', host).split('/', ' ', '\t', '\n').first()
    if (!host.startsWith('[') && host.count { it == ':' } == 1) host = host.substringBefore(':')
    return host.filter { it.isLetterOrDigit() && it.code < 128 || it in ".-:[]" }
}

/** 端口框里输入的样子：只留数字，最多 5 位。 */
fun proxyPortInput(text: String): String = text.filter { it in '0'..'9' }.take(PORT_DIGITS)

/** 填好的主机与端口组成的代理；主机不像主机名或 IP、端口不在 1..65535 时为 null。 */
fun proxyServerOf(type: ProxyType, host: String, port: String): ProxyServer? {
    val number = port.takeIf { it.length in 1..PORT_DIGITS }?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    if (!HOST_NAME.matches(host) && !IPV6.matches(host)) return null
    return ProxyServer(type, host.removePrefix("[").removeSuffix("]"), number)
}

private const val PORT_DIGITS = 5
private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
private val HOST_NAME = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*$")
private val IPV6 = Regex("^\\[?[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*\\]?$")
