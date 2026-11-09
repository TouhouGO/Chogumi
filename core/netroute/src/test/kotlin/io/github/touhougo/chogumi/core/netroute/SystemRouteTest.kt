package io.github.touhougo.chogumi.core.netroute

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 线路是系统网络（默认）时，发往 bgm.tv 的请求原样走系统网络：不改写、不带令牌。 */
class SystemRouteTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `系统网络时请求原样发出`() {
        val dir = Files.createTempDirectory("netroute").toFile()
        val route = NetRoute(
            PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("netroute.preferences_pb") },
            scope,
            BuiltinEdgeAddresses,
            supported = true,
        )
        scope.launch { route.run() }
        val end = RecordingEnd()
        val client = OkHttpClient.Builder().apply {
            DirectRouting { route.endpoint() }.customize(this)
            end.customize(this)
        }.build()

        client.newCall(Request.Builder().url("https://api.bgm.tv/v0/subjects/12").build()).execute().close()

        val sent = end.requests.single()
        assertEquals("https://api.bgm.tv/v0/subjects/12", sent.url.toString())
        assertNull(sent.header("X-Chogumi-Proxy-Token"))
    }
}
