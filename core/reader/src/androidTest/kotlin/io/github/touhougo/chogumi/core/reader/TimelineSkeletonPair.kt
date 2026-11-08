package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.model.Friends
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.TimelineTargetBo
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.ui.common.LocalAppFeatures
import io.github.touhougo.chogumi.core.ui.common.LocalFriends

/** 每组几行：两行才能用第二行的位置探出第一行是否等高。 */
internal const val TIMELINE_PAIR_ROWS = 2

// 「看过」一部动画：无评分、无评论、无回复与贴贴，真实行只剩「标题 + 动词胶囊」，与骨架行一一对应。
private val pairItem = TimelineItemBo(
    id = 1,
    uid = 1,
    user = UserBo(id = 1, username = "frieren", nickname = "动态作者姓名很长需要换行"),
    cat = 3,
    type = 6,
    createdAt = System.currentTimeMillis(),
    text = "",
    targets = listOf(
        TimelineTargetBo(
            title = "芙莉莲",
            url = "https://bgm.tv/subject/1",
            subject = SubjectBo(id = 1, type = SubjectType.ANIME, name = "芙莉莲", nameCn = "芙莉莲", eps = 28),
        ),
    ),
)

/**
 * 动态页一组的骨架 / 真实两态，几何测试与截图留证共用这一份。
 * 与动态页一样左右各 16dp，作者头下直接接分段行，行间 [ListItemDefaults.SegmentedGap]。
 * [friendMarks] 配置与真实页相同的功能开关；关闭时真实行不画好友牌，骨架也必须预留同一几何。
 */
@Composable
internal fun TimelineSkeletonPair(showReal: Boolean, friendMarks: Boolean = true) {
    CompositionLocalProvider(
        LocalAppFeatures provides AppFeatures(
            community = true,
            publicRatings = true,
            coverScores = true,
            friendMarks = friendMarks,
        ),
        LocalFriends provides Friends(setOf(pairItem.uid)),
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (showReal) {
                TimelineGroupHeader(
                    user = pairItem.user,
                    uid = pairItem.uid,
                    createdAt = pairItem.createdAt,
                    count = TIMELINE_PAIR_ROWS,
                    markFriend = true,
                    onUser = {},
                )
            } else {
                TimelineGroupHeaderSkeleton(markFriend = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                repeat(TIMELINE_PAIR_ROWS) { index ->
                    if (showReal) {
                        TimelineRow(
                            group = TimelineDisplayGroup(anchor = pairItem, items = listOf(pairItem)),
                            index = index,
                            count = TIMELINE_PAIR_ROWS,
                            onOpen = {},
                            onDelete = null,
                            onSubject = {},
                        )
                    } else {
                        TimelineRowSkeleton(index, TIMELINE_PAIR_ROWS)
                    }
                }
            }
        }
    }
}
