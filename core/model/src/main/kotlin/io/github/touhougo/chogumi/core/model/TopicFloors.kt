package io.github.touhougo.chogumi.core.model

/** 楼层排序（话题、日志留言、目录留言共用；排序下拉的三项，声明顺序即菜单顺序）。 */
enum class TopicSort {
    /** 楼层顺序（服务端顺序，即发帖时间递增）。 */
    FLOOR,

    /** 最热：贴贴总数 + 楼中楼条数 × 2，见 [topicFloorHeat]。 */
    HOT,

    /** 最新：楼层顺序倒过来。 */
    LATEST,
}

/** 一层楼与它的楼层号（1 起）。楼层号取**原始**顺序：换了排序、开了「只看楼主」都不变。 */
data class TopicFloor(val number: Int, val thread: CommentThreadBo)

/**
 * 要显示的楼层（唯一判定点）。
 *
 * - [firstIsPost]：话题的 #1 楼是帖子正文本身，任何排序下都固定在最前；日志 / 目录的正文不在楼层里，留言全部参与排序；
 * - [opOnly]：只留楼主发过言的串——主楼是楼主的，或楼主在楼中楼里回复过；整串保留以免断了上下文。
 *   已删除的楼不留（固定的 #1 楼除外：它是话题本身）。[opCreatorId] 为 null（楼主未知）时不过滤；
 * - [blocked] 里的人写的楼不显示（[visibleThread]）：楼层号仍按原始顺序，所以会跳号。固定的 #1 楼是话题本身，只去掉其中绝交用户的楼中楼；
 * - 排序稳定：热度相同的按楼层顺序。
 */
fun topicFloors(
    threads: List<CommentThreadBo>,
    sort: TopicSort,
    opOnly: Boolean,
    opCreatorId: Int?,
    firstIsPost: Boolean = true,
    blocked: BlockedUsers = BlockedUsers.NONE,
): List<TopicFloor> {
    val numbered = threads.mapIndexed { index, thread -> TopicFloor(index + 1, thread) }
    val head = if (firstIsPost) numbered.take(1).map { it.copy(thread = it.thread.withoutBlockedReplies(blocked)) } else emptyList()
    val rest = numbered.drop(head.size)
        .mapNotNull { floor -> floor.thread.visibleThread(blocked)?.let { floor.copy(thread = it) } }
        .filter { !opOnly || opCreatorId == null || it.thread.hasPostBy(opCreatorId) }
    val sorted = when (sort) {
        TopicSort.FLOOR -> rest
        TopicSort.HOT -> rest.sortedByDescending { topicFloorHeat(it.thread) }
        TopicSort.LATEST -> rest.reversed()
    }
    return head + sorted
}

/**
 * 一页楼层的阅读视图（话题、日志留言、目录留言共用）：原始楼层 + 排序 /「只看楼主」+ 绝交名单 → 显示楼层、引用落点、可点的引用。
 * 三个派生值只在这里经 [topicFloors] 算，换排序、开关「只看楼主」、名单变了就是 copy 一份。
 *
 * @property allQuoteTargets 全部楼层的引用反查（楼 id → 被引楼 id）：依赖「更早的楼」，调用方在完整楼层上按时间顺序算一次，
 *   排序 / 过滤变化时不重算。
 * @property quoteTargets 只保留落点仍在 [floors] 里的：被滤掉的不给跳。
 * @property blocked 页面当前跟着的绝交名单（[visibleThread]）：绝交后名单一变，这层楼当场从 [floors] 里拿掉。还没读到（null）时
 *   一层楼也不给（[shownUnder]：楼层这一区块仍算等待），不按「谁也不屏蔽」先给出去。
 */
data class FloorReading(
    val threads: List<CommentThreadBo> = emptyList(),
    val allQuoteTargets: Map<Int, Int> = emptyMap(),
    val opCreatorId: Int? = null,
    val firstIsPost: Boolean = true,
    val sort: TopicSort = TopicSort.FLOOR,
    val opOnly: Boolean = false,
    val blocked: BlockedUsers? = null,
) {
    /** 全部要显示的楼层（[firstIsPost] 时帖子正文在第 0 项）；引用落点按它的下标算。名单还没读到时为空。 */
    val floors: List<TopicFloor> = blocked?.let { topicFloors(threads, sort, opOnly, opCreatorId, firstIsPost, it) }.orEmpty()

    /** 帖子正文（话题 #1 楼），画在标题下方、排序行之上；没有正文概念（日志 / 目录留言）时为 null。 */
    val post: TopicFloor? = if (firstIsPost) floors.firstOrNull() else null

    /** 排序行之下的回复楼层：[floors] 去掉 [post]。排序、「只看楼主」、空态都只看它。 */
    val replies: List<TopicFloor> = if (post != null) floors.drop(1) else floors
    val floorIndexByPost: Map<Int, Int> = floors.floorIndexByPost()
    val quoteTargets: Map<Int, Int> = allQuoteTargets.filterValues { it in floorIndexByPost }
}

/** 楼层热度：贴贴总数 + 楼中楼条数 × 2；已删除的楼为 -1（沉到最后）。 */
fun topicFloorHeat(thread: CommentThreadBo): Int =
    if (thread.anchor.state == CommentState.DELETED) {
        -1
    } else {
        thread.anchor.reactions.sumOf { it.count } + thread.replies.size * 2
    }

/** 这一串里是否有 [userId] 的楼（已删除的主楼不算）。 */
private fun CommentThreadBo.hasPostBy(userId: Int): Boolean =
    anchor.state != CommentState.DELETED &&
        (anchor.creatorId == userId || replies.any { it.creatorId == userId })

/**
 * 楼 id（主楼与楼中楼）→ 它所在的项在 [this] 中的下标。引用跳转按它滚动；不在其中的楼（被「只看楼主」滤掉）没有键。
 * 一次建表：大帖有几千层楼，逐条线性查找会在每次跳转与每次过滤引用时重复扫描。
 */
fun List<TopicFloor>.floorIndexByPost(): Map<Int, Int> = buildMap {
    this@floorIndexByPost.forEachIndexed { index, floor ->
        put(floor.thread.anchor.id, index)
        floor.thread.replies.forEach { put(it.id, index) }
    }
}
