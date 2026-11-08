package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.model.BlockedUsers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 一份分页列表（`Pager`）跟着当前账号的绝交名单（[BlockedUsersFeed]）：帖子、动态、长评这些列表里绝交用户写的条目不显示。
 *
 * 取到的条目原样留在取页器里，藏不藏在显示时按**当前**名单定（取页器的 `present` 调 [visible]）。名单变了（在别的页绝交、
 * 取消绝交，或换号）经 [onChange] 让已取到的条目按新名单重算（`Pager.represent`）：返回栈里的列表当场跟上，不等刷新，
 * 取消绝交后那人的条目也回来。
 *
 * 第一页不先按「谁也不屏蔽」画出来：取页时经 [alongside] 同时等名单的第一拍（名单只是辅助数据，与内容请求并行，
 * 不串在前后；名单一直读不到时 [BlockedUsersFeed] 有时限，见 `BlocklistRepository.current`）。
 *
 * [scope] 是取页器所在的作用域，[onChange] 与取页器的其余调用落在同一个调度器上（取页器不是线程安全的）。
 * 取页器要先于它建好：订阅可能在构造时就出第一拍，那一拍就调 [onChange]。
 */
class BlockedUsersFilter(scope: CoroutineScope, feed: BlockedUsersFeed, onChange: () -> Unit) {
    private val list = MutableStateFlow<BlockedUsers?>(null)

    init {
        scope.launch {
            feed.current().collect {
                list.value = it
                onChange()
            }
        }
    }

    /** 取一页（[fetch]），同时等名单的第一拍；两者都到了才交出这一页。[fetch] 失败时等名单的那一路随之取消，异常原样抛出。 */
    suspend fun <R> alongside(fetch: suspend () -> R): R = coroutineScope {
        val known = async { list.filterNotNull().first() }
        fetch().also { known.await() }
    }

    /**
     * [items] 在当前名单下要显示的（[authorOf] 是条目的作者，未知为 null，不藏）。只在 [alongside] 交出过一页之后调
     * （取页器的 `present` 只换算取到的条目），那时名单已经到了。
     */
    fun <T> visible(items: List<T>, authorOf: (T) -> Int?): List<T> {
        val current = requireNotNull(list.value) { "名单还没到：先经 alongside 取页" }
        return items.filterNot { current.hides(authorOf(it)) }
    }
}
