package io.github.touhougo.chogumi.core.netroute

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException

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
 * 连这个代理算不算访问局域网（Android 17 起要 `ACCESS_LOCAL_NETWORK`）：本机回环（`localhost`、127.0.0.0/8、`::1`）不算；
 * 私有与链路本地的 IP（10/8、172.16/12、192.168/16、169.254/16、100.64/10，IPv6 的 fc00::/7、fe80::/10）算，IPv4 映射的 IPv6
 * （`::ffff:192.168.1.5`、`::ffff:c0a8:105`）按里面的 IPv4 判；主机名不在这里解析，可能指向局域网，按算处理（多问一次权限，
 * 好过连接悄悄超时）；其余公网 IP 不算。只有字面量的 IP 交给 [InetAddress]（不查 DNS）。
 */
fun ProxyServer.needsLocalNetwork(): Boolean {
    val name = host.lowercase().removePrefix("[").removeSuffix("]")
    if (name == "localhost") return false
    val literal = IPV4.matchEntire(name)?.destructured?.toList()?.all { it.toInt() <= 255 } == true || ':' in name
    if (!literal) return true
    val address = try {
        InetAddress.getByName(name)
    } catch (e: UnknownHostException) {
        return true // 像 IP 又解不开：当主机名
    }
    return address.isLocalNetwork()
}

private fun InetAddress.isLocalNetwork(): Boolean {
    if (isLoopbackAddress) return false
    val b = address.map { it.toInt() and 0xff }
    return if (this is Inet4Address) {
        b[0] == 10 || b[0] == 192 && b[1] == 168 || b[0] == 172 && b[1] in 16..31 || b[0] == 169 && b[1] == 254 || b[0] == 100 && b[1] in 64..127
    } else {
        b[0] and 0xfe == 0xfc || b[0] == 0xfe && b[1] and 0xc0 == 0x80
    }
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
private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
private val HOST_NAME = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*$")
private val IPV6 = Regex("^\\[?[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*\\]?$")
