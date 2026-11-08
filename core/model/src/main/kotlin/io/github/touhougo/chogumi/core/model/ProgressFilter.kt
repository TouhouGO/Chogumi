package io.github.touhougo.chogumi.core.model

/** 一行的逐集数据到了哪一步，由 [episodeDataState] 判定。 */
enum class EpisodeDataState {
    /** 章节或逐集状态的请求还没返回：EP 槽位画占位。 */
    LOADING,

    /** 请求失败、手上的数据又不齐：终态，不再等，槽位按「没有可点的进度动作」留空，重试成功后再变回按钮。 */
    FAILED,

    /** 数据齐了，或这个类型本就不靠逐集推进：按 [EpisodeTracking.epButtonState] 算按钮。 */
    READY,
}

/**
 * 逐集数据到了哪一步——**唯一判定点**。
 *
 * 动画/三次元要章节与逐集状态都到齐才算 [EpisodeDataState.READY]；其余类型本就不靠逐集推进，恒为 READY。
 * 消费方（进度页 EP 按钮）用它决定「下一集」按钮能不能算得准，不得各自再写一遍。
 *
 * [episodes] 为 null 表示章节请求还没返回；**空列表是「已返回、确实零集」**（如 `eps = 0` 的条目，
 * subject 326125），不能当成加载中，否则槽位会挂一个永久的占位。
 *
 * [watchStates] 同一个口径：null 是逐集请求还没返回，空表是已返回、一行都没有（还没录入章节的新番）。
 * 调用方不能拿「本地逐集表里有没有这部的行」当它：零行的条目在表里没有行，按那个判就永远是加载中。
 *
 * [loadFailed] 为 true 表示这一轮的章节或逐集请求失败了。数据齐了仍是 READY（比如逐集状态后来从别处到了）；
 * 不齐才是 [EpisodeDataState.FAILED]。失败不能当 READY 往下算：章节已到、逐集状态没有时，
 * 「一集都没看」算出的下一集必然是第 1 集，那是错的。
 */
fun episodeDataState(
    subjectType: SubjectType,
    episodes: List<EpisodeBo>?,
    watchStates: Map<Int, EpisodeStatus>?,
    loadFailed: Boolean,
): EpisodeDataState = when {
    !EpisodeTracking.tracksEpisodes(subjectType) || (episodes != null && watchStates != null) -> EpisodeDataState.READY
    loadFailed -> EpisodeDataState.FAILED
    else -> EpisodeDataState.LOADING
}

/**
 * 筛选面板里的一项（一个标签，或分类的一个取值）：这一批里有几部（[count]），以及其中最近动过的几部
 * （[recent]，最近的在前，最多 [FILTER_OPTION_COVERS] 部，格子左边的封面）。
 * 标签与分类取值是同一种格子，所以也是同一种数据，只由 [filterOptionsOf] 算。
 */
data class ProgressFilterOption<out K>(val key: K, val count: Int, val recent: List<SubjectBo>)

/** 筛选格左边最多叠几张封面。 */
private const val FILTER_OPTION_COVERS = 3

/**
 * 每个键（标签、分类取值）在这一批里的 [ProgressFilterOption]——**唯一的计数与取封面处**。
 * 「最近动过」按 [lastActiveAt]（与「最近操作」排序同一个时刻）从新到旧，同一时刻按 `subjectId`；
 * 返回顺序是各键第一次出现在这个次序里的顺序，调用方再按自己的规则排。
 */
internal fun <K> filterOptionsOf(
    items: List<UserCollectionBo>,
    latestWatchedAt: Map<Int, Long>,
    keysOf: (UserCollectionBo) -> Collection<K>,
): List<ProgressFilterOption<K>> {
    val byRecent = items.sortedWith(
        compareByDescending<UserCollectionBo> { lastActiveAt(it.updatedAt, latestWatchedAt[it.subjectId]) }.thenBy { it.subjectId },
    )
    val members = LinkedHashMap<K, MutableList<UserCollectionBo>>()
    byRecent.forEach { item -> keysOf(item).distinct().forEach { members.getOrPut(it) { mutableListOf() } += item } }
    return members.map { (key, works) ->
        ProgressFilterOption(key, works.size, works.mapNotNull { it.subject }.take(FILTER_OPTION_COVERS))
    }
}

/**
 * 「标签」组可选的标签——**唯一判定点**：这批收藏里用户自己打过的标签，按部数从多到少，同部数按名称（[titleOrder]）。
 *
 * 传入的应当是**类型 tab + 收藏状态筛过、但未经本面板筛过**的那一批：
 * 若把标签自己也纳入计算，选中一个标签就会让其余标签消失，用户再也取消不掉。
 */
fun availableProgressTags(collections: List<UserCollectionBo>, latestWatchedAt: Map<Int, Long>): List<ProgressFilterOption<String>> {
    val names = titleOrder()
    return filterOptionsOf(collections, latestWatchedAt) { item -> item.tags.filter { it.isNotBlank() } }
        .sortedWith(compareByDescending<ProgressFilterOption<String>> { it.count }.thenComparing({ it.key }, names))
}

/**
 * 面板里标签的顺序：[pinned]（面板打开那一刻已选的标签）在前，其余保持 [availableProgressTags] 的次序。
 * [pinned] 在面板开着时不变，所以点选不会让格子挪位置。
 */
fun progressPanelTags(options: List<ProgressFilterOption<String>>, pinned: Set<String>): List<ProgressFilterOption<String>> =
    options.sortedBy { it.key !in pinned }

/**
 * 「标签」筛选——**唯一判定点**：命中任一选中标签即通过（组内取并集）。
 * 一个都没选表示没有筛选，恒通过。
 */
fun matchesTagFilter(tags: List<String>, selected: Set<String>): Boolean =
    selected.isEmpty() || tags.any { it in selected }
