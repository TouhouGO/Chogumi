package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.mapper.toCommunityTopicBo
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.withSessionToken
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CommunityTopicBo
import io.github.touhougo.chogumi.core.model.GroupFeedCursor
import io.github.touhougo.chogumi.core.model.GroupFeedPage
import io.github.touhougo.chogumi.core.model.GroupSummaryBo
import io.github.touhougo.chogumi.core.model.GroupTopicRun
import io.github.touhougo.chogumi.core.model.GroupTopicScope
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.RakuenFilter
import io.github.touhougo.chogumi.core.network.api.CommunityApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 社区的帖子流：小组页的新帖、超展开、我的帖子。只走网络，不落库（同条目社区，每次进页现拉）。
 * 带令牌请求：「我加入的小组」要登录，非公开小组的帖子也只对成员可见。
 */
interface CommunityRepository {
    /**
     * 小组页的新帖，从 [cursor] 取一页（首页是 [GroupFeedCursor.START]，之后是上一页给的 [GroupFeedPage.next]）。
     * 首页（[scope] 为 null）在这里定范围：登录了、加入了小组是我加入的小组，没加入小组时是全部小组；
     * 未登录是全部小组。后面的页传回首页定下的范围，免得一页取自「我加入的」、下一页取自「全部」。
     *
     * 我加入的小组不用服务端的「我加入的」流：那条流每帖的回复数、最后回复时间取自一份 24 小时的逐帖缓存，加入名单也缓存一小时，
     * 线上都不随新回复、加入退出清掉（新帖一直是 0 回复，刚加入的小组不在里面）。这里按实时加入名单，每个小组取它自己的帖子列表
     * （直接读库），按最后回复时间合成一页（[mergeNewest]）。
     */
    suspend fun getGroupFeed(scope: GroupTopicScope?, cursor: GroupFeedCursor): GroupFeedPage

    /**
     * 超展开的一页。「小组」按 offset 翻页（全部小组的新帖）；其余分类接口不能翻页，一次取到上限、没有下一页。
     */
    suspend fun getRakuen(filter: RakuenFilter, offset: Int): PagedResult<CommunityTopicBo>

    /**
     * 我的帖子的一页：[scope] 是 [GroupTopicScope.CREATED]（我发起的）或 [GroupTopicScope.REPLIED]（我回复过的）。
     * 接口只覆盖小组帖子（作品讨论版没有「我的」接口）。要登录。
     */
    suspend fun getMyTopics(scope: GroupTopicScope, offset: Int): PagedResult<CommunityTopicBo>
}

/** 超展开（「小组」以外的分类）一次最多几帖：接口的上限，到这里就是底（页脚这样说）。 */
const val RAKUEN_MAX_TOPICS = CommunityApi.MAX_RAKUEN_LIMIT

/** 小组新帖每页条数（设计决定：与讨论版、评论列表同一页长）。 */
internal const val GROUP_TOPICS_PAGE = 20

/** 小组新帖同时取几个小组的帖子（设计决定：与其他扇出同一种写法，不占满同一域名的并发位，别的页面的请求不必排在后面）。 */
internal const val GROUP_FEED_CONCURRENCY = 4

/** [mergeNewest] 的结果：合成的这一页、各路剩下的帖子，以及这一页没取到的路数。 */
internal data class MergedPage(val items: List<CommunityTopicBo>, val runs: List<GroupTopicRun>, val failedRuns: Int)

/**
 * 几个小组各自按最后回复倒序的帖子合成一页，至多 [size] 帖，仍按最后回复倒序（同一时刻前面的小组在前）：
 * 每次从各路剩下的帖子里拿最新的一帖；一路手里的帖子用完、它后面还有时先用 [fetch] 取它的下一页，所以每一帖都排在该在的位置。
 * 没排进这一页的帖子留在返回的各路里，下一页接着排，不再重取。同时取页的路数不超过 [GROUP_FEED_CONCURRENCY]。
 *
 * 一路取页失败不拖垮整页：这一页不含它，它保留自己的 `nextOffset`，下一页再试，失败的路数记在 [MergedPage.failedRuns]。
 * 代价是它之后补进来时，排序只在页内成立。所有路都没取到、一帖也合不出来时，抛出第一个失败。
 *
 * @param fetch 取一个小组从某个 offset 起的一页，回这一页的帖子与下一页的 offset（null 是取完了）。
 */
internal suspend fun mergeNewest(
    runs: List<GroupTopicRun>,
    size: Int,
    fetch: suspend (GroupSummaryBo, Int) -> Outcome<Pair<List<CommunityTopicBo>, Int?>>,
): MergedPage {
    var current = runs
    val taken = LinkedHashMap<String, CommunityTopicBo>()
    val failed = LinkedHashMap<String, Failure>()
    val permits = Semaphore(GROUP_FEED_CONCURRENCY)
    while (taken.size < size) {
        if (current.any { it.needsFetch(failed) }) {
            current = coroutineScope {
                current.map { run ->
                    async {
                        val offset = run.nextOffset
                        if (!run.needsFetch(failed) || offset == null) return@async run
                        when (val result = permits.withPermit { fetch(run.group, offset) }) {
                            is Outcome.Success -> run.copy(buffered = result.value.first, nextOffset = result.value.second)
                            is Outcome.Failed -> {
                                // 途中换了账号：这次取页作废，不能当作「这一路失败」继续合
                                if (result.failure == Failure.SessionChanged) throw FailureException(result.failure)
                                synchronized(failed) { failed[run.group.name] = result.failure }
                                run
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        val newest = current.withIndex().filter { it.value.buffered.isNotEmpty() }.maxByOrNull { it.value.buffered.first().updatedAt } ?: break
        val topic = newest.value.buffered.first()
        // 翻页途中刚有人回复的帖子顶到前面，同一帖会在一个小组的两页里各出现一次：只留先到的那一份
        taken.putIfAbsent(topic.key, topic)
        current = current.toMutableList().also { it[newest.index] = newest.value.copy(buffered = newest.value.buffered.drop(1)) }
    }
    if (taken.isEmpty() && failed.isNotEmpty()) throw FailureException(failed.values.first())
    return MergedPage(taken.values.toList(), current, failed.size)
}

/** 这一路手里没有帖子、后面还有、并且这一页里还没失败过，该去取。 */
private fun GroupTopicRun.needsFetch(failed: Map<String, Failure>): Boolean =
    buffered.isEmpty() && nextOffset != null && group.name !in failed

class CommunityRepositoryImpl(
    private val api: CommunityApi,
    private val tokenStore: TokenStore,
    private val groups: GroupRepository,
) : CommunityRepository {

    override suspend fun getGroupFeed(scope: GroupTopicScope?, cursor: GroupFeedCursor): GroupFeedPage =
        tokenStore.withSessionToken { _, token ->
            val runs = when {
                cursor.runs != null -> cursor.runs
                scope == GroupTopicScope.ALL || token == null -> null
                else -> groups.getJoinedGroups().map { GroupTopicRun(it, buffered = emptyList(), nextOffset = 0) }.takeIf { it.isNotEmpty() }
            }
            if (runs == null) allGroupsFeed(cursor, token) else joinedFeed(runs, token)
        }

    private suspend fun allGroupsFeed(cursor: GroupFeedCursor, token: String?): GroupFeedPage {
        val page = groupTopics(GroupTopicsMode.ALL, cursor.offset, token)
        return GroupFeedPage(GroupTopicScope.ALL, page.items, page.nextOffset?.let { GroupFeedCursor(it, runs = null) })
    }

    /** 我加入的小组的一页：每个小组一路，合成一页（见 [CommunityRepository.getGroupFeed]）。 */
    private suspend fun joinedFeed(runs: List<GroupTopicRun>, token: String?): GroupFeedPage {
        val merged = mergeNewest(runs, GROUP_TOPICS_PAGE) { group, offset ->
            outcomeOf {
                val page = api.getTopicsOfGroup(group.name, GROUP_TOPICS_PAGE, offset, token)
                page.data.map { it.toCommunityTopicBo(group) } to
                    nextCursor(offset = offset, requestedLimit = GROUP_TOPICS_PAGE, total = page.total, isEmpty = page.data.isEmpty())
            }
        }
        val more = merged.runs.any { it.buffered.isNotEmpty() || it.nextOffset != null }
        return GroupFeedPage(
            GroupTopicScope.JOINED,
            merged.items,
            if (more) GroupFeedCursor(offset = 0, runs = merged.runs) else null,
            failedRuns = merged.failedRuns,
        )
    }

    override suspend fun getRakuen(filter: RakuenFilter, offset: Int): PagedResult<CommunityTopicBo> =
        tokenStore.withSessionToken { _, token ->
            val type = rakuenType(filter)
            if (type == null) {
                groupTopics(GroupTopicsMode.ALL, offset, token)
            } else {
                val items = api.getRakuenTopics(type, RAKUEN_MAX_TOPICS, token).data.mapNotNull { it.toCommunityTopicBo() }
                PagedResult(items = items, total = items.size, nextOffset = null)
            }
        }

    override suspend fun getMyTopics(scope: GroupTopicScope, offset: Int): PagedResult<CommunityTopicBo> {
        val mode = when (scope) {
            GroupTopicScope.CREATED -> GroupTopicsMode.CREATED
            GroupTopicScope.REPLIED -> GroupTopicsMode.REPLIED
            else -> throw IllegalArgumentException("my topics are CREATED or REPLIED, not $scope")
        }
        // 请求固定在开始时的会话：途中换号就丢掉结果（SessionChanged），A 的帖子不落到 B 的列表里。
        // 我发起的、我回复过的帖子是自己的足迹，不按绝交名单滤
        return tokenStore.withSessionToken(tokenStore.requireSession()) { _, token -> groupTopics(mode, offset, token) }
    }

    /** 分类 → 超展开接口的 `type`；「小组」不走超展开（那里不能翻页），走全部小组的新帖，为 null。 */
    private fun rakuenType(filter: RakuenFilter): String? = when (filter) {
        RakuenFilter.GROUP -> null
        RakuenFilter.ALL -> "all"
        RakuenFilter.SUBJECT -> "subject"
        RakuenFilter.EPISODE -> "episode"
        RakuenFilter.CHARACTER -> "character"
        RakuenFilter.PERSON -> "person"
    }

    /** 一页小组帖子，游标按原始页推进。 */
    private suspend fun groupTopics(mode: GroupTopicsMode, offset: Int, token: String?): PagedResult<CommunityTopicBo> {
        val page = api.getGroupTopics(mode.wire, GROUP_TOPICS_PAGE, offset, token)
        return page.toPagedResult(offset, GROUP_TOPICS_PAGE, page.data.map { it.toCommunityTopicBo() })
    }

}

/** 小组新帖接口的 `mode`：服务端那条流只有这三种（「我加入的」按实时名单分路取，不走服务端的流）。 */
private enum class GroupTopicsMode(val wire: String) { ALL("all"), CREATED("created"), REPLIED("replied") }
