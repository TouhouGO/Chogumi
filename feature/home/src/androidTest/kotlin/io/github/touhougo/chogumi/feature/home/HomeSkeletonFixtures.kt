package io.github.touhougo.chogumi.feature.home

import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.data.progress.ProgressSubject
import io.github.touhougo.chogumi.core.model.BookUnit
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.ScheduleDayGroup
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import java.time.LocalDate

/** 首页几何测试与截图测试共用的数据：三个区块（待看、追更时间表、继续）都有内容。 */
internal object HomeSkeletonFixtures {
    /** 首页顶部日期与时间表的「今天」：固定下来，几何与截图不随跑测试的日子变。 */
    val today: LocalDate = LocalDate.of(2026, 10, 3)

    fun toWatchItem(id: Int) = HomeToWatchItem(
        card = HomeProgressCard(
            subject = SubjectBo(id = id, type = SubjectType.ANIME, name = "作品$id", nameCn = "作品$id"),
            epButtonState = EpButtonState.Empty,
            progressSubject = ProgressSubject(subjectId = id, subjectType = SubjectType.ANIME, episodes = emptyList()),
            progressCount = ProgressCount.Known(BookUnit.EP, 0, 12),
        ),
        airedUnwatchedCount = 0,
    )

    fun scheduleItem(id: Int) = HomeScheduleItem(
        // 标题取两行长度：骨架按「最多两行」预留，两行数据下方区块才与骨架严格对齐（一行时只会往上收）
        subject = SubjectBo(id = id, type = SubjectType.ANIME, name = "标题$id", nameCn = "这是一个很长很长的作品名称$id"),
        nextUpcomingSort = 4.0,
        airedUnwatchedCount = 0,
        airing = null,
    )

    fun nextAt(id: Int) = EpButtonState.Next(
        episode = EpisodeBo(id = id, subjectId = id, type = 0, name = "", nameCn = "", sort = 2.0),
        position = 2,
    )

    fun continueItem(id: Int, type: SubjectType) = HomeContinueItem(
        card = HomeProgressCard(
            subject = SubjectBo(id = id, type = type, name = "继续$id", nameCn = "继续$id"),
            epButtonState = nextAt(id),
            progressSubject = ProgressSubject(subjectId = id, subjectType = type, episodes = emptyList()),
            progressCount = ProgressCount.Known(BookUnit.EP, 3, 12),
        ),
        collection = UserCollectionBo(
            subjectId = id,
            subjectType = type,
            type = CollectionType.DOING,
            epStatus = 3,
            subject = SubjectBo(id = id, type = type, name = "继续$id", nameCn = "继续$id", eps = 12),
        ),
    )

    /** 三个区块（待看、追更时间表、继续）都有数据的真实内容。 */
    fun fullState() = HomeUiState(
        today = today,
        progress = Load.Ready(Unit),
        toWatch = (1..3).map { id ->
            toWatchItem(id).copy(
                card = toWatchItem(id).card.copy(epButtonState = nextAt(id)),
            )
        },
        scheduleGroups = (0..2).map { day ->
            ScheduleDayGroup(daysUntil = day.toLong(), date = today.plusDays(day.toLong()), items = listOf(scheduleItem(10 + day)))
        },
        continueItems = listOf(continueItem(21, SubjectType.ANIME), continueItem(22, SubjectType.ANIME)),
        continueChips = listOf(SubjectType.ANIME, SubjectType.BOOK),
    )

}
