package io.github.touhougo.chogumi.core.ui.common.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 铃铛面板的两页：通知、短信。 */
enum class InboxTab { NOTICES, MESSAGES }

/**
 * 「打开铃铛面板」请求的唯一真源，属于导航状态，与 [AppNavigator] 同处：点开一条没有对应页面的提醒（动态、同人）或未读短信的
 * 系统通知时，外壳切到社区 tab 并在这里留一个请求；社区页取走（[consume]）后打开面板、停在那一页。取走之前一直留着，
 * 社区页晚一点组合也接得到；取走之后不再重放。
 */
class InboxRequests {
    private val _pending = MutableStateFlow<InboxTab?>(null)
    val pending: StateFlow<InboxTab?> = _pending.asStateFlow()

    fun open(tab: InboxTab) {
        _pending.value = tab
    }

    /** 社区页接住了请求：清掉，回到这一页时不再打开。 */
    fun consume() {
        _pending.value = null
    }
}
