package io.github.touhougo.chogumi.core.ui.tour

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/*
 * 楼层的几何照 core:reader 评论楼层的取值（CommentThreadItem.kt：内边距 16 / 12、头像 32、头像与名字间 10、正文前 8；
 * ReactionBar.kt：贴贴格 44、表情 24、两行、左右 8）。那几个值是 core:reader 的私有常量，这里读不到，演示照它们写一份。
 */
private val FloorPaddingHorizontal = 16.dp
private val FloorPaddingVertical = 12.dp
private val AvatarSize = 32.dp
private val AvatarGap = 10.dp
private val BodyTopGap = 8.dp
private val ReactionCell = 44.dp
private val ReactionSmiley = 24.dp
private val ReactionGridPadding = 8.dp
private const val ReactionRows = 2
private const val ReactionColumns = 6

/** 楼层的按下态：M3 的 pressed state layer，onSurface 10%（tokens.md State layer opacity）。 */
private const val PressedStateAlpha = 0.1f

/** 菜单离按住的那层楼底边多远、离左边多远：菜单从楼层正文的左边弹出（设计决定，not in M3）。 */
private val MenuInset = 8.dp

/** 菜单弹出时从这么高（纵向比例）展开到全高，与官方下拉菜单的进场一样从顶边长出来（设计决定，not in M3）。 */
private const val MenuFromScale = 0.8f

private const val FloorCount = 3
private const val RestMillis = 700L
private const val MenuMillis = 2400L

/** 菜单里这层楼内容的那一组：复制、网页、报告（楼层长按菜单的同一组，同样的字与图标）。 */
private val ContentActions = listOf(
    MenuEntry(R.string.copy, R.drawable.content_copy_24),
    MenuEntry(R.string.page_more_web, R.drawable.open_in_new_24),
    MenuEntry(R.string.report, R.drawable.flag_24),
)

private class MenuEntry(@StringRes val label: Int, @DrawableRes val icon: Int)

/**
 * 第三页：按住第一层楼，楼层压下，长按菜单弹出——上面是贴贴的表情格子，下面一组是复制、网页、报告。
 * 菜单按分组下拉菜单的样子画（每组一块 [MenuDefaults.groupShape]，组间 [MenuDefaults.GroupSpacing]），表情是色块。
 */
@Composable
internal fun FloorDemo(playing: Boolean, modifier: Modifier = Modifier) {
    TourStage(modifier) { stage ->
        val motion = MaterialTheme.motionScheme
        val longPressMillis = LocalViewConfiguration.current.longPressTimeoutMillis
        val finger = remember { TourFinger() }
        var pressed by remember { mutableStateOf(false) }
        var menuShown by remember { mutableStateOf(false) }
        val menu = remember { Animatable(0f) }
        val firstFloor = remember { FloorBounds() }

        LaunchedEffect(playing) {
            finger.reset()
            pressed = false
            menuShown = false
            menu.snapTo(0f)
            if (!playing) return@LaunchedEffect
            while (true) {
                delay(RestMillis)
                finger.press(firstFloor.press)
                pressed = true
                delay(longPressMillis)
                menuShown = true
                finger.lift()
                pressed = false
                menu.animateTo(1f, motion.fastSpatialSpec())
                delay(MenuMillis)
                menuShown = false
                menu.animateTo(0f, motion.fastEffectsSpec())
            }
        }

        Column(Modifier.padding(top = BodyTopGap)) {
            repeat(FloorCount) { index ->
                DemoFloor(
                    pressed = index == 0 && pressed,
                    modifier = if (index == 0) Modifier.onPlaced { firstFloor.place(stage.centerOf(it), it.size.width, it.size.height) } else Modifier,
                )
            }
        }
        if (menuShown || menu.value > 0f) {
            DemoFloorMenu(
                Modifier
                    .offset { IntOffset((firstFloor.left + MenuInset.toPx()).roundToInt(), firstFloor.bottom.roundToInt()) }
                    .graphicsLayer {
                        alpha = menu.value.coerceIn(0f, 1f)
                        scaleY = MenuFromScale + (1f - MenuFromScale) * menu.value
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
            )
        }
        FingerDot(finger)
    }
}

/** 第一层楼在演示台里的位置：手指按在正文中间，菜单挂在它底下。放置时记下，只给脚本与菜单读，不是界面状态。 */
private class FloorBounds {
    var press = Offset.Zero
        private set
    var left = 0f
        private set
    var bottom = 0f
        private set

    fun place(center: Offset, width: Int, height: Int) {
        press = center
        left = center.x - width / 2f
        bottom = center.y + height / 2f
    }
}

/**
 * 一层楼的示意：头像、名字一行、两行正文（评论楼层的版式，几何见上）。[pressed] 时压一层 pressed state layer。
 * 单集面板里的评论也是它。
 */
@Composable
internal fun DemoFloor(modifier: Modifier = Modifier, pressed: Boolean = false) {
    val pressedAlpha by animateFloatAsState(if (pressed) PressedStateAlpha else 0f, MaterialTheme.motionScheme.fastEffectsSpec(), label = "tour_floor")
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = pressedAlpha))
            .padding(horizontal = FloorPaddingHorizontal, vertical = FloorPaddingVertical),
    ) {
        SkeletonBox(Modifier.size(AvatarSize), CircleShape)
        Spacer(Modifier.width(AvatarGap))
        Column(Modifier.weight(1f)) {
            SkeletonText(MaterialTheme.typography.titleSmall, widthFraction = 0.3f)
            SkeletonText(MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = BodyTopGap), lines = 2)
        }
    }
}

/** 长按菜单的示意：贴贴的表情格子一组、楼层内容一组，standard 配色、Level 2 阴影（component-tokens.md Menu ContainerElevation）。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DemoFloorMenu(modifier: Modifier) {
    val groups = 2
    Column(modifier.width(ReactionCell * ReactionColumns + ReactionGridPadding * 2)) {
        MenuGroup(index = 0, count = groups) {
            Column(Modifier.padding(horizontal = ReactionGridPadding)) {
                repeat(ReactionRows) {
                    Row {
                        repeat(ReactionColumns) {
                            Box(Modifier.size(ReactionCell), contentAlignment = Alignment.Center) {
                                SkeletonBox(Modifier.size(ReactionSmiley), CircleShape)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(MenuDefaults.GroupSpacing))
        MenuGroup(index = 1, count = groups) {
            ContentActions.forEach { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = MenuItemHeight).padding(horizontal = 12.dp),
                ) {
                    Icon(painterResource(entry.icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(entry.label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MenuGroup(index: Int, count: Int, content: @Composable () -> Unit) {
    Surface(
        shape = MenuDefaults.groupShape(index, count).shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = MenuElevation,
    ) {
        Column(Modifier.padding(vertical = MenuGroupPadding)) { content() }
    }
}

/** 菜单项的高、组的上下内边距、阴影：M3 菜单（component-tokens.md：列表项 48、SegmentedMenu GroupPadding 4、Level 2 = 3dp）。 */
private val MenuItemHeight = 48.dp
private val MenuGroupPadding = 4.dp
private val MenuElevation = 3.dp
