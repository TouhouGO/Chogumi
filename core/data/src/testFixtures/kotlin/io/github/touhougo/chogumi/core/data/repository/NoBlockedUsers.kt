package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.model.BlockedUsers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * 页面 ViewModel 单测里的绝交名单：开始谁也不屏蔽。测绝交后楼层当场不显示的测试经 [list] 换一份名单，
 * 等于在这一页或别处绝交、取消后本机名单出了一拍。[initial] 为 null 时名单还没读到（刚登录、要当场去读）：
 * 一拍也不出，直到 [list] 被赋值。
 */
class NoBlockedUsers(initial: BlockedUsers? = BlockedUsers.NONE) : BlockedUsersFeed {
    val list = MutableStateFlow(initial)

    override fun current(): Flow<BlockedUsers> = list.filterNotNull()
}
