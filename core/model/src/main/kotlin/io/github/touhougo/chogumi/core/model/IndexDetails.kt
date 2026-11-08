package io.github.touhougo.chogumi.core.model

/**
 * 目录详情头部。
 *
 * @property typeCounts 各条目类型收录了多少（只含非零项，按 [SubjectType] 声明顺序）：头部的类型 chip。
 * @property categoryCounts 各收录类别有多少（只含非零项，按 [IndexCategory] 声明顺序；作品 = [typeCounts] 之和）：
 *   收录区的类别切换与「收录 N」。
 * @property isCollected 当前登录用户是否收藏了这个目录（服务端 `collectedAt` 非空）；未登录恒为 false。
 * @property isPrivate 私密目录：只有创建者看得到。
 * @property isOwn 是当前登录账号创建的（取数时按当前会话判定，界面不再比对 id）：只有自己的目录能编辑、删除、收录、移出。
 */
data class IndexDetailsBo(
    val id: Int,
    val title: String,
    val description: String = "",
    val total: Int = 0,
    val collects: Int = 0,
    val replies: Int = 0,
    val typeCounts: List<Pair<SubjectType, Int>> = emptyList(),
    val categoryCounts: List<Pair<IndexCategory, Int>> = emptyList(),
    val createdAt: Long = 0L,
    /** epoch 毫秒；0 表示未知 */
    val updatedAt: Long = 0L,
    val creator: UserBo? = null,
    val isCollected: Boolean = false,
    val isPrivate: Boolean = false,
    val isOwn: Boolean = false,
)

/**
 * 目录收录的类别（p1 `IndexRelatedCategory` 的取值，[code] 是线上的数）：旧站「添加关联」的 7 个页签，
 * 声明顺序就是页签顺序。全仓按类别分派都从这一个枚举出发。
 */
enum class IndexCategory(val code: Int) {
    SUBJECT(0), CHARACTER(1), PERSON(2), EPISODE(3), BLOG(4), GROUP_TOPIC(5), SUBJECT_TOPIC(6);

    companion object {
        /** 线上的数 → 类别；不认识的数（服务端新加的类别）为 null。 */
        fun fromCode(code: Int): IndexCategory? = entries.find { it.code == code }
    }
}

/** 能收进目录的一样东西：类别 + 它自己的 id（作品 id、角色 id、章节 id、日志 id、话题 id）。 */
data class IndexTarget(val category: IndexCategory, val id: Int)

/** 话题收进目录时的类别（唯一映射）：小组话题与作品讨论是两类。 */
fun indexCategoryOf(type: TopicType): IndexCategory = when (type) {
    TopicType.SUBJECT -> IndexCategory.SUBJECT_TOPIC
    TopicType.GROUP -> IndexCategory.GROUP_TOPIC
}

/**
 * 我创建的一个目录，和某样东西在不在里面：[relatedId] 是那一条收录自己的 id（移出时用），不在里面为 null。
 * 收录面板的勾选初值只从这里来。
 */
data class IndexMembership(val index: IndexBo, val relatedId: Int?)

/** 打开目录时落在哪一类：第一个有收录的类别（按 [IndexCategory] 声明顺序）；都没有时是作品。 */
fun defaultIndexCategory(details: IndexDetailsBo): IndexCategory =
    details.categoryCounts.firstOrNull()?.first ?: IndexCategory.SUBJECT

/**
 * 目录里收录的一项（[category] 七类之一）。
 *
 * @property targetId 被收录的东西的 id（作品、角色、人物、章节、日志、话题）。
 * @property cover 作品是 3:4 封面；角色 / 人物是整张立绘（界面顶对齐裁圆）；其余类别没有图。
 * @property info 服务端的自由文本（作品形如 `1话 / 1987年2月23日 / …`，副行取前两段 [subjectInfoHead]）。
 * @property parentName 章节、作品讨论所属作品的显示名，小组话题所在小组的名字，日志作者的显示名；其余类别为空。
 * @property episodeSort 章节的集号（显示经 [formatEpisodeSort]）；其余类别为 null。
 * @property comment 收录理由（目录作者写的评语）。
 * @property order 服务端的排序值：收录按它升序、同值按 [id] 升序排（[indexReorderOf]）；新收录的是 0。
 */
data class IndexEntryBo(
    /** related 行自身 id */
    val id: Int,
    val targetId: Int,
    val category: IndexCategory = IndexCategory.SUBJECT,
    override val name: String,
    override val nameCn: String = "",
    val cover: String? = null,
    val subjectType: SubjectType = SubjectType.UNKNOWN,
    val info: String = "",
    val parentName: String = "",
    val episodeSort: Double? = null,
    val comment: String = "",
    val order: Int = 0,
    override val ratingScore: Double = 0.0,
    override val ratingTotal: Int = 0,
    override val date: SubjectYearMonth? = null,
) : NamedEntity, WorkSortKey {
    val target: IndexTarget get() = IndexTarget(category, targetId)
}

/** 目录收录列表的排序：收录顺序（服务端顺序）或按评分。 */
enum class IndexEntrySort { ORDER, RATING }

/** 可选的排序（唯一判定）：只有作品有评分可排；公共评分关掉时也只有收录顺序。 */
fun indexEntrySortOptions(category: IndexCategory, publicRatings: Boolean): List<IndexEntrySort> =
    if (publicRatings && category == IndexCategory.SUBJECT) {
        listOf(IndexEntrySort.ORDER, IndexEntrySort.RATING)
    } else {
        listOf(IndexEntrySort.ORDER)
    }

/** 当前这一类收录要不要取全（唯一判定）：按评分排要全量；自己的目录能拖动排序，新的排序值要按整列算（[indexReorderOf]）。 */
fun indexEntriesNeedAll(sort: IndexEntrySort, isOwn: Boolean): Boolean = sort == IndexEntrySort.RATING || isOwn

/**
 * 目录收录排序（唯一真源）。[IndexEntrySort.RATING] 走 [sortByRating]，输入必须是全量；
 * 公共评分关掉时一律收录顺序（他人评分不可见，不能拿它排序）。
 */
fun sortIndexEntries(entries: List<IndexEntryBo>, sort: IndexEntrySort, publicRatings: Boolean): List<IndexEntryBo> =
    when (if (publicRatings) sort else IndexEntrySort.ORDER) {
        IndexEntrySort.ORDER -> entries
        IndexEntrySort.RATING -> sortByRating(entries)
    }

/** 条目 `info` 的前两段（`1话 / 1987年2月23日 / 导演…` → `[1话, 1987年2月23日]`），空段丢掉。 */
fun subjectInfoHead(info: String): List<String> =
    info.split(" / ").map { it.trim() }.filter { it.isNotEmpty() }.take(SUBJECT_INFO_HEAD_PARTS)

private const val SUBJECT_INFO_HEAD_PARTS = 2
