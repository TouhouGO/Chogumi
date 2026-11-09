package io.github.touhougo.chogumi.core.ui.tour

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.model.COMMON_REACTIONS
import io.github.touhougo.chogumi.core.ui.composables.AppMenuItem
import io.github.touhougo.chogumi.core.ui.composables.CommentFloorSkeleton
import io.github.touhougo.chogumi.core.ui.composables.MenuGroups
import io.github.touhougo.chogumi.core.ui.composables.ReactionGridGeometry
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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
 * 菜单是分组下拉菜单的各组（[MenuGroups]），表情是色块。
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

        Column {
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
 * 一层楼的示意：就是评论楼层的骨架 [CommentFloorSkeleton]，[pressed] 时压一层 pressed state layer。
 * 单集面板里的评论也是它。
 */
@Composable
internal fun DemoFloor(modifier: Modifier = Modifier, pressed: Boolean = false) {
    val pressedAlpha by animateFloatAsState(if (pressed) PressedStateAlpha else 0f, MaterialTheme.motionScheme.fastEffectsSpec(), label = "tour_floor")
    CommentFloorSkeleton(modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = pressedAlpha)))
}

/**
 * 长按菜单的示意：分组菜单的各组（[MenuGroups]，与弹出的菜单同一个画法），上面一组是贴贴的表情格子（[ReactionGridGeometry]，
 * 表情是色块），下面一组是楼层内容的菜单项（[AppMenuItem]）。
 */
@Composable
private fun DemoFloorMenu(modifier: Modifier) {
    val columns = ReactionGridGeometry.columnsOf(COMMON_REACTIONS.size)
    Column(modifier) {
        MenuGroups(
            listOf(
                {
                    Column(Modifier.padding(horizontal = ReactionGridGeometry.Padding)) {
                        repeat(ReactionGridGeometry.ROWS) {
                            Row {
                                repeat(columns) {
                                    Box(Modifier.size(ReactionGridGeometry.Cell), contentAlignment = Alignment.Center) {
                                        SkeletonBox(Modifier.size(ReactionGridGeometry.Smiley), CircleShape)
                                    }
                                }
                            }
                        }
                    }
                },
                {
                    ContentActions.forEach { entry ->
                        AppMenuItem(stringResource(entry.label), onDismiss = {}, icon = entry.icon, onClick = {})
                    }
                },
            ),
        )
    }
}
