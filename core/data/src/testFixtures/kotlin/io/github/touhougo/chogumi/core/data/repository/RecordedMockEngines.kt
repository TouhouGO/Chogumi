package io.github.touhougo.chogumi.core.data.repository

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import java.util.concurrent.ConcurrentHashMap

/** 为每个假引擎持有独立的线程安全请求记录。 */
class RecordedMockEngines {
    private val records = ConcurrentHashMap<MockEngine, ConcurrentRequestRecorder>()

    fun create(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): MockEngine {
        val recorder = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            recorder.record(request)
            handler(request)
        }
        records[engine] = recorder
        return engine
    }

    fun requests(engine: MockEngine): ConcurrentRequestRecorder = checkNotNull(records[engine])
}
