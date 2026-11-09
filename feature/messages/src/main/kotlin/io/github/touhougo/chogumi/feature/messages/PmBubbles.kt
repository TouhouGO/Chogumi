package io.github.touhougo.chogumi.feature.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.model.PmThread
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.reader.BBCodeText
import io.github.touhougo.chogumi.core.reader.bodyGestures
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.ReaderTypography
import io.github.touhougo.chogumi.core.ui.composables.CircleAvatar
import io.github.touhougo.chogumi.core.ui.composables.MetaText
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SkeletonStandIn
import io.github.touhougo.chogumi.core.ui.composables.relativeTimeText
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

// 会话页的气泡与主题标签（设计决定，not in M3：M3 没有聊天气泡组件）。
// 对方在左、`surfaceContainerHigh`；自己在右、`secondaryContainer`（都是 tonal 色，不用黑白气泡）。气泡最宽为行宽的 4/5，
// 圆角 `shapes.large`，内边距左右 12、上下 8；对方一组的第一封旁边画 32 的圆头像，自己的不画（顶栏已经是对方）。

/**
 * 一封信的一行：气泡 + （一组的最后一封）下面的时间。正文交给帖子用的同一个渲染器（[BBCodeText]），链接、表情、图片、
 * 剧透照常可点；长按气泡调 [onLongPress]（菜单由 [menu] 画在气泡上，跟着气泡走）。
 */
@Composable
internal fun PmBubbleRow(
    row: PmRow.Bubble,
    peer: UserBo,
    onPeerClick: () -> Unit,
    onUserClick: (String) -> Unit,
    onLongPress: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val message = row.message
    BubbleRowFrame(
        mine = message.mine,
        avatar = if (!message.mine && row.startsRun) ({ CircleAvatar(peer.avatarSmall, displayNameOf(peer), AvatarSize, onClick = onPeerClick) }) else null,
        top = if (row.startsRun) RunGap else BubbleGap,
    ) {
        Box {
            val interaction = remember { MutableInteractionSource() }
            BubbleFrame(
                mine = message.mine,
                modifier = Modifier.bodyGestures(
                    interaction = interaction,
                    onTap = null,
                    onLongPress = { onLongPress() },
                    tapLabel = null,
                    longPressLabel = stringResource(R.string.pm_message_menu),
                ),
            ) {
                val ast = remember(message.body) { BBCodeParser.parse(message.body) }
                BBCodeText(ast = ast, style = ReaderTypography.comment, onUserClick = onUserClick)
            }
            menu()
        }
        if (row.endsRun) {
            MetaText(
                parts = listOf(relativeTimeText(message.sentAt)),
                style = ReaderTypography.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

/** 加载中的一封：真气泡排一段样例文字、不可见，上面盖同大的色块（[SkeletonStandIn]），几何与真气泡同源。 */
@Composable
internal fun PmBubbleSkeleton(mine: Boolean, sample: String) {
    BubbleRowFrame(mine = mine, avatar = if (mine) null else ({ SkeletonStandIn { CircleAvatar(null, null, AvatarSize) } }), top = RunGap) {
        SkeletonStandIn {
            BubbleFrame(mine = mine) { Text(sample, style = ReaderTypography.comment) }
        }
    }
}

/**
 * 一条线开头的主题标签：居中、`surfaceContainer` 底的小标签（`labelMedium`，单行省略），点了回复这条线。
 * 可点的 [PressArea] 自带 48dp 的触控区，标签本身只有字高。
 */
@Composable
internal fun PmThreadLabel(thread: PmThread, onReply: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = RunGap), contentAlignment = Alignment.Center) {
        // 标签按字宽排，最宽同气泡区
        Box(Modifier.fillMaxWidth(BUBBLE_WIDTH_FRACTION), contentAlignment = Alignment.Center) {
            PressArea(
                onClick = onReply,
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = thread.title,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** 气泡这一行的外框：左右留页边，对方的头像格（一组里后面几封留同宽的空），气泡区最宽 4/5，自己的靠右。 */
@Composable
private fun BubbleRowFrame(mine: Boolean, avatar: (@Composable () -> Unit)?, top: Dp, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = ChogumiDimens.ScreenPadding, end = ChogumiDimens.ScreenPadding, top = top),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        if (!mine) {
            Box(Modifier.width(AvatarSize)) { avatar?.invoke() }
            Spacer(Modifier.width(AvatarGap))
        }
        Column(
            modifier = Modifier.fillMaxWidth(BUBBLE_WIDTH_FRACTION),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) { content() }
    }
}

/** 气泡本身：tonal 底色、圆角、内边距；里面的字色随底色（[contentColorFor]）。 */
@Composable
private fun BubbleFrame(mine: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val container = if (mine) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColorFor(container)) { content() }
    }
}

private val AvatarSize = 32.dp
private val AvatarGap = 8.dp

/** 同一组里两封之间、两组之间的间距（设计决定，not in M3）。 */
private val BubbleGap = 2.dp
private val RunGap = 12.dp

/** 气泡区最宽占行宽的比例（设计决定，not in M3）。 */
private const val BUBBLE_WIDTH_FRACTION = 0.8f
