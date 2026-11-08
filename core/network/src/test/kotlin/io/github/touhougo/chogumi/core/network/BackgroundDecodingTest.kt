package io.github.touhougo.chogumi.core.network

import io.ktor.client.call.body
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** 接口客户端的响应体解码不在调用 `body()` 的线程上跑（调用方多是主线程）。 */
class BackgroundDecodingTest {

    @Test
    fun `响应体在后台线程解码，不占调用方的线程`() {
        val caller = Executors.newSingleThreadExecutor { Thread(it, CALLER) }.asCoroutineDispatcher()
        val client = testApiClient { respond("""{"value":7}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        val probe = caller.use { dispatcher ->
            client.use { runBlocking(dispatcher) { it.get("https://api.bgm.tv/v0/probe").body<Probe>() } }
        }
        assertEquals(7, probe.value)
        assertNotEquals(CALLER, decodedOn, "解码跑在了调用方的线程上")
    }

    @Serializable
    private data class Probe(@Serializable(with = RecordingInt::class) val value: Int)

    /** 解码时记下当时的线程名。 */
    private object RecordingInt : KSerializer<Int> {
        override val descriptor = PrimitiveSerialDescriptor("RecordingInt", PrimitiveKind.INT)
        override fun deserialize(decoder: Decoder): Int {
            // 协程调试模式会在线程名后缀「 @coroutine#N」，只取线程本身的名字
            decodedOn = Thread.currentThread().name.substringBefore(" @")
            return decoder.decodeInt()
        }
        override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
    }

    private companion object {
        const val CALLER = "caller"

        @Volatile
        var decodedOn: String? = null
    }
}
