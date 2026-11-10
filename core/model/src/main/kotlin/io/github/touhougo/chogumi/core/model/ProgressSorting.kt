package io.github.touhougo.chogumi.core.model

import java.text.Collator
import java.util.Locale

/**
 * 进度页排序档。**唯一枚举**；各档的比较规则只由 [sortProgress] 实现，
 * 界面与 ViewModel 都不得另写 `when(sort)` 自己比较。
 *
 * - [RECENT]：最近操作（口径见 [lastActiveAt]），默认新到旧；
 * - [LATEST_AIRED]：最近更新，即最后一集已播出正片的播出日（[EpisodeTracking.lastAiredEpisode]），默认新到旧；
 *   只有按集推进的类型有（[progressSortsFor]）；
 * - [DATE]：作品日期（放送或发售，`SubjectBo.date`），默认新到旧；
 * - [TITLE]：名称（`nameCn` 优先，空白时回退 `name`），按拼音排（[titleOrder]），默认 A 到 Z；
 * - [PUBLIC_RATING]：公共评分（`SubjectBo.ratingScore`），默认高到低；关掉公共评分时没有这一档（[progressSortsFor]）；
 * - [MY_RATING]：个人评分（收藏里自己打的分 `UserCollectionBo.rate`），默认高到低。
 *
 * 没有这一档的值的作品（未评分、没有逐集数据、没有日期、没有名称）**不论方向都排在最后**（见 [sortProgress]）。
 */
enum class ProgressSort { RECENT, LATEST_AIRED, DATE, TITLE, PUBLIC_RATING, MY_RATING }

/**
 * 列表按什么排：档位与方向。[reversed] 为 false 是这一档的默认方向（见 [descending]）。
 * 换档回到默认方向；方向在排序菜单的第二组里单独选。
 */
data class ProgressOrder(val sort: ProgressSort = ProgressSort.RECENT, val reversed: Boolean = false) {
    /**
     * 是否「大 / 新 / 高在前」——**唯一判定点**：[sortProgress] 的比较器与排序菜单里两种顺序的叫法都从这里取。
     * 名称是唯一默认「小在前」（A 到 Z）的档，所以按档位判，不能写成「默认一律降序」。
     */
    val descending: Boolean
        get() = if (sort == ProgressSort.TITLE) reversed else !reversed

    /**
     * 在 [type] 这一页实际按什么排：这一页没有选中的档（书籍没有「最近更新」、关掉公共评分时没有「公共评分」）时
     * 按最近操作的默认方向。[publicRatings] 是 `AppFeatures.publicRatings`。
     */
    fun on(type: SubjectType?, publicRatings: Boolean): ProgressOrder =
        if (sort in progressSortsFor(type, publicRatings)) this else ProgressOrder()
}

/**
 * 某一页（[type]，null = 全部）排序菜单里有哪些档——**唯一判定点**。
 * 「最近更新」要逐集数据，只给按集推进的类型（[EpisodeTracking.tracksEpisodes]）与混排的「全部」；
 * 「公共评分」是他人的评分，只在公共评分开着时有（[publicRatings] = `AppFeatures.publicRatings`）。个人评分一直有。
 */
fun progressSortsFor(type: SubjectType?, publicRatings: Boolean): List<ProgressSort> =
    ProgressSort.entries.filter { sort ->
        when (sort) {
            ProgressSort.LATEST_AIRED -> type == null || EpisodeTracking.tracksEpisodes(type)
            ProgressSort.PUBLIC_RATING -> publicRatings
            else -> true
        }
    }

/**
 * 「最近操作时刻」——**唯一判定点**：收藏记录的更新时间与该作品最后一次「看过某集」的时刻取较大者。
 *
 * ## 为什么必须取较大者
 *
 * 收藏记录的更新时间（`UserCollection.updatedAt`）只在改动评分、短评、收藏状态时变，
 * **标一集并不会更新它**（服务端如此，见 v0 spec 对 `updated_at` 的说明）。
 * 只看它会把「刚标记了一集的作品」按很久以前的收藏时间排下去，与「默认排序按最近的操作顺序」
 * 这条产品要求不符。首页「待看」的排序键
 * （`EpisodeTracking.ToWatchRank.lastActiveAt`）、筛选格里「最近动过的几部」与本函数是同一个概念，
 * 都用本函数，不得各写一遍 `maxOf`。
 *
 * [latestWatchedAt] 为 null（或 0）表示该作品没有任何「看过」的集，此时取收藏时间。
 * 逐集数据未到时也是 null——那一刻的保守取值就是收藏时间，不会凭空造出一个未来时刻。
 */
fun lastActiveAt(collectionUpdatedAt: Long, latestWatchedAt: Long?): Long =
    maxOf(collectionUpdatedAt, latestWatchedAt ?: 0L)

/**
 * 作品名的先后——**唯一比较点**：中文按拼音、拉丁字母不分大小写（[Collator] 的二级强度），
 * 不按码点（码点顺序下中文标题排出来没有规律）。名称排序与筛选格的同部数次序都用它。
 *
 * 每次调用新建一个：[Collator] 不保证线程安全，ViewModel 在后台线程上排序。
 */
fun titleOrder(): Comparator<String> {
    val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE).apply { strength = Collator.SECONDARY }
    return Comparator { a, b -> collator.compare(a, b) }
}

/**
 * 进度页排序——**唯一比较点**：[ProgressSort] 各档的口径都只在这里实现。
 *
 * ## 方向
 *
 * [order] 的方向由 [ProgressOrder.descending] 判定，菜单上的叫法与列表顺序同源。
 *
 * ## 没有值与平局
 *
 * - 没有这一档的值的条目**始终排在最后**，反过来也不跑到最前：未评分不是「最低分」（Bangumi 的分值域是 1–10，
 *   0 表示没打），没有逐集数据（只为在看的动画、三次元预取）也不是「很久没更新」。
 * - 排序键相同（以及都没有值）的条目一律以 `subjectId` **升序**打破平局，且**不随方向变化**——
 *   同一份数据在任何方向下都有确定的顺序，不会每次刷新换个排法。
 *
 * [latestWatchedAt]（subjectId → 最后一次「看过」的时刻）只有最近操作用；[episodes] 与 [today]（业务日，判哪集已播）只有最近更新用。
 *
 * 纯函数：不读系统时钟、不碰仓库。
 */
fun sortProgress(
    items: List<UserCollectionBo>,
    order: ProgressOrder,
    today: java.time.LocalDate,
    latestWatchedAt: Map<Int, Long>,
    episodes: Map<Int, List<EpisodeBo>>,
): List<UserCollectionBo> {
    val descending = order.descending
    val byValue: Comparator<UserCollectionBo> = when (order.sort) {
        ProgressSort.RECENT -> missingLast(descending) { lastActiveAt(it.updatedAt, latestWatchedAt[it.subjectId]) }
        ProgressSort.LATEST_AIRED -> missingLast(descending) { item ->
            episodes[item.subjectId]?.let { EpisodeTracking.lastAiredEpisode(it, today) }?.airdate
        }
        // Bangumi 的日期是补零的「年-月-日」（也有只到月的），按字符串比就是按时间比
        ProgressSort.DATE -> missingLast(descending) { item -> item.subject?.date?.takeIf(SUBJECT_DATE::matches) }
        ProgressSort.TITLE -> missingLast(titleOrder(), descending) { item ->
            item.subject?.let { displayNameOf(it) }?.takeIf { it.isNotBlank() }
        }
        ProgressSort.PUBLIC_RATING -> missingLast(descending) { item -> item.subject?.ratingScore?.takeIf { it > 0.0 } }
        // 收藏里的分是 1–10 的整数，0 表示没打
        ProgressSort.MY_RATING -> missingLast(descending) { item -> item.rate.takeIf { it > 0 } }
    }
    return items.sortedWith(byValue.thenBy { it.subjectId })
}

private fun <K : Comparable<K>> missingLast(descending: Boolean, key: (UserCollectionBo) -> K?): Comparator<UserCollectionBo> =
    missingLast(naturalOrder(), descending, key)

/** 按 [key] 比、[descending] 时反过来；没有值（null）的不论方向都在最后。 */
private fun <K> missingLast(values: Comparator<in K>, descending: Boolean, key: (UserCollectionBo) -> K?): Comparator<UserCollectionBo> =
    Comparator { a, b ->
        val x = key(a)
        val y = key(b)
        when {
            x == null && y == null -> 0
            x == null -> 1
            y == null -> -1
            descending -> values.compare(y, x)
            else -> values.compare(x, y)
        }
    }

/** 能当日期比的 `SubjectBo.date`：以「年-月」开头。 */
private val SUBJECT_DATE = Regex("""\d{4}-\d{2}.*""")
