package io.github.touhougo.chogumi.core.reader

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.advance
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.map
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.BlockedUsers
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.FloorReading
import io.github.touhougo.chogumi.core.model.TopicSort
import io.github.touhougo.chogumi.core.model.shownUnder
import io.github.touhougo.chogumi.core.ui.common.Gated
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.StickyHeaderEdge
import io.github.touhougo.chogumi.core.ui.moderation.ModerationAction

/**
 * 一次取到的全部楼层，连同引用反查：反查依赖「更早的楼」，在完整楼层上按时间顺序算一次，换排序 / 过滤不重算。
 */
data class CommentFloors(val threads: List<CommentThreadBo>, val quoteTargets: Map<Int, Int>) {
    /** 改楼层的内容（贴贴）：楼与楼之间的引用不变，反查不重算。 */
    fun withThreads(transform: (List<CommentThreadBo>) -> List<CommentThreadBo>) = copy(threads = transform(threads))
}

/** 取到的楼层 → [CommentFloors]（引用反查在这里算一次）。 */
fun commentFloorsOf(threads: List<CommentThreadBo>) = CommentFloors(threads, commentQuoteTargets(threads))

/**
 * 楼层评论区的状态（目录、日志的留言、角色与人物的吐槽箱、单集评论与动态回复共用）。接口一次返回全部楼层，不分页。
 *
 * @property floors 楼层这一区块：宿主是页面 ViewModel 时由 `collectSection` 填，三态由 [floorCommentsSection] 按
 *   `sectionStateOf` 画。
 * @property opCreatorId 楼主（目录作者 / 日志作者 / 发动态的人；吐槽箱与单集评论没有），随正文到位，与楼层谁先到无关。
 * @property blocked 宿主跟着的当前账号绝交名单（`BlockedUsersFeed`，经 [withBlocked] 写入）：绝交后这个人的楼当场不显示。
 *   还没读到（null）时楼层按等待画（[shown]），读到了才一次画出来，被绝交的人的楼不先冒出来再消失。
 */
data class FloorComments(
    val floors: Load<CommentFloors> = Load.Pending,
    val opCreatorId: Int? = null,
    val sort: TopicSort = TopicSort.FLOOR,
    val opOnly: Boolean = false,
    val blocked: BlockedUsers? = null,
) {
    /** 要画的楼层这一区块：[floors] 在名单下算不算到了（[shownUnder]），三态由 [floorCommentsBody] 按它画。 */
    val shown: Load<CommentFloors> = floors.shownUnder(blocked)

    /** 按当前排序 /「只看楼主」读楼层：正文不在楼层里，留言全部参与排序（`firstIsPost = false`）；楼层没到时是空的。 */
    val reading: FloorReading = FloorReading(
        threads = floors.valueOrNull()?.threads.orEmpty(),
        allQuoteTargets = floors.valueOrNull()?.quoteTargets.orEmpty(),
        opCreatorId = opCreatorId,
        firstIsPost = false,
        sort = sort,
        opOnly = opOnly,
        blocked = blocked,
    )

    /** 楼层往前走一拍（[Load.advance]：已显示的楼层不被等待与失败抹掉）。 */
    fun advance(next: Load<CommentFloors>) = copy(floors = floors.advance(next))

    /** 改已显示楼层的内容（贴贴的乐观更新与退回）。 */
    fun withThreads(transform: (List<CommentThreadBo>) -> List<CommentThreadBo>) = copy(floors = floors.map { it.withThreads(transform) })

    fun withOp(opCreatorId: Int?) = copy(opCreatorId = opCreatorId)

    fun withSort(sort: TopicSort) = copy(sort = sort)

    fun toggleOpOnly() = copy(opOnly = !opOnly)

    fun withBlocked(blocked: BlockedUsers) = copy(blocked = blocked)
}

/**
 * 楼层评论区（目录、日志的留言、单集评论与动态的回复共用），与讨论版话题页同一套阅读骨架：吸顶排序行（「评论区」+ 排序 +「只看楼主」）
 * → 楼层（[floorCommentsBody]）。
 *
 * @param surface 评论所在处：决定能不能回复、分不分两层（[replyStyleOf]）、能不能贴贴（[reactionsOf]；日志、目录没有贴贴接口）。
 * @param onAvatarClick 点了头像（参数是楼 id）：用户卡片在屏幕层，由 [UserCardState] 持有、[CommentUserCard] 画。
 * @param onReact 贴贴；null 表示这里不能贴（同 [commentFloorItems]）。
 * @param onReply 点楼层开编辑器回复那一层（同 [commentFloorItems]）；null 时点楼层不响应。
 * @param onModerate 长按别人的楼时的报告、加好友、绝交（同 [commentFloorItems]）；null 时菜单里没有这三项。
 * @param sortEdge 这个列表的吸顶项柔边（[rememberStickyHeaderEdge]）：吸顶排序行的底色取它（[StickyHeaderEdge.containerColor]）。
 */
fun LazyListScope.floorCommentsSection(
    comments: FloorComments,
    surface: CommentSurface,
    jump: FloorJump,
    @StringRes emptyText: Int,
    onSelectSort: (TopicSort) -> Unit,
    onToggleOpOnly: () -> Unit,
    onUserClick: (String) -> Unit,
    onAvatarClick: (commentId: Int) -> Unit,
    onRetry: () -> Unit,
    onReact: Gated<(itemId: Int, value: Int?) -> Unit>? = null,
    onReply: Gated<(ReplyTarget) -> Unit>? = null,
    onModerate: Gated<(ModerationAction) -> Unit>? = null,
    sortEdge: StickyHeaderEdge,
) {
    stickyHeader(key = "comments-sort") {
        CommentSortBar(
            reading = comments.reading,
            onSelectSort = onSelectSort,
            onToggleOpOnly = onToggleOpOnly,
            containerColor = sortEdge.containerColor,
        )
    }
    floorCommentsBody(comments, surface, jump, emptyText, onUserClick, onAvatarClick, onRetry, onReact, onReply, onModerate)
}

/**
 * 评论区的楼层部分，三态只由 `sectionStateOf` 判定：没到时画几层楼的占位（不转圈）、取失败且没有可显示的画重试、
 * 到了逐条画楼层（[commentFloorItems]，没有楼层时是 [emptyText]）。[floorCommentsSection] 在它上面加吸顶排序行；
 * 评论区只是详情页里一段的（角色、人物的吐槽箱）用自己的区块标题接它。参数同 [floorCommentsSection]。
 */
fun LazyListScope.floorCommentsBody(
    comments: FloorComments,
    surface: CommentSurface,
    jump: FloorJump,
    @StringRes emptyText: Int,
    onUserClick: (String) -> Unit,
    onAvatarClick: (commentId: Int) -> Unit,
    onRetry: () -> Unit,
    onReact: Gated<(itemId: Int, value: Int?) -> Unit>? = null,
    onReply: Gated<(ReplyTarget) -> Unit>? = null,
    onModerate: Gated<(ModerationAction) -> Unit>? = null,
) {
    when (sectionStateOf(comments.shown) { it.threads.isEmpty() }) {
        SectionState.LOADING -> item(key = "comments-loading") { CommentFloorsSkeleton() }
        SectionState.ERROR -> item(key = "comments-error") {
            comments.shown.failureOrNull()?.let { RetryError(it, onRetry = onRetry, Modifier.fillMaxWidth()) }
        }
        // 没有留言也走楼层：空态文字由它画（「只看楼主」滤空时同一句）
        SectionState.EMPTY, SectionState.CONTENT -> commentFloorItems(
            reading = comments.reading,
            emptyText = emptyText,
            jump = jump,
            surface = surface,
            onUserClick = onUserClick,
            onAvatarClick = onAvatarClick,
            onReact = onReact,
            onReply = onReply,
            onModerate = onModerate,
        )
    }
}
