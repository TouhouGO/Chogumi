package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.common.paging.PagedListViewModel
import io.github.touhougo.chogumi.core.common.paging.Pager
import io.github.touhougo.chogumi.core.common.paging.shownUnder
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.BlockedUsersFeed
import io.github.touhougo.chogumi.core.data.repository.BlockedUsersFilter
import io.github.touhougo.chogumi.core.data.repository.SubjectCommunityRepository
import io.github.touhougo.chogumi.core.data.util.loadOnce
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.BlockedUsers
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.IndexBo
import io.github.touhougo.chogumi.core.model.SlimBlogBo
import io.github.touhougo.chogumi.core.model.SubjectCollectorBo
import io.github.touhougo.chogumi.core.model.SubjectTopicBo
import io.github.touhougo.chogumi.core.model.UserModeration
import io.github.touhougo.chogumi.core.model.visibleThread
import io.github.touhougo.chogumi.core.reader.FloorReactions
import io.github.touhougo.chogumi.core.ui.moderation.ModerationController
import io.github.touhougo.chogumi.core.ui.moderation.ModerationState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 社区 tab 顶上「谁在看」最多露出的头像个数（原型是 5，窄屏上要给人数让出一行，取 3）；也是取最近收藏者的条数。 */
internal const val RECENT_COLLECTORS = 3

/**
 * 社区 tab 顶部：三个入口的数量与「谁在看」的头像。
 *
 * 各项独立失败：数量没取到就是 null，入口照常可点（数量只是附加信息），所以整块不会「取不到」；
 * 状态里它是一个 [Load]，四个请求全部落定后才就绪——单调，测试与骨架都等它。
 *
 * @property collectorsTotal 「谁在看」接口不按状态筛时的总数：面板「最近」chip 的计数。
 */
data class CommunityOverview(
    val reviews: Int? = null,
    val topics: Int? = null,
    val indexes: Int? = null,
    val recentCollectors: List<SubjectCollectorBo> = emptyList(),
    val collectorsTotal: Int? = null,
)

/**
 * 社区 tab 的状态：顶部概览、吐槽箱（分页）与吐槽的收藏状态筛选。
 *
 * @property commentFilter 吐槽按收藏状态筛（null = 全部）：服务端 `type` 参数筛，不在本地过滤已到手的一页。
 * @property blocked 当前账号的绝交名单：[shownComments] 据此不显示绝交用户的吐槽，名单一变当场跟上；还没读到（null）时吐槽按首屏加载画。
 */
data class SubjectCommunityUiState(
    val overview: Load<CommunityOverview> = Load.Pending,
    val comments: PagedList<CommentThreadBo> = PagedList(),
    val commentFilter: CollectionType? = null,
    /** 楼层长按的报告面板与绝交确认，只由 [SubjectCommunityViewModel.moderation] 改。 */
    val moderation: ModerationState = ModerationState(),
    val blocked: BlockedUsers? = null,
) {
    /** 要画的吐槽：[comments] 去掉绝交用户的（[visibleThread]），分页游标与总数仍是 [comments] 的（[shownUnder]）。 */
    val shownComments: PagedList<CommentThreadBo> = comments.shownUnder(blocked) { thread, list -> thread.visibleThread(list) }
}

/**
 * 社区 tab：吐槽箱第一页 + [CommunityOverview]。
 *
 * 只在社区 tab 第一次被选中时 [start]：不进社区 tab 就不发请求。吐槽箱在详情页的懒加载列表里原地
 * 逐条展开，滑到底经统一页脚 [loadMore] 续取（没有「查看全部」子页）。
 */
class SubjectCommunityViewModel(
    private val subjectId: Int,
    private val repository: SubjectCommunityRepository,
    userModeration: UserModeration,
    blockedUsers: BlockedUsersFeed,
) : StateViewModel<SubjectCommunityUiState>(SubjectCommunityUiState()) {

    private var started = false

    /** 社区 tab 的失败提示：贴贴失败、下拉刷新时吐槽重取失败。 */
    val failures = FailureNotices()

    /** 楼层长按菜单里的报告、加好友、绝交（[ModerationController]），失败经 [failures] 提示。 */
    val moderation = ModerationController(
        scope = viewModelScope,
        read = { currentState.moderation },
        write = { change -> updateState { it.copy(moderation = change(it.moderation)) } },
        failures = failures,
        moderation = userModeration,
    )

    init {
        // 绝交名单变了（这一页长按绝交、别处绝交或取消），已显示的楼层当场跟上
        viewModelScope.launch { blockedUsers.current().collect { list -> updateState { it.copy(blocked = list) } } }
    }

    private val comments = Pager(
        scope = viewModelScope,
        read = { currentState.comments },
        write = { f -> updateState { it.copy(comments = f(it.comments)) } },
    ) { offset -> outcomeOf { repository.getComments(subjectId, type = currentState.commentFilter, offset = offset) } }

    /** 吐槽的贴贴（乐观更新 + 失败退回，见 [FloorReactions]）。 */
    private val reactions = FloorReactions(
        scope = viewModelScope,
        threads = { currentState.comments.items },
        updateThreads = { transform -> comments.edit(transform) },
        send = repository::setCommentReaction,
        failures = failures,
    )

    fun react(itemId: Int, value: Int?) = reactions.react(itemId, value)

    fun retry() = comments.refresh()

    fun loadMore() = comments.loadMore()

    /** 换筛选：从第一页重拉（作废还在路上的旧请求），新一页到手前旧吐槽留在原地，页面不跳。 */
    fun selectCommentFilter(type: CollectionType?) {
        if (currentState.commentFilter == type) return
        updateState { it.copy(commentFilter = type) }
        comments.refresh(keepItems = true)
    }

    /**
     * 详情页下拉刷新：社区 tab 打开过才重取（吐槽箱第一页与概览），没打开过什么也不做。
     * 吐槽重取失败时已显示的吐槽留着，原因经 [failures] 提示。
     */
    fun refresh() {
        if (!started) return
        comments.reload { failures.post(it, retry = ::refresh) }
        loadOverview()
    }

    /** 幂等：只有第一次调用发请求。 */
    fun start() {
        if (started) return
        started = true
        comments.refresh()
        loadOverview()
    }

    private fun loadOverview() = launchSection(
        "overview",
        failures,
        source = {
            loadOnce {
                coroutineScope {
                    // 失败记为 null（不挡入口）
                    val reviews = async { outcomeOf { repository.getReviews(subjectId, limit = 1).total }.valueOrNull() }
                    val topics = async { outcomeOf { repository.getTopics(subjectId, limit = 1).total }.valueOrNull() }
                    val indexes = async { outcomeOf { repository.getIndexes(subjectId, limit = 1).total }.valueOrNull() }
                    val collectors = async { outcomeOf { repository.getCollectors(subjectId, limit = RECENT_COLLECTORS) }.valueOrNull() }
                    val recent = collectors.await()
                    CommunityOverview(
                        reviews = reviews.await(),
                        topics = topics.await(),
                        indexes = indexes.await(),
                        recentCollectors = recent?.items.orEmpty(),
                        collectorsTotal = recent?.total,
                    )
                }
            }
        },
        get = { it.overview },
    ) { copy(overview = it) }
}

/** 「谁在看」面板的状态：按收藏状态筛（null = 最近，不筛）与收藏者列表。 */
data class SubjectCollectorsUiState(
    val type: CollectionType? = null,
    val collectors: PagedList<SubjectCollectorBo> = PagedList(),
)

/**
 * 「谁在看」面板：按收藏状态筛，分页，按服务端顺序（时间）。
 * 接口另有「好友在前」参数，匿名请求看不出效果，没有接。
 */
class SubjectCollectorsViewModel(
    private val subjectId: Int,
    private val repository: SubjectCommunityRepository,
) : StateViewModel<SubjectCollectorsUiState>(SubjectCollectorsUiState()) {

    private val collectors = Pager(
        scope = viewModelScope,
        read = { currentState.collectors },
        write = { f -> updateState { it.copy(collectors = f(it.collectors)) } },
    ) { offset -> outcomeOf { repository.getCollectors(subjectId, type = currentState.type, offset = offset) } }

    init {
        collectors.refresh()
    }

    fun retry() = collectors.refresh()

    fun loadMore() = collectors.loadMore()

    fun selectType(type: CollectionType?) {
        if (currentState.type == type) return
        updateState { it.copy(type = type) }
        collectors.refresh()
    }
}

/** 讨论版：话题列表。绝交用户发的话题不显示，名单变了当场跟上（[BlockedUsersFilter]）。 */
class SubjectTopicsViewModel(
    private val subjectId: Int,
    private val repository: SubjectCommunityRepository,
    blockedUsers: BlockedUsersFeed,
) : PagedListViewModel<SubjectTopicBo>() {
    private val blocked = BlockedUsersFilter(viewModelScope, blockedUsers) { represent() }

    init {
        loadFirstPage()
    }

    override suspend fun fetchPage(offset: Int) = outcomeOf { blocked.alongside { repository.getTopics(subjectId, offset = offset) } }

    override fun present(fetched: List<SubjectTopicBo>) = blocked.visible(fetched) { it.creator?.id }
}

/** 评论：用户日志列表。绝交用户写的不显示，名单变了当场跟上（[BlockedUsersFilter]）。 */
class SubjectReviewsViewModel(
    private val subjectId: Int,
    private val repository: SubjectCommunityRepository,
    blockedUsers: BlockedUsersFeed,
) : PagedListViewModel<SlimBlogBo>() {
    private val blocked = BlockedUsersFilter(viewModelScope, blockedUsers) { represent() }

    init {
        loadFirstPage()
    }

    override suspend fun fetchPage(offset: Int) = outcomeOf { blocked.alongside { repository.getReviews(subjectId, offset = offset) } }

    override fun present(fetched: List<SlimBlogBo>) = blocked.visible(fetched) { it.author?.id }
}

/** 收录这个条目的目录。 */
class SubjectIndexesViewModel(
    private val subjectId: Int,
    private val repository: SubjectCommunityRepository,
) : PagedListViewModel<IndexBo>() {
    init {
        loadFirstPage()
    }

    override suspend fun fetchPage(offset: Int) = outcomeOf { repository.getIndexes(subjectId, offset = offset) }
}
