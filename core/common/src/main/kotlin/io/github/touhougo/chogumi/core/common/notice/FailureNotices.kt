package io.github.touhougo.chogumi.core.common.notice

import androidx.annotation.StringRes
import io.github.touhougo.chogumi.core.foundation.Failure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 提示文案，都由界面按当前语言取：一个失败原因（[Reason]，文案在 core:ui 的 `Failure.notice()`），
 * 或一条字符串资源（[Resource.args] 是它的格式参数；参数是 [Failure] 时换成它的文案，如「收藏保存失败：%1$s」）。
 */
sealed interface NoticeText {
    data class Reason(val failure: Failure) : NoticeText

    data class Resource(@StringRes val id: Int, val args: List<Any> = emptyList()) : NoticeText
}

/**
 * 一条失败提示。**按身份区分**（不是 data class）：同一句话连着失败两次，提示也出现两次。
 * 提示条最多一个动作：[webUrl] 非空时是「去网页」，否则 [retry] 非空时是「重试」。
 *
 * @property retry 非空时提示条带「重试」，点了就调它。
 * @property webUrl 这件事只能在网页上做成：提示条带「去网页」，点了由界面打开这个网页（ViewModel 不持有界面对象）。
 */
class FailureNotice internal constructor(val text: NoticeText, val retry: (() -> Unit)?, val webUrl: String? = null)

/**
 * ViewModel → 界面的失败提示通道——**全仓唯一**。ViewModel [post]，界面用 core:ui 的 `FailureNoticeEffect`
 * 显示成提示条，显示完由它 [consume]。成功不提示（界面自身的变化就是反馈），只有失败走这里。
 *
 * 只留最新一条：还没显示就又失败时，用户看到的是最近那次的原因。
 */
class FailureNotices {
    private val pending = MutableStateFlow<FailureNotice?>(null)

    /** 等待显示的那一条；null = 没有。 */
    val current: StateFlow<FailureNotice?> = pending.asStateFlow()

    fun post(failure: Failure, retry: (() -> Unit)? = null) {
        pending.value = FailureNotice(NoticeText.Reason(failure), retry)
    }

    /** App 里做不成、网页上能做成的失败：提示条说明原因，动作是去 [url] 那个网页上完成。 */
    fun postWebOnly(failure: Failure, url: String) {
        pending.value = FailureNotice(NoticeText.Reason(failure), retry = null, webUrl = url)
    }

    fun post(@StringRes text: Int, vararg args: Any, retry: (() -> Unit)? = null) {
        pending.value = FailureNotice(NoticeText.Resource(text, args.toList()), retry)
    }

    /** 这一条已经显示过；期间又来了新的就保留新的。 */
    fun consume(notice: FailureNotice) {
        pending.compareAndSet(notice, null)
    }
}
