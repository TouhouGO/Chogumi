package io.github.touhougo.chogumi.feature.monodetails

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.paging.Pager
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.BlockedUsersFeed
import io.github.touhougo.chogumi.core.data.repository.CommentRepository
import io.github.touhougo.chogumi.core.data.repository.MonoRepository
import io.github.touhougo.chogumi.core.data.util.loadOnce
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.AppTime
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CollectionEditing
import io.github.touhougo.chogumi.core.model.CollectionMarker
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.MonoRelationBo
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.collectCountAfter
import io.github.touhougo.chogumi.core.model.UserModeration
import io.github.touhougo.chogumi.core.model.monoTypeSpec
import io.github.touhougo.chogumi.core.reader.ReplyTarget
import io.github.touhougo.chogumi.core.reader.commentFloorsOf
import io.github.touhougo.chogumi.core.reader.replyComposer
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkController
import io.github.touhougo.chogumi.core.ui.moderation.ModerationController
import kotlinx.coroutines.launch

/**
 * 人物与角色详情 ViewModel。
 *
 * 1. 角色与人物共用一套（[monoType] 决定请求端点）；
 * 2. 详情决定整页（失败 = 整页重试）；作品（全量）、代表角色、关联、目录、吐槽箱各自并发、局部容错；
 *    吐槽箱能写吐槽、回复楼层（[composer]）；
 * 3. 收藏乐观写：立即翻转 [MonoDetailsUiState.isCollected] 与 [MonoDetailsUiState.collectCount]，
 *    成功不重拉详情（不闪、不丢滚动位置），失败回滚并经 [failures] 提示。
 */
class MonoDetailsViewModel(
    val monoType: MonoType,
    val id: Int,
    private val monoRepository: MonoRepository,
    comments: CommentRepository,
    collectionMarker: CollectionMarker,
    collectionEditing: CollectionEditing,
    appTime: AppTime,
    userModeration: UserModeration,
    blockedUsers: BlockedUsersFeed,
) : StateViewModel<MonoDetailsUiState>(
    MonoDetailsUiState(
        id = id,
        monoType = monoType,
        // 没有「代表角色」一区的（角色）：一开始就是空的就绪，不参与等待，界面按空区块不画
        characters = if (monoTypeSpec(monoType).hasCastCharacters) Load.Pending else Load.Ready(PagedResult(emptyList(), 0, null)),
    ),
) {

    /** 本页的失败提示：收藏写入失败、参与作品海报上长按标记的提交失败。 */
    val failures = FailureNotices()

    /** 吐槽箱楼层长按菜单里的报告、加好友、绝交（[ModerationController]），失败经 [failures] 提示。 */
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

    /** 参与作品海报的长按标记：标状态、撤销、收藏管理面板。 */
    val posterMarks = PosterMarkController(
        scope = viewModelScope,
        read = { currentState.posterMarks },
        write = { change -> updateState { it.copy(posterMarks = change(it.posterMarks)) } },
        failures = failures,
        marker = collectionMarker,
        editing = collectionEditing,
        today = appTime.today,
    )

    private val indexesPager = Pager(
        scope = viewModelScope,
        read = { currentState.indexes },
        write = { transform -> updateState { it.copy(indexes = transform(it.indexes)) } },
    ) { offset ->
        outcomeOf { monoRepository.getIndexes(monoType, id, limit = INDEX_PAGE_SIZE, offset = offset) }
    }

    /** 写吐槽（新的一条）或回复一层楼：与讨论版同一个编辑器，发出后重拉吐槽箱。 */
    val composer = replyComposer(
        scope = viewModelScope,
        state = { currentState.composer },
        update = { transform -> updateState { it.copy(composer = transform(it.composer)) } },
        send = { content, replyTo, token -> comments.reply(CommentSurface.Mono(monoType, id), content, replyTo, token) },
        reload = {
            loadComments()
            joinSection(COMMENTS_SECTION)
        },
        drafts = comments.replyDrafts(CommentSurface.Mono(monoType, id), ReplyTarget::postId),
    )

    private var commentsWanted = false

    /** 收藏写入与详情读取共用的版本；发出读取后有过写入，旧详情不再播种按钮和计数。 */
    private var collectionWriteVersion = 0L

    init {
        loadData(isRefresh = false)
    }

    fun refresh() = loadData(isRefresh = true)

    fun retryWorks() = loadWorks(refresh = false)

    fun retryCharacters() = loadCharacters()

    fun retryRelations() = loadRelations()

    fun retryIndexes() {
        if (currentState.indexes.items.isEmpty()) {
            indexesPager.refresh()
        } else {
            indexesPager.reload(showIndicator = false) { failures.post(it, retry = ::retryIndexes) }
        }
    }

    fun loadMoreIndexes() = indexesPager.loadMore()

    fun retryComments() = loadComments()

    /** 吐槽区出现在屏幕上（社区功能开着）才取；专注模式下这一块不渲染，也就不为它发请求。 */
    fun startComments() {
        if (commentsWanted) return
        commentsWanted = true
        loadComments()
    }

    fun toggleCollection() {
        if (currentState.isCollectionLoading) return
        val before = currentState
        ++collectionWriteVersion
        val collect = !before.isCollected
        updateState {
            it.copy(isCollected = collect, collectCount = collectCountAfter(before.collectCount, collect), isCollectionLoading = true)
        }
        viewModelScope.launch {
            when (val result = monoRepository.setCollected(monoType, id, collect)) {
                is Outcome.Success -> updateState { it.copy(isCollectionLoading = false) }
                is Outcome.Failed -> {
                    updateState {
                        it.copy(isCollected = before.isCollected, collectCount = before.collectCount, isCollectionLoading = false)
                    }
                    failures.post(result.failure)
                }
            }
        }
    }

    private fun loadData(isRefresh: Boolean) {
        // 下拉转圈只属于有内容在屏上的刷新；整页失败后的重试画骨架，不再叠转圈
        if (isRefresh) updateState { if (it.details is Load.Ready) it.copy(isRefreshing = true) else it }
        loadPrimaryDetails()
        loadWorks(refresh = isRefresh)
        if (monoTypeSpec(monoType).hasCastCharacters) loadCharacters()
        loadRelations()
        loadIndexes(isRefresh)
        if (commentsWanted) loadComments()
    }

    /** 没有内容才整页报错；有内容时（下拉刷新）内容留着，失败走提示条。详情落下时收藏状态以它为起点。 */
    private fun loadPrimaryDetails() {
        val version = collectionWriteVersion
        launchSection(
            "details",
            failures,
            { loadOnce { monoRepository.getDetails(monoType, id) } },
            { it.details },
            alongside = { load ->
                when (load) {
                    is Load.Ready -> if (version == collectionWriteVersion && !isCollectionLoading) {
                        copy(isCollected = load.value.isCollected, collectCount = load.value.collectCount, isRefreshing = false)
                    } else {
                        copy(isRefreshing = false)
                    }
                    is Load.Failed -> copy(isRefreshing = false)
                    Load.Pending -> this
                }
            },
        ) { copy(details = it) }
    }

    /** 全部作品（仓库逐页取齐并缓存；下拉刷新时重取）。 */
    private fun loadWorks(refresh: Boolean) =
        launchSection("works", failures, { loadOnce { monoRepository.getAllWorks(monoType, id, refresh) } }, { it.works }) {
            copy(works = it)
        }

    /** 人物演过的角色（代表角色）：第一页。 */
    private fun loadCharacters() = launchSection(
        "characters",
        failures,
        { loadOnce { monoRepository.getPersonCharacters(id, limit = REPRESENTATIVE_CHARACTER_COUNT) } },
        { it.characters },
    ) { copy(characters = it) }

    private fun loadRelations() = launchSection(
        "relations",
        failures,
        { loadOnce { monoRepository.getRelations(monoType, id).items.mergeByEntity() } },
        { it.relations },
    ) { copy(relations = it) }

    private fun loadIndexes(refresh: Boolean) {
        if (refresh && currentState.indexes.items.isNotEmpty()) {
            indexesPager.reload(showIndicator = false) { failures.post(it, retry = ::retryIndexes) }
        } else {
            indexesPager.refresh()
        }
    }

    private fun loadComments() = launchSection(
        COMMENTS_SECTION,
        failures,
        { loadOnce { commentFloorsOf(monoRepository.getComments(monoType, id)) } },
        { it.comments.floors },
    ) { copy(comments = comments.copy(floors = it)) }
}

/** 同一人物 / 角色的多条关系合成一张卡：信息取首次出现的那条，关系文案按出现顺序去重后用「 / 」连接。 */
internal fun List<MonoRelationBo>.mergeByEntity(): List<MonoRelationBo> = groupBy { it.id }.values.map { same ->
    same.first().copy(relation = same.map { it.relation }.filter { it.isNotBlank() }.distinct().joinToString(" / "))
}

private const val COMMENTS_SECTION = "comments"

/** 角色 / 人物关联目录每页条数。 */
private const val INDEX_PAGE_SIZE = 30

/** 人物页「代表角色」横排露出几个（原型露 10 个；设计决定，not in M3）。 */
private const val REPRESENTATIVE_CHARACTER_COUNT = 10
