package io.github.touhougo.chogumi.core.netroute

import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.network.OkHttpCustomizer
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCheckTest {
    @Test
    fun `检查连接走网络层的线路调整_对站点发一次 HEAD`() = runBlocking {
        val end = RecordingEnd()
        val check = RouteCheck(listOf(end))

        val result = check.measure(CheckSite.IMAGES)

        assertTrue("连上了是毫秒数：$result", result is Outcome.Success && result.value >= 0)
        val sent = end.requests.single()
        assertEquals("https://lain.bgm.tv/", sent.url.toString())
        assertEquals("HEAD", sent.method)
    }

    @Test
    fun `线路让请求失败时这一格是连不上`() = runBlocking {
        val refusing = OkHttpCustomizer { builder -> builder.addInterceptor(Interceptor { throw IOException("proxy selected but not connected") }) }
        val check = RouteCheck(listOf(refusing))

        assertTrue(check.measure(CheckSite.WEB) is Outcome.Failed)
    }

    @Test
    fun `站点回跳转也算连上_不跟着跳转走`() = runBlocking {
        val end = RecordingEnd { Response.Builder().code(302).message("Found").header("Location", "https://unreachable.example.invalid/") }
        val check = RouteCheck(listOf(end))

        val result = check.measure(CheckSite.WEB)

        assertTrue("收到第一个响应就是连上：$result", result is Outcome.Success)
        assertEquals("只发到站点本身", listOf("https://bgm.tv/"), end.requests.map { it.url.toString() })
    }
}
