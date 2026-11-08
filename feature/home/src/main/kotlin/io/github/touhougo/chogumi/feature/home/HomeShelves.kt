package io.github.touhougo.chogumi.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.FriendSubjectGroupBo
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.CarouselRow
import io.github.touhougo.chogumi.core.ui.composables.CountBadge
import io.github.touhougo.chogumi.core.ui.composables.CoverBadgeInset
import io.github.touhougo.chogumi.core.ui.composables.PosterCard
import io.github.touhougo.chogumi.core.ui.composables.PosterCardSkeleton
import io.github.touhougo.chogumi.core.ui.composables.PosterCardStyle
import io.github.touhougo.chogumi.core.ui.composables.PosterCardWidth
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.StackedAvatars
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.composables.labelRes
import io.github.touhougo.chogumi.core.ui.composables.labelResFor

/** 也在进行：游戏与音乐迷你封面。 */
@Composable
internal fun DoingList(
    items: List<UserCollectionBo>,
    onSubjectClick: (Int) -> Unit,
) {
    CarouselRow(Modifier.padding(top = 4.dp)) {
        items(items, key = { "doing_${it.subjectId}" }) { item ->
            // 设计决定（非 M3）：迷你封面 72x96 dp
            PressArea(
                onClick = { onSubjectClick(item.subjectId) },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.width(72.dp),
            ) {
                Column {
                    val typeLabel = stringResource(item.subjectType.labelRes())
                    SubjectPoster(
                        item.subject?.coverUrl,
                        modifier = Modifier.width(72.dp),
                        description = item.subject?.let(::displayNameOf) ?: typeLabel,
                    )
                    Text(
                        text = typeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}


/** 好友在看：按作品合并，海报叠最多 3 个头像。 */
@Composable
internal fun FriendsActivities(
    groups: List<FriendSubjectGroupBo>,
    onSubjectClick: (Int) -> Unit,
) {
    CarouselRow(Modifier.padding(top = 4.dp)) {
        items(groups, key = { "friend_${it.subject.id}" }) { group ->
            val verb = stringResource(group.latestCollectionType.labelResFor(group.subject.type))
            PosterCard(
                imageUrl = group.subject.coverUrl,
                title = displayNameOf(group.subject),
                style = PosterCardStyle.CAROUSEL,
                onClick = { onSubjectClick(group.subject.id) },
                mark = null,
                modifier = Modifier.width(PosterCardWidth),
                supporting = pluralStringResource(R.plurals.home_friends_count, group.friends.size, group.friends.size, verb),
            ) {
                // 叠头像（最多 3 个），描环取封面上的 surface
                StackedAvatars(
                    avatars = group.friends.take(3).map { it.avatarUrl },
                    size = 24.dp,
                    ringColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(CoverBadgeInset),
                )
            }
        }
    }
}

/** 好友内容未到时用同一海报卡框架占位，含辅助文字行；三张只是首屏占位数量，不是 M3 规格。 */
@Composable
internal fun FriendsActivitiesSkeleton() {
    CarouselRow(Modifier.padding(top = 4.dp).testTag("home_friends_loading")) {
        items(3) {
            PosterCardSkeleton(PosterCardStyle.CAROUSEL, modifier = Modifier.width(PosterCardWidth), supporting = true)
        }
    }
}

/** 封面上「已播未看 N 集」的角标：[CountBadge] 的 tertiary 一对，没有未看就不画。 */
@Composable
internal fun UnwatchedCountBadge(count: Int, modifier: Modifier = Modifier) {
    CountBadge(
        count = count,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
    )
}
