package io.github.touhougo.chogumi.core.ui.tour

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.AdaptiveGridSkeleton
import io.github.touhougo.chogumi.core.ui.composables.GridCell
import io.github.touhougo.chogumi.core.ui.composables.PeopleListHead
import io.github.touhougo.chogumi.core.ui.composables.PersonGridItemSkeleton
import io.github.touhougo.chogumi.core.ui.composables.ReactionChipFace
import io.github.touhougo.chogumi.core.ui.composables.ReactionChipGeometry
import io.github.touhougo.chogumi.core.ui.composables.SelectableChip
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.peopleListSheetColor
import io.github.touhougo.chogumi.core.ui.composables.reactionChipContainer
import io.github.touhougo.chogumi.core.ui.composables.reactionChipContent
import io.github.touhougo.chogumi.core.ui.theme.raisedContainer
import kotlinx.coroutines.delay

/** 第一层楼下面那一排贴贴的人数；第二个是我贴的（序号从 0 起）。 */
private val ChipCounts = listOf(12, 5, 3)
private const val MineIndex = 1

/** 手指按住的那一个贴贴：第一个（别人贴的），名单面板停在它上面。 */
private const val HoldIndex = 0

/** 名单面板里画几行人。 */
private const val PeopleRows = 2

private const val FloorCount = 3
private const val RestMillis = 700L
private const val SheetMillis = 2200L

/**
 * 按住贴贴那一页：第一层楼正文下面一排贴贴，按住第一个，它压下，压暗、名单面板升起——标题、一排表情筛选（停在按住的那一个）、
 * 下面一格格的人。楼层是 [DemoFloor]，贴贴是楼层里 chip 的样子 [ReactionChipFace]（表情是色块），面板是 [TourSheet]，
 * 里面是名单面板的头部 [PeopleListHead] 与人物格的骨架。
 */
@Composable
internal fun ReactionDemo(playing: Boolean, modifier: Modifier = Modifier) {
    TourStage(modifier) { stage ->
        val motion = MaterialTheme.motionScheme
        val longPressMillis = LocalViewConfiguration.current.longPressTimeoutMillis
        val finger = remember { TourFinger() }
        val sheet = remember { TourSheetState() }
        var pressed by remember { mutableStateOf(false) }
        // 按住的那一个贴贴的中心（演示台坐标）：放置时记下，只给脚本读
        val hold = remember { HoldTarget() }

        LaunchedEffect(playing) {
            finger.reset()
            pressed = false
            sheet.reset()
            if (!playing) return@LaunchedEffect
            while (true) {
                delay(RestMillis)
                finger.press(hold.center)
                pressed = true
                delay(longPressMillis)
                finger.lift()
                pressed = false
                sheet.open(motion)
                delay(SheetMillis)
                sheet.close(motion)
            }
        }

        Column {
            repeat(FloorCount) { index ->
                DemoFloor(
                    reactions = if (index == 0) {
                        {
                            Row(horizontalArrangement = Arrangement.spacedBy(ReactionChipGeometry.Spacing)) {
                                ChipCounts.forEachIndexed { chip, count ->
                                    DemoReactionChip(
                                        count = count,
                                        mine = chip == MineIndex,
                                        pressed = chip == HoldIndex && pressed,
                                        modifier = if (chip == HoldIndex) Modifier.onPlaced { hold.center = stage.centerOf(it) } else Modifier,
                                    )
                                }
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }
        TourSheet(sheet, color = peopleListSheetColor) {
            // 面板里竖着排：人物格按自己的高排整行，放不下的由面板下沿裁掉，不被挤进剩下的高度
            Column(Modifier.verticalScroll(rememberScrollState(), enabled = false)) {
                PeopleListHead(stringResource(R.string.reaction_users_title)) {
                    ChipCounts.forEachIndexed { chip, count ->
                        SelectableChip(
                            label = "$count",
                            selected = chip == HoldIndex,
                            labelLeading = { SkeletonBox(Modifier.size(FilterChipDefaults.IconSize), CircleShape) },
                            onClick = {},
                        )
                    }
                }
                AdaptiveGridSkeleton(rows = PeopleRows, cell = GridCell.Person) { PersonGridItemSkeleton() }
            }
        }
        FingerDot(finger)
    }
}

private class HoldTarget {
    var center = Offset.Zero
}

/**
 * 一个贴贴：楼层里 chip 的样子（[ReactionChipFace]），表情是色块，计数是字。我贴的取 [reactionChipContainer]；
 * 别人的在页面上是 `surfaceContainer`，演示台本身就是这个颜色，所以取 `raisedContainer`。[pressed] 时压一层按下态。
 */
@Composable
private fun DemoReactionChip(count: Int, mine: Boolean, pressed: Boolean, modifier: Modifier) {
    ReactionChipFace(
        container = if (mine) reactionChipContainer(selected = true) else MaterialTheme.colorScheme.raisedContainer,
        content = reactionChipContent(mine),
        modifier = modifier,
        rowModifier = Modifier.pressedLayer(pressed),
    ) {
        SkeletonBox(Modifier.size(ReactionChipGeometry.Smiley), CircleShape)
        Text("$count", style = MaterialTheme.typography.labelMedium)
    }
}
