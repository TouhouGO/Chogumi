package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.common.bbcode.excerptOf
import io.github.touhougo.chogumi.core.res.R

/**
 * 帖子行：全 App 的帖子列表（社区的小组与超展开、一个小组的帖子、我的帖子与草稿、讨论版、作品评论、个人日志）只用这一个，
 * 几何只此一份。论坛样式，有发帖人时：
 *
 * ```
 * [头像] 发帖人 ................ 来源
 *        标题（≤2 行）
 *        摘要（≤3 行，可无）
 *        时间 ................ 💬 N
 * ```
 *
 * 没有发帖人时（单集讨论、角色人物吐槽、自己的草稿）标题顶格，来源移到标题下面一行：
 *
 * ```
 * [封面] 标题（≤2 行）
 *        来源
 *        时间 ................ 💬 N
 * ```
 *
 * 时间固定在文字列左下、回复数固定在右下：标题折不折行、数字几位，两者在每一行的位置都一样（
 * 回复数挂在行尾会随标题行数上下漂、数字宽度不同图标左右漂）。项底比一般分段列表浅一档（`surfaceContainerLow`）。
 *
 * 设计决定（not in M3）：发帖人 `labelLarge`、来源 `labelMedium`（色同副行）；两者放得下时同一行、同一条基线，
 * 放不下时发帖人先占整行、来源下一行，发帖人只在自己一行也放不下时才截断；标题 `titleMedium`；摘要 `bodyMedium`；
 * 页脚 `labelMedium`、图标 16、图标与数字间距 4、页脚上方 8。
 *
 * @param leading 左边那一列（[PostLeading]）；null 不画（个人日志、草稿全是本人写的）。
 * @param author 发帖人（显示名由调用方经 `displayNameOf` 取）；null 不画。
 * @param source 帖子挂在哪里（小组名、作品名、「角色」「人物」、草稿回复的对象）：有发帖人且一行放得下时在那一行最右边，
 *   放不下时在发帖人下一行；没有发帖人时在标题下面；null 不画。
 * @param time 已按场景格式化好的时间；空串不画。
 * @param replies 回复数；null 不画（还没发出去的草稿）。
 * @param repliesDescription 读屏念的回复数（「N 条回复」），不念图标 + 裸数字。
 * @param onLongClick 长按（自己的草稿删除、超展开的小组帖屏蔽小组）；null 不响应长按。
 */
@Composable
fun PostListItem(
    title: String,
    replies: Int?,
    repliesDescription: String?,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    leading: PostLeading? = null,
    author: String? = null,
    source: String? = null,
    summary: String = "",
    time: String = "",
) {
    SegmentedItem(
        index = index,
        count = count,
        onClick = onClick,
        modifier = modifier,
        onLongClick = onLongClick,
        verticalAlignment = Alignment.Top,
        containerColor = PostRowContainerColor,
        leadingContent = leading?.let { { PostLeadingImage(it) } },
        details = {
            Column {
                // 日志摘要是服务端截断的 BBCode，列表里只画纯文字
                val excerpt = remember(summary) { excerptOf(summary) }
                if (excerpt.isNotBlank()) {
                    Text(
                        text = excerpt,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = SUMMARY_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(time, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    if (replies != null) CountLabel(icon = R.drawable.forum_24, count = replies, description = repliesDescription.orEmpty())
                }
            }
        },
    ) {
        Column {
            val shownAuthor = author?.takeIf { it.isNotBlank() }
            val shownSource = source?.takeIf { it.isNotBlank() }
            if (shownAuthor != null) {
                PostAuthorSourceLine(
                    author = {
                        Text(
                            text = shownAuthor,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    source = shownSource?.let { sourceText ->
                        { SourceText(text = sourceText) }
                    },
                )
            }
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = TITLE_MAX_LINES, overflow = TextOverflow.Ellipsis)
            if (shownAuthor == null && shownSource != null) {
                SourceText(text = shownSource, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** 帖子行左边那一列画什么：同一列宽（[ListAvatarSize]），各行的文字列左缘对齐。 */
sealed interface PostLeading {
    /** 圆形头像：发帖人，或角色、人物（顶对齐裁，见 [CircleAvatar]）；[url] 为 null 时画人形占位。 */
    data class Avatar(val url: String?) : PostLeading

    /** 作品封面（[SubjectPoster]，3:4）：没有发帖人的单集讨论画它挂在哪部作品。 */
    data class Cover(val url: String?) : PostLeading
}

@Composable
private fun PostLeadingImage(leading: PostLeading) {
    when (leading) {
        is PostLeading.Avatar -> CircleAvatar(leading.url, description = null, size = ListAvatarSize)
        is PostLeading.Cover -> SubjectPoster(url = leading.url, modifier = Modifier.width(ListAvatarSize))
    }
}

/** 来源是副信息：色同副行（onSurfaceVariant），不和发帖人、标题抢。一行，放不下截断。 */
@Composable
private fun SourceText(text: String, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Unspecified) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = modifier,
    )
}

/** 发帖人和来源的唯一排法：真实行与骨架都从这里取得断行、基线和单行右缘。 */
@Composable
private fun PostAuthorSourceLine(author: @Composable () -> Unit, source: (@Composable () -> Unit)?) {
    PriorityTextLine(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        primary = author,
        trailing = source,
        gap = SourceGap,
        trailingToEnd = true,
    )
}

/**
 * 帖子行的占位（骨架用）：与 [PostListItem] 同一个 [SegmentedItem] 框架、同样的项底、左列（[leading] 只看种类）与字阶，
 * 发帖人（[withAuthor]）、来源（[withSource]）、标题、摘要（[withSummary]）、页脚各画一段字条，位置同真实行：
 * 有发帖人时姓名与来源共用同一断行框架，放得下时来源贴逻辑 end、放不下时下一行；没有时来源在标题下面。
 * 标题与摘要按真实行的行数上限占位，内容出现时只会往上收。
 * 字条宽度是设计决定（not in M3）。
 */
@Composable
fun PostListItemSkeleton(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    leading: PostLeading? = null,
    withSummary: Boolean = false,
    withSource: Boolean = false,
    withAuthor: Boolean = leading != null,
) {
    SegmentedItem(
        index = index,
        count = count,
        onClick = null,
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        containerColor = PostRowContainerColor,
        leadingContent = leading?.let {
            {
                when (it) {
                    is PostLeading.Avatar -> SkeletonBox(Modifier.size(ListAvatarSize), shape = CircleShape)
                    is PostLeading.Cover -> SubjectPosterSkeletonBox(Modifier.width(ListAvatarSize))
                }
            }
        },
        details = {
            Column {
                if (withSummary) {
                    SkeletonText(MaterialTheme.typography.bodyMedium, Modifier.padding(top = 4.dp), lines = SUMMARY_MAX_LINES)
                }
                SkeletonText(MaterialTheme.typography.labelMedium, Modifier.padding(top = 8.dp), widthFraction = 0.25f)
            }
        },
    ) {
        Column {
            if (withAuthor) {
                PostAuthorSourceLine(
                    author = {
                        SkeletonText(
                            MaterialTheme.typography.labelLarge,
                            widthFraction = 0.7f,
                            sample = POST_SKELETON_AUTHOR_SAMPLE,
                            fillWidth = false,
                            maxLines = 1,
                        )
                    },
                    source = if (withSource) {
                        {
                            SkeletonText(
                                MaterialTheme.typography.labelMedium,
                                widthFraction = 0.7f,
                                sample = POST_SKELETON_SOURCE_SAMPLE,
                                fillWidth = false,
                                maxLines = 1,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
            SkeletonText(MaterialTheme.typography.titleMedium, lines = TITLE_MAX_LINES, widthFraction = 0.8f)
            if (!withAuthor && withSource) {
                SkeletonText(MaterialTheme.typography.labelMedium, Modifier.padding(top = 4.dp), widthFraction = 0.4f)
            }
        }
    }
}

/** 帖子行的项底：比一般分段列表（`surfaceContainer`）浅一档。设计决定（not in M3）。 */
private val PostRowContainerColor: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow

/** 发帖人与来源之间至少留的空。设计决定（not in M3）。 */
private val SourceGap = 12.dp

/** 标题、摘要最多几行：真实行与占位共用。 */
private const val TITLE_MAX_LINES = 2
private const val SUMMARY_MAX_LINES = 3
private const val POST_SKELETON_AUTHOR_SAMPLE = "很长的发帖人姓名"
private const val POST_SKELETON_SOURCE_SAMPLE = "很长的小组名称"
