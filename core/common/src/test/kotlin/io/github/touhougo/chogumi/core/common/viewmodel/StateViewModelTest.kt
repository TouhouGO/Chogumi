package io.github.touhougo.chogumi.core.common.viewmodel

import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class StateViewModelTest {

    private data class Counter(val value: Int = 0, val label: String = "")

    private class CounterViewModel : StateViewModel<Counter>(Counter()) {
        fun increment() = updateState { it.copy(value = it.value + 1) }
        fun rename(label: String) = updateState { it.copy(label = label) }
        fun snapshot() = currentState
    }

    /** 一个区块加一个随它落下的忙态：区块一有结果，忙态就该同时落下。 */
    private data class Page(val section: Load<Int> = Load.Pending, val busy: Boolean = true)

    private class PageViewModel : StateViewModel<Page>(Page()) {
        val failures = FailureNotices()
        val retry: () -> Unit = {}

        suspend fun follow(source: Flow<Load<Int>>) = source.collectSection(
            failures,
            retry,
            get = { it.section },
            alongside = { load -> if (load is Load.Ready) copy(busy = false) else this },
        ) { copy(section = it) }
    }

    @Test
    fun `初始状态来自构造参数`() {
        assertEquals(Counter(), CounterViewModel().uiState.value)
    }

    @Test
    fun `每次更新都基于最新状态，互不覆盖`() {
        val viewModel = CounterViewModel()
        viewModel.increment()
        viewModel.rename("a")
        viewModel.increment()
        assertEquals(Counter(value = 2, label = "a"), viewModel.uiState.value)
        assertEquals(viewModel.uiState.value, viewModel.snapshot())
    }

    @Test
    fun `区块的一拍与随它改的字段同一次更新落下 中间没有一拍新值还忙`() = runBlocking {
        val viewModel = PageViewModel()
        val seen = mutableListOf<Page>()
        // Unconfined：每次状态更新都在更新的那一刻同步交到这里，中间态一个不漏
        val watcher = launch(Dispatchers.Unconfined) { viewModel.uiState.collect { seen += it } }
        viewModel.follow(flowOf(Load.Pending, Load.Ready(1)))
        watcher.cancel()
        assertEquals(listOf(Page(), Page(Load.Ready(1), busy = false)), seen)
    }

    @Test
    fun `有内容时来了失败 内容留着 失败带重试发一次提示`() = runBlocking {
        val viewModel = PageViewModel()
        viewModel.follow(flowOf(Load.Ready(1), Load.Failed(Failure.Offline)))
        assertEquals(Load.Ready(1), viewModel.uiState.value.section)
        val notice = assertNotNull(viewModel.failures.current.value)
        assertEquals(NoticeText.Reason(Failure.Offline), notice.text)
        assertSame(viewModel.retry, notice.retry)
    }

    @Test
    fun `没有内容时失败落在区块上 不另发提示`() = runBlocking {
        val viewModel = PageViewModel()
        viewModel.follow(flowOf(Load.Pending, Load.Failed(Failure.Offline)))
        assertEquals(Load.Failed(Failure.Offline), viewModel.uiState.value.section)
        assertNull(viewModel.failures.current.value)
    }
}
