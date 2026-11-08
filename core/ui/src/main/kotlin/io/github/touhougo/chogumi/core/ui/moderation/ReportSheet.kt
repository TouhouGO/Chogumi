package io.github.touhougo.chogumi.core.ui.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.ReportTarget
import io.github.touhougo.chogumi.core.model.ReportType
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.BusyButton
import io.github.touhougo.chogumi.core.ui.composables.FormTextField
import io.github.touhougo.chogumi.core.ui.composables.PageModalSheet
import io.github.touhougo.chogumi.core.ui.composables.SheetTitleRow

/** 报告原因：服务端 `ReportReason` 枚举（实测核实，与原型 10 项一一对应）。 */
enum class ReportReason(val value: Int, val labelRes: Int) {
    ABUSE(1, R.string.report_reason_abuse),
    SPAM(2, R.string.report_reason_spam),
    POLITICAL(3, R.string.report_reason_political),
    ILLEGAL(4, R.string.report_reason_illegal),
    PRIVACY(5, R.string.report_reason_privacy),
    CHEAT_SCORE(6, R.string.report_reason_cheat_score),
    FLAME(7, R.string.report_reason_flame),
    ADVERTISEMENT(8, R.string.report_reason_advertisement),
    SPOILER(9, R.string.report_reason_spoiler),
    OTHER(99, R.string.report_reason_other),
}

/**
 * 报告面板的 UI 状态，只由 [ModerationController] 改。原因未选时提交不可点（[canSubmit]）。
 *
 * @property target 报告什么（用户、话题、一层楼……）。
 * @property name 面板标题里的称呼：报告用户时是该用户，报告内容时是发言的人。
 */
data class ReportState(
    val target: ReportTarget,
    val name: String,
    /** 已选原因；null = 未选。取值为服务端 `ReportReason` 枚举（1-9 / 99）。 */
    val reason: Int? = null,
    val comment: String = "",
    /** 提交在途。 */
    val submitting: Boolean = false,
    /**
     * 提交成功后服务端返回的致谢文案；非空 = 面板**原地**切到确认态，
     * 展示致谢 + 「完成」按钮，点「完成」才关面板（不另起弹窗、不留成功提示条）。
     */
    val submittedText: String? = null,
) {
    /** 没选原因、在途、或已确认态都不能再提交（服务端 value 必填）。 */
    val canSubmit: Boolean get() = reason != null && !submitting && submittedText == null
}

/**
 * 页面里对别人的写操作要画的东西：报告面板（[ModerationState.report]）、绝交确认框（[ModerationState.blocking]）与
 * 解除好友确认框（[ModerationState.unfriending]），没开的不画，动作都交给 [controller]。放在页面里其他面板之后，从别的面板里打开时叠在它上面。
 */
@Composable
fun ModerationHost(state: ModerationState, controller: ModerationController) {
    state.report?.let { report ->
        ReportSheet(
            state = report,
            onSelectReason = controller::selectReportReason,
            onComment = controller::setReportComment,
            onSubmit = controller::submitReport,
            onDismiss = controller::closeReport,
        )
    }
    state.blocking?.let { confirm ->
        BlockConfirmDialog(confirm, confirm.nickname, onConfirm = controller::confirmBlock, onDismiss = controller::dismissBlock)
    }
    state.unfriending?.let { confirm ->
        UnfriendConfirmDialog(confirm, onConfirm = controller::confirmUnfriend, onDismiss = controller::dismissUnfriend)
    }
}

/**
 * 报告底部面板（原型 `.sheet`：上两角 28dp、底 surfaceContainerLow、单选 min-height 48dp）。
 *
 * 原因 10 项与实测核实的服务端 `ReportReason` 枚举一一对应（[ReportReason] 是唯一映射）。
 * 没选原因时「提交」不可点（[ReportState.canSubmit]）。
 *
 * 提交成功后**不关面板**，原地换成 [ReportSubmittedContent]（服务端致谢 + 「完成」），
 * 避免"关面板 + 底部弹一条成功提示"两处重复表达同一件事。
 */
@Composable
internal fun ReportSheet(
    state: ReportState,
    onSelectReason: (Int) -> Unit,
    onComment: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 一打开就整张展开（原型面板没有半展开态）；高至多 86%（原型 `.sheet{max-height:86%}`，我的设计决定，not in M3）。
    PageModalSheet(onDismiss = onDismiss, fullHeight = false) {
        // 提交成功后**原地**换成确认态（服务端致谢 + 「完成」），面板不关、不另起弹窗、不留提示条。
        val submittedText = state.submittedText
        if (submittedText != null) {
            ReportSubmittedContent(text = submittedText, onDone = onDismiss)
            return@PageModalSheet
        }
        Column(Modifier.fillMaxWidth().fillMaxHeight(REPORT_SHEET_MAX_HEIGHT_FRACTION)) {
            // 标题行不随内容滚动：标题 titleLarge，右侧「提交」（确认在标题行右侧；下滑或点遮罩就是取消）。
            SheetTitleRow(
                title = stringResource(if (state.target.type == ReportType.USER) R.string.report_title else R.string.report_title_post, state.name),
                modifier = Modifier.testTag(ReportTitleRowTag),
            ) {
                BusyButton(
                    onClick = onSubmit,
                    enabled = state.canSubmit,
                    busy = state.submitting,
                    busyLabel = stringResource(R.string.loading),
                ) {
                    Text(stringResource(R.string.report_submit))
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
            ) {
                // 原型 `.radio{min-height:48px;padding:0 24px 0 16px;gap:12px}`：整行可点，
                // 单选钮本身不另设点击（否则它自带 48dp 触控区，把圆点推离左缘）。
                Column(Modifier.selectableGroup()) {
                    ReportReason.entries.forEach { reason ->
                        val selected = state.reason == reason.value
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = selected, enabled = !state.submitting, role = Role.RadioButton) { onSelectReason(reason.value) }
                                .padding(start = 16.dp, end = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = selected, onClick = null, enabled = !state.submitting)
                            Text(stringResource(reason.labelRes), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                // 补充说明（原型 `.field`：外边距 8/16、min-height 88dp，提示文字在框内；选填 ≤2000 字）。
                FormTextField(
                    value = state.comment,
                    onValueChange = onComment,
                    enabled = !state.submitting,
                    placeholder = stringResource(R.string.report_comment_label),
                    minLines = REPORT_COMMENT_MIN_LINES,
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                        .fillMaxWidth(),
                )
            }
        }
    }
}


/** 举报面板的标题行（标题与「提交」同一行）。 */
internal const val ReportTitleRowTag = "report_title_row"


/** 报告面板高度上限占屏比（原型 `.sheet{max-height:86%}`）。 */
private const val REPORT_SHEET_MAX_HEIGHT_FRACTION = 0.86f
private const val REPORT_COMMENT_MIN_LINES = 4


/**
 * 报告提交成功的原地确认态：服务端致谢文案 + 全宽「完成」按钮，点「完成」才关面板。
 *
 * 布局（居中、24dp 边距、16dp 间距）沿用本面板既有留白，**my design decision (not in M3)**
 * ——M3 未规定 bottom sheet 内确认态的排布，这里只求与上面的表单同一种观感。
 */
@Composable
private fun ReportSubmittedContent(text: String, onDone: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.report_done))
        }
    }
}
