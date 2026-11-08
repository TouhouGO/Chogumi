package io.github.touhougo.chogumi.feature.profile

import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.model.CareerStatistics
import io.github.touhougo.chogumi.core.model.CollectionStatistics
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.RankedStatistic
import io.github.touhougo.chogumi.core.model.RatingVerdict
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.TimelineReactionTarget
import io.github.touhougo.chogumi.core.model.TimelineTargetBo
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.WatchTime

/** 个人页骨架几何测试与截图测试共用的假数据。全部在本地构造，不连账号、不发网络。 */
internal object ProfileSkeletonFixtures {

    val user = UserBo(id = 1, username = "alpha", nickname = "Alpha")

    /** 当前登录用户的 id：与 [singleSubjectFeed] 里动态的作者一致，动态行因此可长按删除。 */
    const val CURRENT_USER_ID = 1

    /** 一条只含单个作品的动态——个人页「动态」里最常见的形态（封面 + 标题 + 动词 + 行内时间）。 */
    val singleSubjectFeed = PagedList(
        isLoading = false,
        items = listOf(
            TimelineItemBo(
                id = 1,
                uid = CURRENT_USER_ID,
                user = null,
                cat = 3,
                type = 1,
                createdAt = System.currentTimeMillis(),
                text = "",
                targets = listOf(
                    TimelineTargetBo(
                        title = "芙莉莲",
                        url = "https://bgm.tv/subject/1",
                        subject = SubjectBo(id = 1, type = SubjectType.ANIME, name = "芙莉莲", nameCn = "芙莉莲", eps = 28),
                    ),
                ),
                reactionTarget = TimelineReactionTarget.Status,
            ),
        ),
    )

    /**
     * 一类的统计快照：三格大数字、进度阶梯、题材、年代与打分口味都有内容。
     */
    val careerStats = CollectionStatistics(
        totalCount = 128,
        ratedCount = 42,
        episodeProgress = 663,
        watchTime = WatchTime(minutes = 265 * 60, estimated = true),
        statusCounts = mapOf(
            CollectionType.DONE to 88,
            CollectionType.DOING to 12,
            CollectionType.WISH to 24,
            CollectionType.ON_HOLD to 3,
            CollectionType.DROPPED to 1,
        ),
        genreCounts = listOf(
            RankedStatistic("科幻", 31),
            RankedStatistic("日常", 22),
            RankedStatistic("奇幻", 18),
        ),
        ratingVerdict = RatingVerdict(
            ratedCount = 42,
            myAverage = 8.1,
            publicAverage = 7.6,
            gap = 0.5,
        ),
    )

    /**
     * 生涯卡 `Ready` 态：两类有收藏（右边列各类完成数）、打过分且能和大众比（均分一行带说明）、有题材（称号与最爱），
     * 封面不够铺满（拼图走「几行」的排法，底图露出来）。封面没有图片地址，画占位色，测试不连网络。
     */
    val careerStatistics = CareerStatistics(
        byType = mapOf(
            SubjectType.ANIME to careerStats,
            SubjectType.BOOK to CollectionStatistics(totalCount = 9, statusCounts = mapOf(CollectionType.DONE to 9)),
        ),
        overall = careerStats.copy(
            totalCount = careerStats.totalCount + 9,
            averageRating = 8.1,
            coverPicks = (1..7).map { SubjectBo(id = it, type = SubjectType.ANIME, name = "作品 $it", nameCn = "作品 $it") },
        ),
    )
}
