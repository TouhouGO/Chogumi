package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.ui.composables.CircleAvatar
import io.github.touhougo.chogumi.core.ui.composables.CommentFloorSkeleton
import io.github.touhougo.chogumi.core.ui.composables.GroupIcon
import io.github.touhougo.chogumi.core.ui.composables.ListSortBarSkeleton
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.composables.TitleLink
import io.github.touhougo.chogumi.core.ui.composables.TitleLinkImageSize

// 帖子页（讨论版话题、日志、单集讨论）的头部、顶栏上级与整页骨架。头部共用这一份几何，骨架跟着同源。

/**
 * 帖子页头部：关联作品眉题（换行排，[eyebrows] 为 null 时不出）+ 标题（`.thead`）。不显示发帖时间。
 * 只有日志有眉题（每部关联作品一个）；话题的上级（小组、作品）在顶栏标题（[groupTitleLink]、[subjectTitleLink]）。
 */
@Composable
fun ThreadHead(
    title: String,
    eyebrows: (@Composable FlowRowScope.() -> Unit)?,
) {
    ThreadHeadFrame(eyebrows) {
        Text(text = title, style = MaterialTheme.typography.titleLargeEmphasized)
    }
}

/**
 * 帖子页的整页骨架：头部（[eyebrow] 时一个眉题 + 两行标题）→ 帖子正文楼 → 排序行 → 几层楼，几何全部取自真实组件的同一外框。
 * [eyebrow] 只有日志给（关联作品眉题在正文里）；话题、单集讨论的上级在顶栏标题，头部没有眉题。
 *
 * 只许往上收：标题按两行占位、日志的眉题总画（日志可能没有关联作品），真实内容比占位矮时整体上移，不会往下顶。
 * 进页先画这套骨架，首屏齐了再淡入；不走「整屏转圈 → 正文 → 关联作品插进来把正文往下挤 → 留言」。
 */
@Composable
fun ThreadPageSkeleton(eyebrow: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().clipToBounds()) {
        ThreadHeadFrame(eyebrows = if (eyebrow) ({ SubjectEyebrowSkeleton() }) else null) {
            SkeletonText(MaterialTheme.typography.titleLargeEmphasized, lines = TITLE_SKELETON_LINES, widthFraction = 0.8f)
        }
        CommentFloorSkeleton(asPost = true)
        ListSortBarSkeleton()
        CommentFloorsSkeleton()
    }
}

/** 头部外框：左右 16、上 4、下 12；眉题行上 4，标题与眉题间 6（原型值，设计决定 not in M3）。真实头部与骨架共用。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThreadHeadFrame(
    eyebrows: (@Composable FlowRowScope.() -> Unit)?,
    title: @Composable () -> Unit,
) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
        if (eyebrows != null) {
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), content = eyebrows)
        }
        Column(Modifier.padding(top = 6.dp)) { title() }
    }
}

private const val TITLE_SKELETON_LINES = 2

/**
 * 顶栏标题当上级的去处（话题所在的小组 / 作品讨论版，单集讨论的作品）：名字前一张小图，整条可点。
 * 小图的格子是 [TitleLinkImageSize]、圆角 4。
 */
fun subjectTitleLink(cover: String?, onClick: () -> Unit): TitleLink = TitleLink(onClick) {
    SubjectPoster(cover, Modifier.size(TitleLinkImageSize), shape = TitleImageShape)
}

/** 小组话题的顶栏标题去处，见 [subjectTitleLink]。小组图标是方图，按原图比例画（[GroupIcon]），放在与封面同高的格子里。 */
fun groupTitleLink(icon: String?, onClick: () -> Unit): TitleLink = TitleLink(onClick) {
    Box(Modifier.size(TitleLinkImageSize), contentAlignment = Alignment.Center) {
        GroupIcon(icon, TitleLinkImageSize.width, TitleImageShape)
    }
}

/** 短信会话的顶栏标题去处（对方的主页），见 [subjectTitleLink]。头像是圆的，放在与封面同高的格子里。 */
fun userTitleLink(avatar: String?, onClick: () -> Unit): TitleLink = TitleLink(onClick) {
    Box(Modifier.size(TitleLinkImageSize), contentAlignment = Alignment.Center) {
        CircleAvatar(avatar, description = null, TitleLinkImageSize.width)
    }
}

private val TitleImageShape = ShapeDefaults.ExtraSmall
