package io.github.touhougo.chogumi.core.foundation

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LoadTest {

    private val shown = Load.Ready("旧内容")

    @Test
    fun `刷新途中与刷新失败时_已显示的内容留着`() {
        assertEquals(shown, shown.advance(Load.Pending))
        assertEquals(shown, shown.advance(Load.Failed(Failure.Offline)))
    }

    @Test
    fun `没有内容时_失败后再等待回到骨架`() {
        assertEquals(Load.Pending, Load.Failed(Failure.Offline).advance(Load.Pending))
    }

    @Test
    fun `只有内容盖住的失败才要另外提示`() {
        assertEquals(Failure.Offline, shown.hiddenFailureOf(Load.Failed(Failure.Offline)))
        assertNull(Load.Pending.hiddenFailureOf<String>(Load.Failed(Failure.Offline)))
        assertNull(Load.Failed(Failure.Timeout).hiddenFailureOf<String>(Load.Failed(Failure.Offline)))
        assertNull(shown.hiddenFailureOf(Load.Pending))
        assertNull(shown.hiddenFailureOf(Load.Ready("新内容")))
    }

    @Test
    fun `缓存拍之后的网络拍直接替换`() {
        val cached = Load.Ready("缓存", fromCache = true)
        assertEquals(Load.Ready("网络"), cached.advance(Load.Ready("网络")))
    }

    @Test
    fun `落定_缓存拍也算成功_等待跳过`() = runTest {
        assertEquals(Outcome.Success("缓存"), flowOf(Load.Pending, Load.Ready("缓存", fromCache = true), Load.Ready("网络")).settled())
        assertEquals(Outcome.Failed(Failure.Offline), flowOf(Load.Pending, Load.Failed(Failure.Offline)).settled())
    }

    @Test
    fun `落定_只发等待就结束的流抛出`() = runTest {
        assertFailsWith<NoSuchElementException> { flowOf<Load<String>>(Load.Pending).settled() }
    }
}
