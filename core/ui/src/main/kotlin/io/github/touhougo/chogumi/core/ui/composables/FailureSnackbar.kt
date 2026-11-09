package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.res.R

/**
 * 显示一条失败提示条。只由 [FailureNoticeEffect] 调用（失败提示的唯一接线）。
 *
 * 时长依据 M3 Expressive（`.agent-skills/m3-expressive/references/components/feedback.md`
 * §5 Behavior）：不带操作的提示条 4–10 秒自动消失（[SnackbarDuration.Short]）；
 * 带操作的提示条要一直留到用户处理为止（[SnackbarDuration.Indefinite]），同时给一个关闭按钮（§5 Anatomy 的可选关闭项），
 * 用户不想处理时能自己收掉。
 *
 * @param message 面向用户的可读原因。
 * @param actionLabel 有动作（重试、去网页）时给出动作文案；为 null 表示纯告知失败、无操作按钮。
 */
private suspend fun SnackbarHostState.showFailure(
    message: String,
    actionLabel: String?,
): SnackbarResult =
    if (actionLabel == null) {
        showSnackbar(message = message, duration = SnackbarDuration.Short)
    } else {
        showSnackbar(
            message = message,
            actionLabel = actionLabel,
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite,
        )
    }

/**
 * 底部提示条（Snackbar）的**唯一入口**：把 ViewModel 的 [FailureNotices] 显示成提示条，
 * 页面不各自手写「LaunchedEffect(错误) { 显示; 清掉 }」。显示完才确认消费（先清掉会取消这次显示）；
 * 带重试的提示点了「重试」就调它；只能在网页上做成的（[io.github.touhougo.chogumi.core.common.notice.FailureNotice.webUrl]）
 * 点了「去网页」用当前的 [LocalUriHandler] 打开。
 *
 * 规则（产品决定，单一真源）：提示条只在「操作没办成」时出现——请求失败 / 超时 / 无响应、
 * 操作被拒（例如需要登录）。操作成功、纯告知（「已保存」「已固定」「已全部标为已读」）**一律不弹**，
 * 由界面本身的变化表达（图钉实心 / 空心、收藏面板关闭、未读数归零……）。`app` 模块的
 * `SnackbarFailureOnlyContractTest` 扫描全部 `src/main` 源集，任何模块直接调用 `showSnackbar` 都会让它变红。
 */
@Composable
fun FailureNoticeEffect(notices: FailureNotices, snackbar: SnackbarHostState) {
    val notice by notices.current.collectAsStateWithLifecycle()
    val text = notice?.text?.resolve()
    val retryLabel = stringResource(R.string.retry)
    val webLabel = stringResource(R.string.failure_action_open_web)
    val browser by rememberUpdatedState(LocalUriHandler.current)
    LaunchedEffect(notice) {
        val shown = notice ?: return@LaunchedEffect
        val webUrl = shown.webUrl
        val action = when {
            webUrl != null -> webLabel
            shown.retry != null -> retryLabel
            else -> null
        }
        val result = snackbar.showFailure(text.orEmpty(), action)
        if (result == SnackbarResult.ActionPerformed) {
            if (webUrl != null) browser.openUri(webUrl) else shown.retry?.invoke()
        }
        notices.consume(shown)
    }
}

/** 按当前语言取出 [NoticeText] 的文字——全仓唯一的解析处。 */
@Composable
fun NoticeText.resolve(): String = when (this) {
    is NoticeText.Reason -> failure.notice().resolve()
    is NoticeText.Resource -> stringResource(id, *args.map { if (it is Failure) it.text() else it }.toTypedArray())
}
