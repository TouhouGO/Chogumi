package io.github.touhougo.chogumi.feature.profile

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.common.paging.Pager
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.BlockedUsersFeed
import io.github.touhougo.chogumi.core.data.repository.CommentRepository
import io.github.touhougo.chogumi.core.data.repository.IndexRepository
import io.github.touhougo.chogumi.core.data.util.loadOnce
import io.github.touhougo.chogumi.core.data.util.loadOutcome
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.IndexCategory
import io.github.touhougo.chogumi.core.model.IndexDetailsBo
import io.github.touhougo.chogumi.core.model.IndexEntryBo
import io.github.touhougo.chogumi.core.model.INDEX_EDITS_PER_WINDOW
import io.github.touhougo.chogumi.core.model.IndexEntrySort
import io.github.touhougo.chogumi.core.model.IndexTarget
import io.github.touhougo.chogumi.core.model.TopicSort
import io.github.touhougo.chogumi.core.model.UserModeration
import io.github.touhougo.chogumi.core.model.collectCountAfter
import io.github.touhougo.chogumi.core.model.defaultIndexCategory
import io.github.touhougo.chogumi.core.model.indexEntriesNeedAll
import io.github.touhougo.chogumi.core.model.indexReorderOf
import io.github.touhougo.chogumi.core.reader.ComposerState
import io.github.touhougo.chogumi.core.reader.FloorComments
import io.github.touhougo.chogumi.core.reader.ReplyTarget
import io.github.touhougo.chogumi.core.reader.commentFloorsOf
import io.github.touhougo.chogumi.core.reader.replyComposer
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.moderation.ModerationController
import io.github.touhougo.chogumi.core.ui.moderation.ModerationState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 目录详情页状态。
 *
 * [entries] 是当前类别 [category] **服务端顺序**的已取收录；显示顺序由界面按 [sort] 调 `sortIndexEntries` 派生，不另存一份。
 * 换类别时从第 0 页重取（分页游标属于某一类别）。「按评分」与自己的目录要全量（`indexEntriesNeedAll`：接口没有排序参数；
 * 拖动排序的新排序值按整列算），剩下的页由 [Pager.loadAll] 取完。
 *
 * @property comments 留言（与讨论版话题同一套阅读骨架，楼主是目录作者）；与头部、收录并行取，失败只影响留言页签。
 * @property editor 编辑目录面板（自己的目录，⋮「编辑」），关着为 null。
 * @property link 「收录链接」面板（自己的目录），关着为 null。
 * @property entryComment 评语面板（自己的目录，长按一行「编辑评语」），关着为 null。
 * @property isReordering 拖动排序的写入在途：写完（或失败写回）之前不能再拖。
 */
data class IndexDetailsUiState(
    /** 整页：目录信息与第一页收录都落定才就绪；目录信息取不到才整页失败，收录、留言各有自己的错误位。 */
    val details: Load<IndexDetailsBo> = Load.Pending,
    val category: IndexCategory = IndexCategory.SUBJECT,
    val entries: PagedList<IndexEntryBo> = PagedList(),
    val sort: IndexEntrySort = IndexEntrySort.ORDER,
    val comments: FloorComments = FloorComments(),
    /** 写留言、回复一层楼的编辑器面板（[IndexDetailsViewModel.composer]）。 */
    val composer: ComposerState<ReplyTarget> = ComposerState(),
    val isCollectionLoading: Boolean = false,
    /** 楼层长按的报告面板与绝交确认，只由 [IndexDetailsViewModel.moderation] 改。 */
    val moderation: ModerationState = ModerationState(),
    val editor: IndexEditorState? = null,
    val link: IndexLinkState? = null,
    /** 删除目录在途：确认后不再发第二次。 */
    val isDeleting: Boolean = false,
    /** 目录已删掉：页面在前台时离开，此刻在子页上就等回到这一页再离开。 */
    val deleted: Boolean = false,
    val entryComment: IndexCommentState? = null,
    val isReordering: Boolean = false,
) {
    /**
     * 能不能拖动排序（唯一判定，界面与 [IndexDetailsViewModel.moveEntries] 都读它）：自己的目录、收录顺序视图、
     * 这一类已经全部取到，没有排序或评语的写入在途。
     */
    val canReorder: Boolean
        get() = details.valueOrNull()?.isOwn == true && sort == IndexEntrySort.ORDER &&
            !entries.isLoading && entries.error == null && !entries.hasMore && !isReordering && entryComment?.saving != true
}

/**
 * 评语面板的状态（关着时页面状态里是 null）：哪一条（[entryId]，打开时它的排序值 [order]）、打开时的评语、框里的字、
 * 在途、写成功（面板滑下去）。一点没改不能保存；写失败时面板与框里的字都留着。
 */
data class IndexCommentState(
    val entryId: Int,
    val order: Int,
    val original: String,
    val text: String = original,
    val saving: Boolean = false,
    val saved: Boolean = false,
) {
    val canSave: Boolean get() = !saving && !saved && text.trim() != original.trim()
}

class IndexDetailsViewModel(
    private val indexId: Int,
    private val indexRepository: IndexRepository,
    comments: CommentRepository,
    userModeration: UserModeration,
    blockedUsers: BlockedUsersFeed,
) : StateViewModel<IndexDetailsUiState>(IndexDetailsUiState()) {

    /** 本页的失败提示：收藏写入失败、留言发出后重拉楼层失败。 */
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

    /** 写留言或回复一层楼：与讨论版同一个编辑器，发出后重拉留言。 */
    val composer = replyComposer(
        scope = viewModelScope,
        state = { currentState.composer },
        update = { transform -> updateState { it.copy(composer = transform(it.composer)) } },
        send = { content, replyTo, token -> comments.reply(CommentSurface.Index(indexId), content, replyTo, token) },
        reload = {
            loadComments()
            joinSection(COMMENTS_SECTION)
        },
        drafts = comments.replyDrafts(CommentSurface.Index(indexId), ReplyTarget::postId),
    )

    /** 编辑目录（自己的目录）：写成功后重取头部，名称、介绍、私密跟着变。 */
    val editor = IndexEditor(
        scope = viewModelScope,
        repository = indexRepository,
        failures = failures,
        read = { currentState.editor },
        write = { transform -> updateState { it.copy(editor = transform(it.editor)) } },
        afterSave = { refreshDetails() },
    )

    /** 每次打开、关闭「收录链接」面板加一：旧一次的结果不改后来打开的面板。 */
    private var linkGeneration = 0L

    /** 每次打开、关闭评语面板加一：旧一次的结果不改后来打开的面板。 */
    private var commentGeneration = 0L

    /**
     * 改收录（拖动排序、评语）一次只写一样：两样都要带上另一项的当前值（接口要求排序值与评语一起写），
     * 后到的在锁里才读这一条的当前值，不会把前一笔刚写的改回去。
     */
    private val entryWrites = Mutex()

    private val entries = Pager(
        scope = viewModelScope,
        read = { currentState.entries },
        write = { f -> updateState { it.copy(entries = f(it.entries)) } },
    ) { offset -> outcomeOf { indexRepository.getEntries(indexId, currentState.category, offset = offset) } }

    init {
        load()
    }

    /** 整页重试：同一区块的上一轮先取消，不会有两轮交替写入。 */
    fun retry() = load()

    private fun load() = launchSection(PAGE_SECTION, failures = null, source = { loadOutcome { loadPage() } }, get = { it.details }) { copy(details = it) }

    /**
     * 目录信息取到后，类别、留言与第一页收录一并落定，最后才交出目录信息：一套整页骨架直到首屏齐了才交叉淡入，
     * 不是「整屏加载 → 头部出现 → 收录区再加载」两段。
     */
    private suspend fun loadPage(): Outcome<IndexDetailsBo> {
        val details = when (val result = outcomeOf { indexRepository.getIndex(indexId) }) {
            is Outcome.Success -> result.value
            is Outcome.Failed -> return result
        }
        loadComments()
        updateState { it.copy(category = defaultIndexCategory(details), comments = it.comments.withOp(details.creator?.id)) }
        fetchEntries(keepItems = false, isOwn = details.isOwn)
        uiState.first { !it.entries.isLoading }
        return Outcome.Success(details)
    }

    fun loadMoreEntries() = entries.loadMore()

    fun retryEntries() = fetchEntries(keepItems = false)

    /** 换类别（作品 / 角色 / 人物）：排序回到收录顺序，从第 0 页重取（作废在途的取页）。 */
    fun selectCategory(category: IndexCategory) {
        if (category == currentState.category) return
        updateState { it.copy(category = category, sort = IndexEntrySort.ORDER) }
        fetchEntries(keepItems = false)
    }

    /** 换排序。切到要全量的视图（「按评分」）时把剩下的页取完。 */
    fun selectSort(sort: IndexEntrySort) {
        updateState { it.copy(sort = sort) }
        if (needsAll()) entries.loadAll()
    }

    /** 从第一页取当前类别的收录（[keepItems] 见 [Pager.refresh]）；要全量时（`indexEntriesNeedAll`）接着把剩下的页取完。 */
    private fun fetchEntries(keepItems: Boolean, isOwn: Boolean = currentState.details.valueOrNull()?.isOwn == true) {
        entries.refresh(keepItems)
        if (indexEntriesNeedAll(currentState.sort, isOwn)) entries.loadAll()
    }

    private fun needsAll(): Boolean = indexEntriesNeedAll(currentState.sort, currentState.details.valueOrNull()?.isOwn == true)

    /** 「按评分」取全量失败后的重试。 */
    fun retryLoadAll() = entries.loadAll()

    fun retryComments() = loadComments()

    fun selectCommentSort(sort: TopicSort) = updateState { it.copy(comments = it.comments.withSort(sort)) }

    fun toggleCommentOpOnly() = updateState { it.copy(comments = it.comments.toggleOpOnly()) }

    private fun loadComments() = launchSection(
        key = COMMENTS_SECTION,
        failures = failures,
        source = { loadOnce { commentFloorsOf(indexRepository.getComments(indexId)) } },
        get = { it.comments.floors },
    ) { copy(comments = comments.copy(floors = it)) }

    /**
     * 收藏 / 取消收藏：乐观切换（收藏数跟着 ±1），失败回滚并提示原因。
     * 在途时再点不发第二个请求：失败的回滚用的是这一次点之前的头部，第二次的乐观值会被它盖掉。
     */
    fun toggleCollection() {
        if (currentState.isCollectionLoading) return
        val details = currentState.details.valueOrNull() ?: return
        val target = !details.isCollected
        updateState {
            it.copy(
                details = Load.Ready(details.copy(isCollected = target, collects = collectCountAfter(details.collects, target))),
                isCollectionLoading = true,
            )
        }
        viewModelScope.launch {
            when (val result = indexRepository.setCollected(indexId, target)) {
                is Outcome.Success -> updateState { it.copy(isCollectionLoading = false) }
                is Outcome.Failed -> {
                    updateState {
                        val latest = it.details.valueOrNull()
                        it.copy(
                            details = latest?.let { current ->
                                Load.Ready(current.copy(isCollected = details.isCollected, collects = details.collects))
                            } ?: it.details,
                            isCollectionLoading = false,
                        )
                    }
                    failures.post(result.failure)
                }
            }
        }
    }

    /**
     * 写过之后重取头部（名称、介绍、私密、各类数量）：已显示的头部留着，失败经 [failures] 提示。
     * 收藏状态只归 [toggleCollection] 管：重取的结果不覆盖屏上的收藏与收藏数（重取在途时点了收藏，旧的回包不会把它改回去）。
     */
    private fun refreshDetails() = launchSection(
        key = DETAILS_REFRESH_SECTION,
        failures = failures,
        source = { loadOnce { indexRepository.getIndex(indexId) } },
        get = { it.details },
    ) { fresh ->
        val shown = details.valueOrNull()
        copy(details = if (fresh is Load.Ready && shown != null) Load.Ready(fresh.value.copy(isCollected = shown.isCollected, collects = shown.collects)) else fresh)
    }

    /** 打开「收录链接」面板，框里先放 [prefill]（剪贴板里能收录的链接，没有为空）。 */
    fun openLink(prefill: String) {
        linkGeneration++
        updateState { it.copy(link = IndexLinkState(text = prefill)) }
    }

    fun changeLink(text: String) = updateState { state -> state.copy(link = state.link?.takeUnless { it.adding || it.added }?.copy(text = text) ?: state.link) }

    fun closeLink() {
        linkGeneration++
        updateState { it.copy(link = null) }
    }

    /**
     * 把 [target] 收进这个目录：成功后面板滑走，收录区切到它那一类并从头重取，头部的各类数量重取；
     * 失败时面板与链接留着，原因经 [failures] 提示。面板在途时被滑走，写成功照样刷新列表，只是不再动面板。
     */
    fun addLink(target: IndexTarget) {
        val link = currentState.link ?: return
        if (link.adding || link.added) return
        val owner = linkGeneration
        updateState { it.copy(link = it.link?.copy(adding = true)) }
        viewModelScope.launch {
            when (val result = indexRepository.addEntry(indexId, target)) {
                is Outcome.Success -> {
                    refreshDetails()
                    if (currentState.category == target.category) reloadEntries() else selectCategory(target.category)
                    if (owner == linkGeneration) updateState { it.copy(link = it.link?.copy(adding = false, added = true)) }
                }
                is Outcome.Failed -> {
                    if (owner == linkGeneration) updateState { it.copy(link = it.link?.copy(adding = false)) }
                    failures.post(result.failure)
                }
            }
        }
    }

    /**
     * 从这个目录移出 [entry]：成功后这一行立刻消失，收录从头重取（后面各项的 offset 都前移了一位，续取不能接着旧游标），
     * 头部的各类数量重取。失败时行留着，原因经 [failures] 提示。
     */
    fun removeEntry(entry: IndexEntryBo) {
        viewModelScope.launch {
            when (val result = indexRepository.removeEntry(indexId, entry.id)) {
                is Outcome.Success -> {
                    entries.edit { items -> items.filterNot { it.id == entry.id } }
                    reloadEntries()
                    refreshDetails()
                }
                is Outcome.Failed -> failures.post(result.failure)
            }
        }
    }

    /** 写过之后从头重取当前类别的收录，屏上的行留到新的一页到手；要全量的照旧取完。 */
    private fun reloadEntries() = fetchEntries(keepItems = true)

    /**
     * 拖动排序松手：[ids] 是这一类收录的新顺序。屏上当即换成新顺序，再只写排序值变了的那几条（`indexReorderOf`，改动最少）。
     * 返回 false 表示没接受，屏上回到原来的顺序：现在不能拖（[IndexDetailsUiState.canReorder]）、列表在拖动途中换过，
     * 或要改的条数超过服务端 5 分钟的写入上限（提示原因）。
     * 写失败时仓库已把写成的那几条写回原值：屏上回到拖动前的顺序、提示原因，再从服务端重取一遍（写回没成的以服务端为准）。
     */
    fun moveEntries(ids: List<Int>): Boolean {
        val before = currentState.entries.items
        val current = before.map { it.id }
        if (ids == current) return true
        if (!currentState.canReorder || entries.isActive || ids.sorted() != current.sorted()) return false
        val reorder = indexReorderOf(before, ids)
        if (reorder.changes.size > INDEX_EDITS_PER_WINDOW) {
            failures.post(R.string.index_reorder_too_many, reorder.changes.size, INDEX_EDITS_PER_WINDOW)
            return false
        }
        val category = currentState.category
        entries.edit { reorder.entries }
        updateState { it.copy(isReordering = true) }
        viewModelScope.launch {
            entryWrites.withLock {
                when (val result = indexRepository.reorderEntries(indexId, reorder.changes)) {
                    is Outcome.Success -> updateState { it.copy(isReordering = false) }
                    is Outcome.Failed -> {
                        // 还在这一类、没有取页在途时屏上先回到拖动前，再以服务端为准重取
                        if (currentState.category == category && !entries.isActive) entries.edit { before }
                        updateState { it.copy(isReordering = false) }
                        failures.post(result.failure)
                        reloadEntries()
                    }
                }
            }
        }
        return true
    }

    /** 打开 [entry] 的评语面板，框里是现在的评语。 */
    fun openComment(entry: IndexEntryBo) {
        commentGeneration++
        updateState { it.copy(entryComment = IndexCommentState(entryId = entry.id, order = entry.order, original = entry.comment)) }
    }

    fun changeComment(text: String) =
        updateState { state -> state.copy(entryComment = state.entryComment?.takeUnless { it.saving || it.saved }?.copy(text = text) ?: state.entryComment) }

    /** 面板滑走（取消，或写成功后滑完）：回到关着。 */
    fun closeComment() {
        commentGeneration++
        updateState { it.copy(entryComment = null) }
    }

    /**
     * 存评语：排序值取这一条此刻的值（在 [entryWrites] 里读，前一笔拖动排序写完或写回之后）一起写。
     * 成功后屏上换成新评语、面板滑下去；失败时面板与框里的字都留着，原因经 [failures] 提示。
     */
    fun saveComment() {
        val panel = currentState.entryComment ?: return
        if (!panel.canSave) return
        val owner = commentGeneration
        val text = panel.text.trim()
        updateState { it.copy(entryComment = it.entryComment?.copy(saving = true)) }
        viewModelScope.launch {
            entryWrites.withLock {
                // 面板开着时列表可能重取过（拖动排序失败后）；这一条不在了就用打开时的值，服务端会说它已不在
                val order = currentState.entries.items.firstOrNull { it.id == panel.entryId }?.order ?: panel.order
                when (val result = indexRepository.updateEntry(indexId, panel.entryId, order, text)) {
                    is Outcome.Success -> {
                        // 有取页在途（重取、换类别）时它是这一笔之前发出的，回包里没有新评语：再取一遍；否则就地换掉这一行的评语
                        if (entries.isActive) {
                            reloadEntries()
                        } else {
                            entries.edit { items -> items.map { if (it.id == panel.entryId) it.copy(comment = text) else it } }
                        }
                        if (owner == commentGeneration) updateState { it.copy(entryComment = it.entryComment?.copy(saving = false, saved = true)) }
                    }
                    is Outcome.Failed -> {
                        if (owner == commentGeneration) updateState { it.copy(entryComment = it.entryComment?.copy(saving = false)) }
                        failures.post(result.failure)
                    }
                }
            }
        }
    }

    /** 删除这个目录（确认之后）：成功后 [onDeleted]（页面返回）；失败经 [failures] 提示，留在这一页。 */
    fun deleteIndex() {
        if (currentState.isDeleting || currentState.deleted) return
        updateState { it.copy(isDeleting = true) }
        viewModelScope.launch {
            val result = indexRepository.deleteIndex(indexId)
            updateState { it.copy(isDeleting = false, deleted = result is Outcome.Success) }
            result.failureOrNull()?.let { failures.post(it) }
        }
    }
}

private const val PAGE_SECTION = "page"
private const val COMMENTS_SECTION = "comments"
private const val DETAILS_REFRESH_SECTION = "details-refresh"
