package io.github.touhougo.chogumi.core.data.util

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.data.token.SessionChangedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** 「一个异常算什么失败」只判一处：一次性调用（outcomeOf）与加载流（failuresAsLoad）结论一致。 */
class OutcomeOfTest {

    @Test
    fun `超时只有回读终态的写才改走网页_点一次操作链接的写原样给出超时`() = runTest {
        var webCalls = 0
        val timedOut: Outcome<String> = Outcome.Failed(Failure.Timeout)

        val linkWrite = timedOut.onWebIfServerError(RejectReason.TIMELINE_DELETE_UNAVAILABLE) { webCalls++; "web" }
        assertEquals(Failure.Timeout, assertIs<Outcome.Failed>(linkWrite).failure)
        assertEquals(0, webCalls, "删动态这类写超时后不再点网页链接：服务端可能已经做成")

        val settingWrite = timedOut.onWebIfUnconfirmed(RejectReason.PRIVACY_SETTING_UNAVAILABLE) { webCalls++; "web" }
        assertEquals("web", assertIs<Outcome.Success<String>>(settingWrite).value)
        assertEquals(1, webCalls)
    }

    @Test
    fun `连不上服务器时两种写都不改走网页`() = runTest {
        val offline: Outcome<String> = Outcome.Failed(Failure.Offline)

        assertEquals(offline, offline.onWebIfUnconfirmed(RejectReason.PRIVACY_SETTING_UNAVAILABLE) { error("不该走网页") })
        assertEquals(offline, offline.onWebIfServerError(RejectReason.TIMELINE_DELETE_UNAVAILABLE) { error("不该走网页") })
    }

    @Test
    fun `换号在一次性调用与加载流里都是换号失败`() = runTest {
        assertEquals(Outcome.Failed(Failure.SessionChanged), outcomeOf<Unit> { throw SessionChangedException() })
        val flowResult = flow<Load<Int>> {
            emit(Load.Pending)
            throw SessionChangedException()
        }.failuresAsLoad().toList()
        assertEquals(listOf(Load.Pending, Load.Failed(Failure.SessionChanged)), flowResult)
    }

    @Test
    fun `真取消照常抛出`() = runTest {
        assertFailsWith<CancellationException> { outcomeOf<Unit> { throw CancellationException("stop") } }
        assertFailsWith<CancellationException> { storageOutcomeOf<Unit> { throw CancellationException("stop") } }
        assertFailsWith<CancellationException> {
            flow<Load<Int>> { throw CancellationException("stop") }.failuresAsLoad().collect()
        }
    }

    @Test
    fun `上游的其它异常收成失败，下游收集者抛的异常不被吞掉`() = runTest {
        val last = flow<Load<Int>> { throw IllegalStateException("boom") }.failuresAsLoad().toList().last()
        assertIs<Load.Failed>(last)
        assertFailsWith<IllegalArgumentException> {
            flow<Load<Int>> { emit(Load.Ready(1)) }.failuresAsLoad().collect { throw IllegalArgumentException("collector") }
        }
    }
}
