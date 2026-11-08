package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.tag.TagCatalogDao
import io.github.touhougo.chogumi.core.data.db.tag.TagCatalogEntity
import io.github.touhougo.chogumi.core.data.util.isCacheStale
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagCatalogEntry
import io.github.touhougo.chogumi.core.model.isExplicitAdultTag
import io.github.touhougo.chogumi.core.network.api.NextApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 标签目录缓存 —— 筛选面板搜索框的数据层。
 *
 * ## 数据源与规模
 *
 * `GET /p1/channels/{type}/tags`（`NextApi.getChannelTags`），按使用次数倒序。
 * 每个类型缓存**前 [CATALOG_SIZE] 个**：`limit=100` 共 [CATALOG_SIZE] / [PAGE_SIZE] 次请求。
 * 选 5000 的判据是「普通作品（rank>1000）」的覆盖率——只有 5000 才让三个主要类型
 * 都过九成（动画 92.4%、书籍 90.8%、游戏 90.4%），
 * 2026-09-30 实测。
 *
 * ## 与 NSFW 开关的关系（缓存不分开关）
 *
 * 缓存**同一份前 5000，与开关无关**：开关切换不重拉。
 * - 开关打开：[search] 不过滤，全部返回。
 * - 开关关闭：只把**明确成人向**的词从查找结果里剔除（歧义词保留），
 *   谓词是全仓唯一的 [isExplicitAdultTag]。
 *
 * 过滤**只此一处**：复用唯一过滤组件 [NsfwFilter]，本类不自己判断开关。
 *
 * ## 刷新时机与调用约定（搜索页的筛选面板）
 *
 * 调用方（搜索页的筛选面板，按标签查目录建议）在**打开面板时**调用 [refreshIfNeeded]（挂起函数，跑完即止）：
 * 某个类型没有缓存、或距上次拉全超过 [REFRESH_TTL_MILLIS] 时，它在后台拉全并整体替换。
 * 刷新期间并发的 [search] **不会阻塞**，它返回当前已有的缓存（首次可能为空列表）——
 * 面板据此先显示已有结果，刷新完成后再自行重查。
 *
 * ## 失败与并发
 *
 * - 中途任何一页失败：**不写库**，旧数据与刷新时间原样保留，
 *   下次仍判定为需要刷新。
 * - 同一类型同时只允许一次刷新：持锁后**二次检查**新鲜度，并发调用只会真正拉一轮。
 *
 * @param clock 时间源。
 */
class TagCatalogRepository(
    private val nextApi: NextApi,
    private val dao: TagCatalogDao,
    private val nsfwFilter: NsfwFilter,
    private val clock: AppClock,
) {

    /** 每类型一把刷新锁；二次检查新鲜度后并发调用只拉一轮。 */
    private val refreshLocks = ConcurrentHashMap<Int, Mutex>()

    /** 保证目录可用：刷新失败时，有完整旧快照便回退到它；没有快照则返回失败。空目录的 meta 也是完整快照。 */
    suspend fun refreshIfNeeded(type: SubjectType): Outcome<Unit> {
        val lock = refreshLocks.computeIfAbsent(type.value) { Mutex() }
        return when (val result = outcomeOf {
            lock.withLock {
                val meta = dao.getMeta(type.value)
                if (meta != null && !isCacheStale(meta.refreshedAt, REFRESH_TTL_MILLIS, clock.nowMillis())) {
                    Outcome.Success(Unit)
                } else {
                    val refresh = outcomeOf { dao.replaceForType(type.value, fetchCatalog(type), clock.nowMillis()) }
                    if (meta != null && refresh is Outcome.Failed) Outcome.Success(Unit) else refresh
                }
            }
        }) {
            is Outcome.Success -> result.value
            is Outcome.Failed -> result
        }
    }

    /**
     * 按关键词查找该类型的标签。
     *
     * 名字**包含**关键词即命中（不区分大小写），按使用次数倒序，最多 [limit] 条。
     * SQL 通配符（`%`/`_`/`\`）会被转义，用户输入不会被当模式串。
     * 结果经唯一过滤组件 [NsfwFilter] 按当前开关处理（开关关闭时剔除明确成人向词）。
     */
    suspend fun search(type: SubjectType, keyword: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<TagCatalogEntry> {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return emptyList()
        val rows = dao.search(type.value, escapeLike(trimmed), limit)
        return nsfwFilter.apply(rows) { isExplicitAdultTag(it.name) }.map { it.toBo() }
    }

    /**
     * 拉全该类型前 [CATALOG_SIZE] 个标签，经唯一的分页取全 [fetchAllPages]，同时最多 [REFRESH_PARALLELISM] 页在途。
     *
     * 任何一页失败都直接抛出，由 [refreshIfNeeded] 转为「不写库」——
     * 绝不能把半份目录写进去覆盖旧数据。跨页重名按首次出现去重（实测前 5000 无重复，
     * 但服务端分页之间理论上可能重复，去重后名次取首次出现的位置）。
     */
    private suspend fun fetchCatalog(type: SubjectType): List<TagCatalogEntity> {
        val tags = fetchAllPages(PAGE_SIZE, maxRows = CATALOG_SIZE, permits = Semaphore(REFRESH_PARALLELISM)) { offset, limit ->
            nextApi.getChannelTags(type = type.value, limit = limit, offset = offset).toSlice()
        }
        return tags.distinctBy { it.name }.mapIndexed { index, tag ->
            TagCatalogEntity(type = type.value, name = tag.name, count = tag.count, rank = index + 1)
        }
    }

    private fun TagCatalogEntity.toBo() = TagCatalogEntry(name = name, count = count, rank = rank)

    companion object {
        /** 每个类型缓存的标签条数。判据见类文档与 2026-09-30 实测。 */
        const val CATALOG_SIZE = 5000

        /** 单次请求的最大条数（服务端上限 100，实测 101 返回 400）。 */
        const val PAGE_SIZE = 100

        /**
         * 刷新时同时在途的页数。刷新在后台跑、没人等它，而它和筛选面板自己的 p1 请求同一个 host：
         * 几十页一起发会占满网络层对该 host 的并发名额，面板的请求得排在后面。
         */
        private const val REFRESH_PARALLELISM = 2

        /** 查找结果默认条数上限。 */
        const val DEFAULT_SEARCH_LIMIT = 50

        /**
         * 目录刷新周期：7 天。
         *
         * **未实测**——`count`（使用次数）是存量排序，理论上应当比较稳，
         * 但 2026-09-30 的实测只测了一次、没有跨时间的名次对比，
         * 所以「排序有多稳」没有数据支撑。7 天是推测值。
         */
        const val REFRESH_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

        /**
         * 转义 SQL `like` 的通配符，配合 DAO 的 `escape '\'` 子句。
         *
         * 反斜杠必须**先**转义，否则会把后面新插入的反斜杠再转一次。
         * 转义只此一处，DAO 里的 `escape` 子句与本函数配对，缺一不可。
         */
        internal fun escapeLike(keyword: String): String = keyword
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }
}
