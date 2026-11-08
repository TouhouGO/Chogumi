package io.github.touhougo.chogumi.feature.profile

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.common.paging.PagedListViewModel
import io.github.touhougo.chogumi.core.common.paging.Pager
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.BlockedUsersFeed
import io.github.touhougo.chogumi.core.data.repository.CommentRepository
import io.github.touhougo.chogumi.core.data.repository.IndexRepository
import io.github.touhougo.chogumi.core.data.repository.UserRepository
import io.github.touhougo.chogumi.core.data.util.loadOutcome
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.map
import io.github.touhougo.chogumi.core.foundation.toLoad
import io.github.touhougo.chogumi.core.model.BlogDetailsBo
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.IndexBo
import io.github.touhougo.chogumi.core.model.SlimBlogBo
import io.github.touhougo.chogumi.core.model.TopicSort
import io.github.touhougo.chogumi.core.model.TopicSubjectBo
import io.github.touhougo.chogumi.core.model.UserModeration
import io.github.touhougo.chogumi.core.reader.ComposerState
import io.github.touhougo.chogumi.core.reader.FloorComments
import io.github.touhougo.chogumi.core.reader.ReplyTarget
import io.github.touhougo.chogumi.core.reader.commentFloorsOf
import io.github.touhougo.chogumi.core.reader.replyComposer
import io.github.touhougo.chogumi.core.ui.moderation.ModerationController
import io.github.touhougo.chogumi.core.ui.moderation.ModerationState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** 用户日志列表页。 */
class BlogListViewModel(
    private val username: String,
    private val userRepository: UserRepository,
) : PagedListViewModel<SlimBlogBo>() {

    override suspend fun fetchPage(offset: Int) = userRepository.getUserBlogsPage(username, offset = offset)

    init {
        loadFirstPage()
    }
}

/**
 * 日志详情页：正文画成楼主楼，留言与讨论版话题同一套阅读骨架。正文、关联作品、留言三路并行取，**三路都落定才撤整页骨架**：
 * 正文先到先画的话，关联作品晚到会插进标题上方把正文往下挤，留言区还要再单独转一次圈。
 * 关联作品取不到就不出眉题（不报错），留言失败只影响留言区，正文失败整页报错。
 */
data class BlogDetailsUiState(
    val details: Load<BlogDetailsBo> = Load.Pending,
    /** 关联作品眉题：自己的区块，取不到时原位重试（[BlogDetailsViewModel.retrySubjects]），正文与留言照常。 */
    val subjects: Load<List<TopicSubjectBo>> = Load.Pending,
    val comments: FloorComments = FloorComments(),
    /** 写留言、回复一层楼的编辑器面板（[BlogDetailsViewModel.composer]）。 */
    val composer: ComposerState<ReplyTarget> = ComposerState(),
    /** 楼层长按的报告面板与绝交确认，只由 [BlogDetailsViewModel.moderation] 改。 */
    val moderation: ModerationState = ModerationState(),
)

class BlogDetailsViewModel(
    private val blogId: Int,
    private val userRepository: UserRepository,
    comments: CommentRepository,
    userModeration: UserModeration,
    blockedUsers: BlockedUsersFeed,
) : StateViewModel<BlogDetailsUiState>(BlogDetailsUiState()) {

    /** 本页的失败提示：留言发出后重拉楼层失败（楼层已在屏上）。 */
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
        viewModelScope.launch { blockedUsers.current().collect { list -> updateState { it.copy(comments = it.comments.withBlocked(list)) } } }
    }

    /** 写留言（回复日志本身）或回复一层楼：与讨论版同一个编辑器，发出后重拉留言。 */
    val composer = replyComposer(
        scope = viewModelScope,
        state = { currentState.composer },
        update = { transform -> updateState { it.copy(composer = transform(it.composer)) } },
        send = { content, replyTo, token -> comments.reply(CommentSurface.Blog(blogId), content, replyTo, token) },
        reload = {
            retryComments()
            joinSection(COMMENTS_SECTION)
        },
        drafts = comments.replyDrafts(CommentSurface.Blog(blogId), ReplyTarget::postId),
    )

    init {
        load()
    }

    fun retry() = load()

    /**
     * 重取留言：留言取失败时的重试，与发出留言后的重拉。楼层已在屏上时失败不抹掉楼层、走 [failures] 提示。
     * 与整页加载共用一个区块键，后发的一轮作废先发的。
     */
    fun retryComments() = launchSection(
        key = COMMENTS_SECTION,
        failures = failures,
        source = { loadOutcome { blogFloors() } },
        get = { it.comments.floors },
    ) { copy(comments = comments.copy(floors = it)) }

    private suspend fun blogFloors() = userRepository.getBlogComments(blogId).map(::commentFloorsOf)

    fun selectCommentSort(sort: TopicSort) = updateState { it.copy(comments = it.comments.withSort(sort)) }

    fun toggleCommentOpOnly() = updateState { it.copy(comments = it.comments.toggleOpOnly()) }

    /** 只重取关联作品：眉题区取不到时的原位重试，正文与留言不动。 */
    fun retrySubjects() = launchSection(SUBJECTS_SECTION, failures = null, source = { loadOutcome { userRepository.getBlogSubjects(blogId) } }, get = { it.subjects }) { copy(subjects = it) }

    private fun load() {
        cancelSection(COMMENTS_SECTION)
        cancelSection(SUBJECTS_SECTION)
        launchSection(PAGE_SECTION, failures = null, source = { loadOutcome { loadPage() } }, get = { it.details }) { copy(details = it) }
    }

    /**
     * 关联作品与留言和正文并行取；正文取到后三路一起落下，最后才交出正文（整页由 [BlogDetailsUiState.details] 决定画什么）。
     * 关联作品取不到不拖累整页：区块记成失败，眉题区原位重试（[retrySubjects]）。
     */
    private suspend fun loadPage(): Outcome<BlogDetailsBo> = coroutineScope {
        updateState { it.copy(comments = it.comments.advance(Load.Pending)) }
        val subjects = async { userRepository.getBlogSubjects(blogId).toLoad() }
        val comments = async { blogFloors() }
        val result = userRepository.getBlogDetails(blogId)
        when (result) {
            is Outcome.Success -> {
                val related = subjects.await()
                val floors = comments.await()
                updateState { it.copy(subjects = related, comments = it.comments.withOp(result.value.author?.id).advance(floors.toLoad())) }
            }
            is Outcome.Failed -> {
                subjects.cancel()
                comments.cancel()
            }
        }
        result
    }
}

/** 目录列表页的两页「创建的 / 收藏的」：共用一个 VM，各自一份分页列表。 */
enum class IndexListPage(val labelRes: Int) {
    CREATED(io.github.touhougo.chogumi.core.res.R.string.profile_indexes_created),
    COLLECTED(io.github.touhougo.chogumi.core.res.R.string.profile_indexes_collected),
}

/**
 * 目录两页各有多少条：资料头数字行「目录 N」带过来的数，取资料时由两个列表端点的 `total` 得来（`UserBo.indexCreateCount`）。
 * 只用来决定打开时落在哪页，以及两页的第一页到之前页签上先显示的数（[IndexListUiState.countOf]）：
 * 是打开资料时的数，之后别处建、删、收藏过的目录要等列表自己的 total。
 */
data class IndexListCounts(val created: Int, val collected: Int) {

    /** 打开时的页签：没创建过、但收藏过 → 「收藏的」；其余 → 「创建的」（不让人落在空页上）。 */
    val initialPage: IndexListPage
        get() = if (created == 0 && collected > 0) IndexListPage.COLLECTED else IndexListPage.CREATED

    fun countOf(page: IndexListPage): Int = when (page) {
        IndexListPage.CREATED -> created
        IndexListPage.COLLECTED -> collected
    }
}

/**
 * 目录列表页的状态：当前页签与两页各自的目录（左右翻页时两页都在，切走再切回不重取）。
 * [editor] 是自己的目录列表上「新建目录」的面板，关着为 null。
 */
data class IndexListUiState(
    val page: IndexListPage,
    val headerCounts: IndexListCounts,
    val lists: Map<IndexListPage, PagedList<IndexBo>> = IndexListPage.entries.associateWith { PagedList() },
    val editor: IndexEditorState? = null,
) {
    fun listFor(page: IndexListPage): PagedList<IndexBo> = lists.getValue(page)

    /**
     * 页签标签上的数（原型「创建的 2 / 收藏的 5」，唯一判定）：这一页的第一页到了就取列表自己的 total，与列表同一个来源；
     * 之前（或首屏失败）先用资料头带来的数。服务端没给 total 时：取完了就是已取到的条数，没取完仍用资料头带来的数。
     */
    fun countOf(page: IndexListPage): Int {
        val list = listFor(page)
        return when {
            list.isLoading || list.error != null -> headerCounts.countOf(page)
            list.total > 0 -> list.total
            !list.hasMore -> list.items.size
            else -> headerCounts.countOf(page)
        }
    }
}

/**
 * 目录列表页。打开就同时取两页的第一页：两个页签的数都是列表自己的 total，翻到另一页也不用再等。
 * 目录在别处改过（这里新建、目录页里编辑、删除、收录、移出）就把两页从头重取，屏上的行留到新的一页到手：
 * 观察 [IndexRepository.writes]，不是打开时读一次。
 */
class IndexListViewModel(
    private val username: String,
    counts: IndexListCounts,
    private val userRepository: UserRepository,
    indexRepository: IndexRepository,
) : StateViewModel<IndexListUiState>(IndexListUiState(page = counts.initialPage, headerCounts = counts)) {

    /** 本页的失败提示：新建目录失败。 */
    val failures = FailureNotices()

    /** 新建目录（自己的目录列表）：建好后落在「创建的」页签，进入新目录由界面在 `save` 的回调里做。 */
    val editor = IndexEditor(
        scope = viewModelScope,
        repository = indexRepository,
        failures = failures,
        read = { currentState.editor },
        write = { transform -> updateState { it.copy(editor = transform(it.editor)) } },
        afterSave = { selectPage(IndexListPage.CREATED) },
    )

    private val pagers = IndexListPage.entries.associateWith { page ->
        Pager(
            scope = viewModelScope,
            read = { currentState.listFor(page) },
            write = { f -> updateState { it.copy(lists = it.lists + (page to f(it.listFor(page)))) } },
        ) { offset ->
            when (page) {
                IndexListPage.CREATED -> userRepository.getUserIndexesPage(username, offset = offset)
                IndexListPage.COLLECTED -> userRepository.getIndexCollectionsPage(username, offset = offset)
            }
        }
    }

    init {
        pagers.values.forEach { it.refresh() }
        viewModelScope.launch {
            indexRepository.writes.drop(1).collect {
                pagers.values.forEach { it.refresh(keepItems = true) }
            }
        }
    }

    fun retry(page: IndexListPage) = pagers.getValue(page).refresh()

    fun loadMore(page: IndexListPage) = pagers.getValue(page).loadMore()

    fun selectPage(page: IndexListPage) {
        if (currentState.page != page) updateState { it.copy(page = page) }
    }
}

private const val COMMENTS_SECTION = "comments"
private const val PAGE_SECTION = "page"
private const val SUBJECTS_SECTION = "subjects"
