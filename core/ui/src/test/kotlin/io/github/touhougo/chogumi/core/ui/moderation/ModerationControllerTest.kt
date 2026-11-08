package io.github.touhougo.chogumi.core.ui.moderation

import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.ReportTarget
import io.github.touhougo.chogumi.core.model.ReportType
import io.github.touhougo.chogumi.core.model.TopicType
import io.github.touhougo.chogumi.core.model.floorReportTargetOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 楼层菜单与他人主页共用的写操作：报告面板的轮次、绝交先确认、失败提示、写操作走数据层。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModerationControllerTest {

    private val floor = requireNotNull(floorReportTargetOf(CommentSurface.Topic(TopicType.GROUP, 7), 100, isPost = false))

    private class Harness(scope: CoroutineScope) {
        var state = ModerationState()
        val report get() = state.report
        val failures = FailureNotices()
        val moderation = FakeModeration()
        val controller = ModerationController(scope, { state }, { change -> state = change(state) }, failures, moderation)
    }

    private fun TestScope.harness() = Harness(this)

    @Test
    fun `报告一层楼：打开面板、选原因、提交，成功后面板换成致谢`() = runTest {
        val h = harness()
        h.controller.perform(ModerationAction.Report(floor, "Bob"))
        assertEquals(ReportState(floor, "Bob"), h.report)
        h.controller.selectReportReason(3)
        h.controller.setReportComment("  ")
        h.controller.submitReport()
        assertTrue(requireNotNull(h.report).submitting)
        advanceUntilIdle()
        assertEquals(listOf(Triple(ReportTarget(ReportType.GROUP_REPLY, 100), 3, null)), h.moderation.reports)
        assertEquals("感谢", h.report?.submittedText)
        assertNull(h.failures.current.value)
    }

    @Test
    fun `报告失败：面板留着可重试，失败提示一次`() = runTest {
        val h = harness()
        h.moderation.reportReply = CompletableDeferred(Outcome.Failed(Failure.Http(409)))
        val notices = mutableListOf<NoticeText>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.failures.current.filterNotNull().collect { notices += it.text }
        }
        h.controller.perform(ModerationAction.Report(floor, "Bob"))
        h.controller.selectReportReason(1)
        h.controller.submitReport()
        advanceUntilIdle()
        val state = requireNotNull(h.report)
        assertEquals(false, state.submitting)
        assertNull(state.submittedText)
        assertEquals(listOf(NoticeText.Reason(Failure.Http(409))), notices)
        assertEquals(listOf(Triple(floor, 1, null)), h.moderation.reports)
        h.failures.consume(requireNotNull(h.failures.current.value))
        h.moderation.reportReply = CompletableDeferred(Outcome.Success("感谢"))
        h.controller.submitReport()
        advanceUntilIdle()
        assertEquals("感谢", h.report?.submittedText)
        assertEquals(2, h.moderation.reports.size)
        assertEquals(listOf(NoticeText.Reason(Failure.Http(409))), notices)
    }

    @Test
    fun `关掉面板后，在途的提交结果作废`() = runTest {
        val h = harness()
        h.moderation.reportReply = CompletableDeferred()
        h.controller.perform(ModerationAction.Report(floor, "Bob"))
        h.controller.selectReportReason(1)
        h.controller.submitReport()
        advanceUntilIdle()
        h.controller.closeReport()
        h.moderation.reportReply.complete(Outcome.Failed(Failure.Offline))
        advanceUntilIdle()
        assertNull(h.report)
        assertNull(h.failures.current.value)
    }

    @Test
    fun `加好友交给数据层，失败才提示`() = runTest {
        val h = harness()
        h.controller.perform(ModerationAction.AddFriend("bob"))
        advanceUntilIdle()
        assertEquals(listOf("bob"), h.moderation.friends)
        assertNull(h.failures.current.value)

        h.moderation.writeResult = Outcome.Failed(Failure.NotLoggedIn)
        h.controller.perform(ModerationAction.AddFriend("bob"))
        advanceUntilIdle()
        assertEquals(NoticeText.Reason(Failure.NotLoggedIn), h.failures.current.value?.text)
    }

    @Test
    fun `绝交先开确认框，确认了才发出，取消什么都不发`() = runTest {
        val h = harness()
        h.controller.perform(ModerationAction.Block("eve", "Eve"))
        advanceUntilIdle()
        assertEquals(BlockConfirm("eve", "Eve"), h.state.blocking)
        assertEquals(emptyList<String>(), h.moderation.blocked)

        h.controller.dismissBlock()
        advanceUntilIdle()
        assertNull(h.state.blocking)
        assertEquals(emptyList<String>(), h.moderation.blocked)

        // 确认框点确认的顺序：先收起，再把框里问的那个人交给确认
        h.controller.perform(ModerationAction.Block("eve", "Eve"))
        val confirm = requireNotNull(h.state.blocking)
        h.controller.dismissBlock()
        h.controller.confirmBlock(confirm)
        advanceUntilIdle()
        assertNull(h.state.blocking)
        assertEquals(listOf("eve"), h.moderation.blocked)

        h.moderation.writeResult = Outcome.Failed(Failure.Offline)
        h.controller.dismissBlock()
        h.controller.confirmBlock(confirm)
        advanceUntilIdle()
        assertEquals(NoticeText.Reason(Failure.Offline), h.failures.current.value?.text)
    }

    @Test
    fun `解除好友先开确认框，确认了才发出，取消什么都不发`() = runTest {
        val h = harness()
        h.controller.perform(ModerationAction.Unfriend("bob"))
        advanceUntilIdle()
        assertEquals(UnfriendConfirm("bob"), h.state.unfriending)
        assertEquals(emptyList<String>(), h.moderation.unfriended)

        h.controller.dismissUnfriend()
        advanceUntilIdle()
        assertNull(h.state.unfriending)
        assertEquals(emptyList<String>(), h.moderation.unfriended)

        // 确认框点确认的顺序：先收起，再把框里问的那个人交给确认
        h.controller.perform(ModerationAction.Unfriend("bob"))
        val confirm = requireNotNull(h.state.unfriending)
        h.controller.dismissUnfriend()
        h.controller.confirmUnfriend(confirm)
        advanceUntilIdle()
        assertNull(h.state.unfriending)
        assertEquals(listOf("bob"), h.moderation.unfriended)
        assertNull(h.failures.current.value)

        h.moderation.writeResult = Outcome.Failed(Failure.Offline)
        h.controller.confirmUnfriend(confirm)
        advanceUntilIdle()
        assertEquals(NoticeText.Reason(Failure.Offline), h.failures.current.value?.text)
    }

    @Test
    fun `报告与绝交确认互不覆盖`() = runTest {
        val h = harness()
        h.controller.perform(ModerationAction.Report(floor, "Bob"))
        h.controller.perform(ModerationAction.Block("eve", "Eve"))
        h.controller.selectReportReason(2)
        assertEquals(2, h.report?.reason)
        assertEquals(BlockConfirm("eve", "Eve"), h.state.blocking)
    }
}
