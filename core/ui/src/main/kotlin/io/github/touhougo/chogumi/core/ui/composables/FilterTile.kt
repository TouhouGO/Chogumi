package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.raisedContainer

/**
 * 筛选格：左边一摞封面，右边名字与「N 部」。
 * 进度页筛选面板里的标签与分类取值都是这一种格子，多选，点一下选上、再点取消。
 *
 * - 封面是这一项下最近动过的几部（[covers]，最近的在前，最多三张）：保持原图 3:4（24 × 32dp，圆角 4 = shapes.extraSmall），
 *   最近的一张立在最前，另两张压在后面，向两边各偏 6dp、斜 6°，像扑克牌一样叠成一小摞；封面位固定 56dp 宽，
 *   所以名字都从同一条竖线开始。选中时最前一张抬起 3dp、后两张张开到 9dp / 13°，张开后仍在格子里。
 * - 格子至少 56dp 高（字号放大时随文字长高），底色 [raisedContainer]（比面板亮一档）、圆角 16（shapes.large）；选中 `secondaryContainer`、
 *   圆角 28（shapes.extraLarge）；按下圆角收到 8（shapes.small，M3 Expressive：按下更方）。
 * - 形状与牌摞走 fast spatial（几何），底色走 default effects（M3 motion：颜色是 effects，不过冲）。
 * - 整块是 [PressArea] 的开关块：读屏读出选没选，水波纹按当前圆角裁，封面与文字不裁。
 *
 * 尺寸、叠法与按下的圆角是我的设计决定（not in M3）；圆角取 M3 形状阶。
 */
@Composable
fun FilterTile(
    label: String,
    count: Int,
    covers: List<SubjectBo>,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val shape = rememberMorphingShape(
        target = when {
            pressed -> MaterialTheme.shapes.small
            selected -> MaterialTheme.shapes.extraLarge
            else -> MaterialTheme.shapes.large
        },
        spec = spatial,
    )
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.raisedContainer,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "FilterTileContainer",
    )
    val fan by animateFloatAsState(if (selected) 1f else 0f, spatial, label = "FilterTileFan")
    PressArea(
        checked = selected,
        onCheckedChange = { onClick() },
        shape = shape,
        color = container,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier.heightIn(min = FilterTileHeight).padding(start = 8.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverStack(covers, fan)
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = if (selected) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluralStringResource(R.plurals.work_count, count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 一摞封面：后面的两张先画、最前一张最后画。每张绕自己的底边中点转，[fan] 是张开的程度（0 = 收着，1 = 选中时张开）。
 * 张开到头时最外一角离封面位中线 9 + 12·cos13° + 32·sin13° ≈ 28dp，正好在 56dp 宽的封面位里。
 */
@Composable
private fun CoverStack(covers: List<SubjectBo>, fan: Float) {
    Box(Modifier.size(width = CoverStackWidth, height = CoverStackHeight)) {
        covers.take(FanSpreads.size).withIndex().reversed().forEach { (index, subject) ->
            key(subject.id) {
                val spread = FanSpreads[index]
                SubjectPoster(
                    url = subject.coverUrl,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 2.dp)
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0.5f, 1f)
                            translationX = (spread.restX + (spread.openX - spread.restX) * fan).dp.toPx()
                            translationY = (spread.openLift * fan).dp.toPx()
                            rotationZ = spread.restAngle + (spread.openAngle - spread.restAngle) * fan
                        }
                        .width(CoverWidth),
                    shape = MaterialTheme.shapes.extraSmall,
                    shadowElevation = 1.dp,
                )
            }
        }
    }
}

/** 一摞里一张封面的位置：收着时与张开时的横向偏移（dp）、角度（度），以及张开时抬起多少（dp，负数向上）。 */
private class FanSpread(val restX: Float, val openX: Float, val restAngle: Float, val openAngle: Float, val openLift: Float)

/** 最前、左后、右后三张（原型 `.av:nth-child(1..3)`）。 */
private val FanSpreads = listOf(
    FanSpread(restX = 0f, openX = 0f, restAngle = 0f, openAngle = 0f, openLift = -3f),
    FanSpread(restX = -6f, openX = -9f, restAngle = -6f, openAngle = -13f, openLift = 0f),
    FanSpread(restX = 6f, openX = 9f, restAngle = 6f, openAngle = 13f, openLift = 0f),
)

private val FilterTileHeight = 56.dp
private val CoverStackWidth = 56.dp
private val CoverStackHeight = 42.dp
private val CoverWidth = 24.dp
