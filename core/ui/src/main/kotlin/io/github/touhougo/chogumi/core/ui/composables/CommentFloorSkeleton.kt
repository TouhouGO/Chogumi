package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.ui.common.ReaderTypography

/**
 * 评论楼层的几何（设计决定，not in M3）：左右 16 与列表一致；头像与昵称间距 10；头部与正文间距主楼 8、楼中楼 4。
 * core:reader 的楼层与这里的 [CommentFloorSkeleton] 都读它。
 */
object FloorGeometry {
    val Padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    val ReplyPadding = PaddingValues(vertical = 8.dp)

    /**
     * 主楼作者头像边长（圆形 32×32）。头像并进头部一行、正文通栏，
     * 原型的 40 是「头像独占一列」版式的值，挤在昵称行里偏大。
     */
    val AvatarSize = 32.dp

    /** 楼中楼二级回复作者头像边长（圆形 24×24，与时间并进同一行）。 */
    val ReplyAvatarSize = 24.dp
    val AvatarGap = 10.dp
    val BodyTopGap = 8.dp
    val ReplyBodyTopGap = 4.dp

    /** 正文与下面那一排贴贴之间。 */
    val ReactionsTopGap = 8.dp
}

/**
 * 一层楼的占位（骨架用）：与 core:reader 的楼层（`CommentFloor`）同一组几何（[FloorGeometry]）与同一字阶（[ReaderTypography]），
 * 头部画昵称、时间两行，正文画几行。[asPost] 时是帖子正文：正文字阶 article、行数多些。行数是设计决定（not in M3）。
 * [reactions] 是正文下面那一排贴贴的位置（手势引导里贴贴的示意放在这里），与楼层里贴贴的位置一样。
 */
@Composable
fun CommentFloorSkeleton(modifier: Modifier = Modifier, asPost: Boolean = false, reactions: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(FloorGeometry.Padding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkeletonBox(Modifier.size(FloorGeometry.AvatarSize), shape = CircleShape)
            Spacer(Modifier.width(FloorGeometry.AvatarGap))
            Column(Modifier.weight(1f)) {
                SkeletonText(ReaderTypography.author, widthFraction = 0.3f)
                SkeletonText(ReaderTypography.meta, widthFraction = 0.2f)
            }
        }
        SkeletonText(
            style = if (asPost) ReaderTypography.article else ReaderTypography.comment,
            modifier = Modifier.padding(top = FloorGeometry.BodyTopGap),
            lines = if (asPost) POST_SKELETON_LINES else FLOOR_SKELETON_LINES,
        )
        if (reactions != null) Box(Modifier.padding(top = FloorGeometry.ReactionsTopGap)) { reactions() }
    }
}

private const val POST_SKELETON_LINES = 6
private const val FLOOR_SKELETON_LINES = 2
