package io.github.touhougo.chogumi.feature.explore

import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.data.repository.FaultablePreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.TestPreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * [ExploreGuide]：没走完过就从封面那一步开始，「下一步」到标题那一步，「知道了」「去整理」收起并记下；记下了就不再出，
 * 偏好读不出时不出也不崩。
 *
 * 等待都落在单调信号上：引导的状态出现目标值、偏好的流变真、读偏好的那个协程结束（作用域里不再有子协程）。
 */
class ExploreGuideTest {
    @get:Rule
    val resources = TestResources()

    private val preferences = TestPreferencesRepository()
    private val scopes = mutableListOf<CoroutineScope>()

    /** 引导作用域里没接住的异常：抛在 Default 的线程上测试看不见，记在这里由测试断言。 */
    private val uncaught = ConcurrentLinkedQueue<Throwable>()

    @After
    fun tearDown() = runBlocking {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        preferences.close()
        assertEquals(emptyList<Throwable>(), uncaught.toList())
    }

    private class Harness(val guide: ExploreGuide, val step: MutableStateFlow<ExploreGuideStep?>, val scope: CoroutineScope, val arranged: () -> Int)

    private fun harness(repository: PreferencesRepository = preferences.repository): Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + CoroutineExceptionHandler { _, error -> uncaught += error })
            .also { scopes += it }
        val step = MutableStateFlow<ExploreGuideStep?>(null)
        var arranged = 0
        val guide = ExploreGuide(scope, { change -> step.update(change) }, FailureNotices(), repository, arrange = { arranged++ })
        return Harness(guide, step, scope, { arranged })
    }

    private suspend fun Harness.awaitStep(expected: ExploreGuideStep) = withTimeout(WAIT_BUDGET_MS) { step.first { it == expected } }

    /** 读偏好的那个协程结束了（引导只在开头启动这一个）。 */
    private suspend fun Harness.awaitRead() = awaitUntil("读完引导记录") { scope.coroutineContext.job.children.none() }

    @Test
    fun `没走完过就从封面开始_下一步到标题_知道了收起并记下_下一次不再出`() = runBlocking {
        val first = harness()
        first.awaitStep(ExploreGuideStep.CoverMark)

        first.guide.next()
        assertEquals(ExploreGuideStep.Arrange, first.step.value)
        first.guide.finish(thenArrange = false)
        assertNull(first.step.value)
        assertEquals(0, first.arranged())
        withTimeout(WAIT_BUDGET_MS) { preferences.repository.exploreGuideShown.first { it } }

        val second = harness()
        second.awaitRead()
        assertNull(second.step.value)
    }

    @Test
    fun `中途离开不记下_下一次还从头出`() = runBlocking {
        val first = harness()
        first.awaitStep(ExploreGuideStep.CoverMark)
        first.guide.next()
        first.scope.coroutineContext.job.cancelAndJoin()

        val second = harness()
        second.awaitStep(ExploreGuideStep.CoverMark)
        assertEquals(false, preferences.repository.exploreGuideShown.first())
    }

    @Test
    fun `去整理收起之后进整理`() = runBlocking {
        val h = harness()
        h.awaitStep(ExploreGuideStep.CoverMark)
        h.guide.next()
        h.guide.finish(thenArrange = true)
        assertNull(h.step.value)
        assertEquals(1, h.arranged())
    }

    @Test
    fun `偏好读不出时不出引导也不崩`() = runBlocking {
        val faulty = FaultablePreferencesRepository(resources).apply { failReads.set(true) }
        val h = harness(faulty.repository)
        awaitUntil("读偏好失败") { faulty.failedReads.get() > 0 }
        h.awaitRead()
        assertNull(h.step.value)
        assertEquals("读失败不抛出引导的作用域", emptyList<Throwable>(), uncaught.toList())
    }
}
