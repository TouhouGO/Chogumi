package io.github.touhougo.chogumi.core.model

import java.util.concurrent.TimeUnit

/**
 * 首页「进度」区块的一条条目（对应 `/p1/home` 的 `ProgressItem`）。
 *
 * 只保留首页用到的字段：`/p1/home` 的响应很大（实测 663 KB，
 * 2026-09-29 实测），其余区块与字段一律不映射。
 *
 * 收藏状态与条目级进度（话数/卷数）**不在这里**：它们与其它页面共用收藏表
 * （`CollectionStore`，首页拉取时合并进去），首页按 `subject.id` 去那里读。
 */
data class HomeProgressItemBo(
    val subject: SubjectBo,
    /** 章节元数据：`/p1/home` 的 eps[] 完整时用它，被截断时为回退拉到的 v0 全量。 */
    val eps: List<EpisodeBo>,
    /**
     * 首页快照里的收藏更新时间（epoch 毫秒）——「继续」区块的排序依据，也是待看最近观看时刻的后备值。
     * 刻意取**快照**而非收藏表的实时值：点了 EP 按钮后条目不跳位置，下次刷新才重排。
     */
    val updatedAt: Long,
)

/**
 * 好友的在看动态（对应 `/p1/home` 之外的 `GET /p1/me/friends/subject-collections`）。
 */
data class FriendActivityBo(
    val userId: Int,
    val username: String,
    val nickname: String,
    val avatarUrl: String?,
    val subject: SubjectBo,
    val collectionType: CollectionType,
    val updatedAt: Long,
)

/** 好友动态中展示的简要用户信息。 */
data class FriendUserBo(
    val userId: Int,
    val username: String,
    val nickname: String,
    val avatarUrl: String?,
)

/**
 * 首页「好友在看」聚合条目：按作品合并后的一条展示项。
 */
data class FriendSubjectGroupBo(
    val subject: SubjectBo,
    /** 该作品最近一条动态的收藏类型，用于驱动「在看 / 看过 / 在读」等动词。 */
    val latestCollectionType: CollectionType,
    val latestUpdatedAt: Long,
    /** 参与该作品动态的好友列表（最新排序，已去重，UI 上最多展示前 3 个头像）。 */
    val friends: List<FriendUserBo>,
)

/** 好友在看动态只看最近这么久。 */
private val FRIENDS_ACTIVITY_WINDOW_MILLIS = TimeUnit.DAYS.toMillis(7)

/**
 * 好友在看动态聚合纯函数：
 * 1. 过滤：只保留最近 7 天内（`updatedAt >= nowMillis - FRIENDS_ACTIVITY_WINDOW_MILLIS`）的记录；比 [nowMillis] 还新的也算
 *    （服务端时钟略快，或 [nowMillis] 是整分钟一跳的「现在」、落后最多一分钟）；
 * 2. 合并：按作品 (`subject.id`) 合并；好友按 userId 去重（多次操作保留最新的一条）；
 * 3. 排序：按作品最新动态时间倒序（最近操作在前）；
 * 4. 动词状态：取该作品最新一条记录的 [collectionType]。
 */
fun aggregateFriendsActivities(
    activities: List<FriendActivityBo>,
    nowMillis: Long,
): List<FriendSubjectGroupBo> {
    val recent = activities.filter { it.updatedAt >= nowMillis - FRIENDS_ACTIVITY_WINDOW_MILLIS }
    return recent
        .groupBy { it.subject.id }
        .values
        .map { subjectActivities ->
            val sorted = subjectActivities.sortedByDescending { it.updatedAt }
            val latest = sorted.first()
            val uniqueFriends = sorted
                .distinctBy { it.userId }
                .map { FriendUserBo(it.userId, it.username, it.nickname, it.avatarUrl) }
            FriendSubjectGroupBo(
                subject = latest.subject,
                latestCollectionType = latest.collectionType,
                latestUpdatedAt = latest.updatedAt,
                friends = uniqueFriends,
            )
        }
        .sortedByDescending { it.latestUpdatedAt }
}

/** 追更时间表按天分组结果。 */
data class ScheduleDayGroup<T>(
    /** 距离播出的天数（0 = 今天，1 = 明天，N = N 天后）。 */
    val daysUntil: Long,
    /** 播出日期。 */
    val date: java.time.LocalDate,
    /** 该天播出的条目集合。 */
    val items: List<T>,
)

/**
 * 追更时间表分组纯函数：
 * 按条目下一次在哪天播（[followedAiringDateOf]：下一个未来播出正片 `airdate >= today` 的播出日）分组，组间按天数升序；组内按这一天的播出时刻升序
 * （[airingSelector] 给出条目在那一天的 [AiringMinute]，没有时刻的排最后），时刻相同或都没有的保留原有顺序。
 */
fun <T> groupScheduleByDay(
    items: List<T>,
    today: java.time.LocalDate,
    episodesSelector: (T) -> List<EpisodeBo>,
    airingSelector: (T, java.time.LocalDate) -> AiringMinute?,
): List<ScheduleDayGroup<T>> {
    val itemsWithDate = items.mapNotNull { item ->
        // 首页的作品都带齐了章节，不读放送表
        val date = followedAiringDateOf(episodesSelector(item), calendarWeekday = null, today) ?: return@mapNotNull null
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)
        Triple(days, date, item)
    }
    return itemsWithDate
        .groupBy { Pair(it.first, it.second) }
        .entries
        .sortedBy { it.key.first }
        .map { (key, group) ->
            ScheduleDayGroup(
                daysUntil = key.first,
                date = key.second,
                items = group.map { it.third to airingSelector(it.third, key.second) }
                    .sortedWith(compareBy<Pair<T, AiringMinute?>, AiringMinute?>(nullsLast()) { it.second })
                    .map { it.first },
            )
        }
}

/**
 * 待看排序键，三个量都由 [EpisodeTracking] 算好（见 [EpisodeTracking.rankToWatch]）：
 *
 * - [lastActiveAt]：该作品最近一次「有关联」的时刻——收藏更新时间与该作品最后一次「看过」某集的时间取大；
 * - [relevantAt]：`max(lastActiveAt, freshAirAt)`，freshAirAt 见 [EpisodeTracking.toWatchRecency]；
 * - [airedUnwatchedCount]：已播未看的正片集数。排序与冷却都读 [relevantAt]。
 */
data class ToWatchRank(
    val subjectId: Int,
    val lastActiveAt: Long,
    val relevantAt: Long,
    val airedUnwatchedCount: Int,
)

/** 首页区块归属。 */
enum class HomeSection {
    /** 待看：动画、三次元有已播未看正片（不要求连载中）。 */
    TO_WATCH,

    /** 仅时间表：连载中且已追平，只出现在「追更时间表」。 */
    SCHEDULE_ONLY,

    /** 继续：非连载条目与书籍。 */
    CONTINUE,

    /** 也在进行：游戏、音乐。 */
    IN_PROGRESS,
}
