package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 人物头像的 testTag：真实卡与骨架共用，几何测试据此对位。 */
const val PersonAvatarTag = "person_avatar"

/** 竖向网格里人物头像的直径（设计决定，not in M3）。网格的人物格最小宽度由它推出（[GridCell.Person]）。 */
internal val PersonAvatarSize = 80.dp

/**
 * 人物卡左右内边距（水波纹圆角区域与头像、名字之间的留白）；同样计入 [GridCell.Person]。
 * 4：380dp 小屏排 3 列时名字能多出 8dp，「anonymous」这类拉丁昵称不会被拆成两行。我的设计决定（not in M3）。
 */
internal val PersonGridItemHorizontalPadding = 4.dp

/**
 * 竖向人物卡：圆形头像 + 居中姓名 + 可选副标题。
 *
 * 用于**自适应网格**里的人群展示（好友 / 关注者、谁在看）：上图下文居中，一屏能看更多人。
 */
@Composable
fun PersonGridItem(
    title: String,
    imageUrl: String?,
    /** null = 不可点：不挂 clickable、不给水波纹。详情页还没做的入口别伪装成能点。 */
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** 名字下的附加信息，每项一行（如「1 小时前在看」「★ 8」）；各自单行，放不下省略。不拼成一句、不用分隔号。 */
    subtitles: List<String> = emptyList(),
    /**
     * 卡片是一个用户时传其 id：是当前账号关注的人时，头像底边正中压一块「好友」小牌（[FriendMark]）。不跟在名字后面：
     * 格子窄，小牌占掉的宽会把四个字的名字也折成两行。角色、人物，以及人人都是好友的列表不传。
     */
    userId: Int? = null,
) {
    PressArea(onClick = onClick, shape = MaterialTheme.shapes.small, modifier = modifier) {
        PersonGridItemLayout(title = title, subtitles = subtitles) {
            // 小牌画在头像的方框里（压在圆的底边上），不伸出卡片自己量出的边界
            Box(contentAlignment = Alignment.BottomCenter) {
                // 整张竖立绘顶对齐裁圆，画法见 [PortraitImage]（走全局 crossfade 时顶对齐失效、只剩身子和腿）
                Box(
                    modifier = Modifier
                        .personAvatarSize()
                        .testTag(PersonAvatarTag)
                        .shadow(1.dp, CircleShape)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    PortraitImage(url = imageUrl, description = title, modifier = Modifier.fillMaxSize())
                }
                FriendMark(userId)
            }
        }
    }
}

/**
 * 人物竖卡的**布局**——真实卡（[PersonGridItem]）与骨架（[PersonGridItemSkeleton]）共用的唯一出处：
 * 内边距、头像与名字的间距、名字与副标题的字阶只写在这里。
 * [title] 为 null 时名字位画 [SkeletonText]（同字阶），[skeletonSubtitles] 行副行同样画同字阶的占位，其余完全一致。
 *
 * 名字预留真实组件的最多行数：长名字出现时不会将后续网格行撑下移，短名字出现时只向上收。
 */
@Composable
private fun PersonGridItemLayout(
    title: String?,
    modifier: Modifier = Modifier,
    subtitles: List<String> = emptyList(),
    skeletonSubtitles: Int = 0,
    avatar: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.padding(horizontal = PersonGridItemHorizontalPadding, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        avatar()
        val titleModifier = Modifier.padding(top = 8.dp)
        val titleStyle = MaterialTheme.typography.bodyLarge
        if (title != null) {
            Text(
                text = title,
                modifier = titleModifier.heightIn(min = rememberTextLinesHeight(titleStyle, PERSON_TITLE_LINES)),
                color = MaterialTheme.colorScheme.onSurface,
                style = titleStyle,
                textAlign = TextAlign.Center,
                maxLines = PERSON_TITLE_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            // 名字占位铺满整列：真实的最长（两行）名字会撑满列宽，头像因此在列内居中、左缘落在最长名字那一档。
            // 若给占位写一个比头像窄的固定宽，列宽会退化成头像宽，名字一出现头像就横向跳 9dp。
            // 色条只画列宽的一部分，宽度是项目设计决定（not in M3），高度仍由 [SkeletonText] 随真实字阶走。
            SkeletonText(
                style = titleStyle,
                modifier = titleModifier.fillMaxWidth(),
                lines = PERSON_TITLE_LINES,
                widthFraction = PERSON_SKELETON_TITLE_FRACTION,
            )
        }
        val subtitleStyle = MaterialTheme.typography.bodySmall
        subtitles.forEach {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = subtitleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        repeat(skeletonSubtitles) {
            SkeletonText(style = subtitleStyle, widthFraction = PERSON_SKELETON_SUBTITLE_FRACTION)
        }
    }
}

/**
 * 骨架里名字色条占整列宽的比例。**本项目设计决定（not in M3）**：约四五个汉字宽，只影响横向；
 * 高度由 [SkeletonText] 随真实名字的字阶走。占位仍铺满整列，列宽（进而头像的居中位置）不受这个比例影响。
 */
private const val PERSON_SKELETON_TITLE_FRACTION = 0.7f

/** 骨架里副行色条占整列宽的比例，比名字短一截。本项目设计决定（not in M3）。 */
private const val PERSON_SKELETON_SUBTITLE_FRACTION = 0.5f

/** 真实名字既有的最多行数，由文字和骨架共用。 */
private const val PERSON_TITLE_LINES = 2

/**
 * 网格骨架：布局来自 [PersonGridItemLayout]，与真实的 [PersonGridItem] 同源。
 * [subtitleLines] 是真实卡必有的副行行数（谁在看的「1 小时前在看」）；可有可无的副行不画。
 */
@Composable
fun PersonGridItemSkeleton(modifier: Modifier = Modifier, subtitleLines: Int = 0) {
    PersonGridItemLayout(title = null, modifier = modifier, skeletonSubtitles = subtitleLines) {
        SkeletonBox(
            modifier = Modifier.personAvatarSize().testTag(PersonAvatarTag),
            shape = CircleShape,
        )
    }
}

/**
 * 头像尺寸：直径 [PersonAvatarSize]，格子比它窄时随格宽缩小但**始终是正圆**（宽高比 1）。
 * 网格按 [GridCell.Person] 排列时格子不会比头像窄；这里只防别处把卡放进更窄的容器时被压成椭圆。
 */
private fun Modifier.personAvatarSize(): Modifier = this
    .widthIn(max = PersonAvatarSize)
    .fillMaxWidth()
    .aspectRatio(1f)
