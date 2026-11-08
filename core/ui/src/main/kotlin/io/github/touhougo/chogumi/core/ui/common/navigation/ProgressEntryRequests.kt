package io.github.touhougo.chogumi.core.ui.common.navigation

import io.github.touhougo.chogumi.core.model.SubjectType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 一次「从首页进入进度页」的请求。
 *
 * 语义固定：进度页切到 [type]（null = 全部类型），并回到「在看」、清掉搜索与标签/分类
 * （清空动作复用进度页已有的 `clearFilters()`，请求本身不携带、也不重复列出要清哪些字段）。
 *
 * [seq] 是请求的**身份**：每次点击都生成新的 [seq]，所以同一类型连点两次也是两个不相等的请求。
 * 去掉它，[StateFlow] 会按相等性去重，第二次从首页进来就什么都不会发生。
 */
data class ProgressEntryRequest(val seq: Long, val type: SubjectType?) {
    /**
     * 还没有人从首页请求过（初始值）：进度页开在设置里选的默认分类，[type] 不用。
     * 之后的每一次请求都按 [type] 开，与默认分类无关。
     */
    val isDefaultView: Boolean get() = seq == 0L
}

/**
 * 「从首页进入进度页」请求的**唯一真源**，属于导航状态，与 [AppNavigator] 同处。
 *
 * 只有 app 的路由接线（`appEntries`）[open]：首页点了哪个入口以语义回调交给它，它在同一处写请求并切到进度 tab；
 * 进度页只订阅 [latest]。两个页面都不各存一份，也不跨 feature 直接调用对方的 ViewModel。
 * 进度页里用户自己切换类型不经过这里——那是页面自己的状态，不是「入口请求」。
 *
 * 初始值（`seq = 0`）等价于「尚无人请求过」（[ProgressEntryRequest.isDefaultView]）：订阅它的进度页开在默认视图。
 */
class ProgressEntryRequests {
    private val _latest = MutableStateFlow(ProgressEntryRequest(seq = 0, type = null))
    val latest: StateFlow<ProgressEntryRequest> = _latest.asStateFlow()

    fun open(type: SubjectType?) {
        _latest.update { ProgressEntryRequest(seq = it.seq + 1, type = type) }
    }
}
