package io.github.touhougo.chogumi.core.ui.moderation

import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.ReportTarget
import io.github.touhougo.chogumi.core.model.UserModeration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 报告补充说明上限（服务端契约 `comment` ≤ 2000 字）。 */
private const val MAX_REPORT_COMMENT = 2000

/** 对别人的一次写操作（过了登录门才交给 [ModerationController.perform]）。 */
sealed interface ModerationAction {
    /** 打开报告面板；[name] 是面板标题里的称呼。 */
    data class Report(val target: ReportTarget, val name: String) : ModerationAction

    data class AddFriend(val username: String) : ModerationAction

    /** 解除好友：先弹确认（[ModerationState.unfriending]），确认了才发出。 */
    data class Unfriend(val username: String) : ModerationAction

    /** 绝交：先弹确认（[ModerationState.blocking]），确认了才发出；[nickname] 是确认框里的称呼。 */
    data class Block(val username: String, val nickname: String) : ModerationAction
}

/** 等确认的绝交：确认框里称呼 [nickname]，确认后绝交 [username]。 */
data class BlockConfirm(val username: String, val nickname: String)

/** 等确认的解除好友：确认后解除与 [username] 的好友关系。 */
data class UnfriendConfirm(val username: String)

/**
 * 一个页面里对别人的写操作的 UI 状态，只由 [ModerationController] 改，放在页面状态里（不放在楼层里：楼滚出列表、
 * 进程重建后楼层是骨架，开着的面板与确认框都得还在）。
 *
 * @property report 开着的报告面板；null = 没开。
 * @property blocking 开着的绝交确认框；null = 没开。
 * @property unfriending 开着的解除好友确认框；null = 没开。
 */
data class ModerationState(
    val report: ReportState? = null,
    val blocking: BlockConfirm? = null,
    val unfriending: UnfriendConfirm? = null,
)

/**
 * 页面里对别人的写操作：报告（用户或一层楼）、加好友、解除好友、绝交。有楼层的页面与他人主页各一份，用法同
 * [io.github.touhougo.chogumi.core.ui.collection.PosterMarkController]：报告面板与两个确认框的状态经 [read] / [write] 读写页面状态里的
 * 一个 [ModerationState] 字段，ViewModel 的状态仍只有一份，页面用 [ModerationHost] 画；写操作交给 [moderation]（数据层唯一实现）。
 *
 * 成功不提示：报告成功面板原地换成服务端致谢；加好友、解除好友、绝交成功什么都不说（M3：成功不打扰），关注状态按钮跟着好友名单变。
 * 失败经页面的 [failures] 提示一次；报告失败面板留着，可以重试（含重复报告的 409）。
 */
class ModerationController(
    private val scope: CoroutineScope,
    private val read: () -> ModerationState,
    private val write: ((ModerationState) -> ModerationState) -> Unit,
    private val failures: FailureNotices,
    private val moderation: UserModeration,
) {
    /** 面板轮次：关掉或重开之后，上一轮还在途的提交结果作废。 */
    private var reportRound = 0L

    fun perform(action: ModerationAction) {
        when (action) {
            is ModerationAction.Report -> {
                ++reportRound
                write { it.copy(report = ReportState(action.target, action.name)) }
            }
            is ModerationAction.AddFriend -> oneShot { moderation.addFriend(action.username) }
            is ModerationAction.Unfriend -> write { it.copy(unfriending = UnfriendConfirm(action.username)) }
            is ModerationAction.Block -> write { it.copy(blocking = BlockConfirm(action.username, action.nickname)) }
        }
    }

    /** 确认框里点了「绝交」：绝交框里问的 [confirm]（框已由 [dismissBlock] 收起，见 ConfirmDialog）。 */
    fun confirmBlock(confirm: BlockConfirm) = oneShot { moderation.addUserToBlocklist(confirm.username) }

    fun dismissBlock() = write { it.copy(blocking = null) }

    /** 确认框里点了「解除」：框里问的 [confirm]（框已由 [dismissUnfriend] 收起，见 ConfirmDialog）。 */
    fun confirmUnfriend(confirm: UnfriendConfirm) = oneShot { moderation.removeFriend(confirm.username) }

    fun dismissUnfriend() = write { it.copy(unfriending = null) }

    fun selectReportReason(reason: Int) = editReport { copy(reason = reason) }

    fun setReportComment(comment: String) = editReport { copy(comment = comment.take(MAX_REPORT_COMMENT)) }

    /** 提交报告。原因未选时直接忽略（「提交」不可点由 [ReportState.canSubmit] 保证）。 */
    fun submitReport() {
        val state = read().report ?: return
        if (!state.canSubmit) return
        val reason = requireNotNull(state.reason)
        val round = ++reportRound
        write { it.copy(report = state.copy(submitting = true)) }
        scope.launch {
            val result = moderation.report(state.target, reason, state.comment.ifBlank { null })
            if (round != reportRound) return@launch
            when (result) {
                is Outcome.Failed -> {
                    write { it.copy(report = state.copy(submitting = false)) }
                    failures.post(result.failure)
                }
                is Outcome.Success -> write { it.copy(report = state.copy(submitting = false, submittedText = result.value)) }
            }
        }
    }

    fun closeReport() {
        ++reportRound
        write { it.copy(report = null) }
    }

    /** 在途与确认态表单冻结；允许关闭，关闭后的结果由面板轮次作废。 */
    private fun editReport(edit: ReportState.() -> ReportState) {
        write { state ->
            val report = state.report
            if (report == null || report.submitting || report.submittedText != null) state else state.copy(report = report.edit())
        }
    }

    private fun oneShot(request: suspend () -> Outcome<Unit>) {
        scope.launch {
            val result = request()
            if (result is Outcome.Failed) failures.post(result.failure)
        }
    }
}
