package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.explore.ExploreShelfDao
import io.github.touhougo.chogumi.core.data.db.explore.ExploreShelfEntity
import io.github.touhougo.chogumi.core.data.db.explore.toShelfEntryOrNull
import io.github.touhougo.chogumi.core.data.db.explore.toShelfRows
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.ShelfEntry
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.concreteOrNull
import io.github.touhougo.chogumi.core.model.defaultShelfLayout
import io.github.touhougo.chogumi.core.model.pinned
import io.github.touhougo.chogumi.core.model.withHidden
import io.github.touhougo.chogumi.core.model.withShelfName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 探索页的货架布局（每个分类一份，只在本机）：读、改、恢复默认与搜索页图钉。
 *
 * 一个分类没有存过 = 没动过，读出来是默认布局（`defaultShelfLayout`）；第一次改动时把默认布局整份写进去再改
 * （[edit] 从「当前布局」起算，当前布局就是存下来的或默认的那份）。布局怎么变只由 core:model 的纯函数决定
 * （`pinned`、`withHidden`、`withShelfName`……），这里只负责读出当前那份、交给它、整份写回。
 *
 * 读改写由一把锁串起来：两次改动挨着来时，后一次从前一次写完的结果起算，不会互相盖掉。
 */
class ExploreLayoutRepository(private val dao: ExploreShelfDao) {

    private val writes = Mutex()

    /** [type] 的当前布局：存下来的（读不懂的行跳过），没存过就是默认布局。 */
    fun observe(type: SubjectType): Flow<List<ShelfEntry>> =
        dao.observe(type.value).map { rows -> rows.toLayout(type) }.distinctUntilChanged()

    /** 改 [type] 的布局：[change] 拿到当前布局，返回新布局，整份写回（新布局与当前一样时不写）。 */
    suspend fun edit(type: SubjectType, change: (List<ShelfEntry>) -> List<ShelfEntry>) = writes.withLock {
        val current = dao.get(type.value).toLayout(type)
        val next = change(current)
        if (next != current) dao.replaceType(type.value, next.toShelfRows(type))
    }

    /** [type] 回到默认布局：删掉存下来的那份，之后跟着默认布局走。 */
    suspend fun reset(type: SubjectType) = writes.withLock { dao.deleteType(type.value) }

    /** 搜索页图钉：把 [criteria] 放上它所属分类的探索页（规则见 core:model `pinned`）。 */
    suspend fun pin(criteria: SearchCriteria, name: String) {
        val type = checkNotNull(criteria.subjectType.concreteOrNull()) { "没有具体分类的条件放不上探索页" }
        edit(type) { it.pinned(type, criteria, name) }
    }

    /** 移除 [type] 里的一条货架（= 隐藏，「添加货架」里还能加回来）。 */
    suspend fun hide(type: SubjectType, id: String) = edit(type) { it.withHidden(id) }

    /** 给 [type] 里一条按词取的作品货架改名。 */
    suspend fun rename(type: SubjectType, id: String, name: String) = edit(type) { it.withShelfName(id, name) }

    private fun List<ExploreShelfEntity>.toLayout(type: SubjectType): List<ShelfEntry> =
        if (isEmpty()) defaultShelfLayout(type) else mapNotNull { it.toShelfEntryOrNull() }

}
