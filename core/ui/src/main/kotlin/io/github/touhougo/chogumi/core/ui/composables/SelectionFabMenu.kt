package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ExpressiveMotion

/**
 * 「从几个选项里选一个」的右下角 FAB 菜单：收起时 FAB 显示当前选项的图标，展开后列出全部选项，
 * 点一项即选中并收起。进度页（收藏状态）与搜索页（条目类型）共用这一份，外观与动效只在这里定义。
 * **位置也只在这里定**：自己贴所在 Box 的右下角，外边距是 FloatingActionButtonMenu 自带的 16dp，调用处不传 align / padding
 * （全 App FAB 规则见 FabPlacement.kt；列表底部留白取 [FabClearance]）。
 *
 * M3 Expressive 原生 FAB 菜单（fabs.md:241-269）：
 * 1. FloatingActionButtonMenu：承载展开收起与菜单项列表
 * 2. button 用原生 ToggleFloatingActionButton：同一个元素在收起↔展开间形变（fabs.md:252-253）。
 *    尺寸档位选 small（默认档，不传任何档位参数）：收起 56dp/圆角16dp（CornerLarge）→
 *    展开 56dp/圆角28dp（=FabFinalSize/2，全药丸）。
 *    选 small 档是我的设计决策（not in M3 / 与 fabs.md:90 有意相左——small FAB
 *    「no longer recommended」），理由一：未展开的 FAB 压在列表上，medium（80dp）显得太大，
 *    可用档位里比 medium 更小的只有 small（56dp）；理由二：FAB menu 的 close
 *    button 由 FabFinalSize 定死 56dp（fabs.md:253），收起态同尺寸使 morph 只剩圆角
 *    16→28 与图标 24→20 两个维度，与展开态视觉连续。代价：放弃了 M3 Expressive 对
 *    FAB 尺寸的推荐档位；若要用推荐档位，把三个档位参数改成 …Medium() 即可。
 *    菜单整体包在 [ExpressiveMotion] 里：圆角/尺寸走 expressive 的 fastSpatial 弹簧，会 overshoot 回弹（motion.md:20-26）——正是要的"灵动"感；
 *    颜色插值在容器 lambda 内夹紧 [0,1]，
 *    因为 checkedProgress 是 spatial 弹簧会瞬时越过 1.0，不夹紧颜色会外推闪变（motion.md:24-26）。
 * 3. FloatingActionButtonMenuItem：圆角自适应内容，选中项呈现容器色差与 labelLargeEmphasized
 *    （M3 Expressive：选中态用 emphasized 字体）。
 *
 * My design decision (not in M3)：M3 的 FAB 菜单用于「同一动作下的相关操作」（fabs.md:248），
 * 这里用来切换筛选项是本项目的统一交互（产品决定），两页保持一致。
 *
 * 两处有意偏离 M3 的设计决策（产品决定）：
 * - 等宽：M3 要求菜单项按内容自适应、明文不许等宽（fabs.md:254、334），但「三次元」比其余两字
 *   选项宽一截、参差扎眼。做法是把标签文字定宽为全部选项里最宽的那个（按选中态的
 *   labelLargeEmphasized 与常态 labelLarge 取大，切换选中也不跳宽）。只定文字宽、不给容器定宽：
 *   原生菜单项靠内部 layout 按比例展开宽度做入场动画，给容器定宽会让药丸背景一上来就是满宽。
 * - 收起方式：M3 只给了 close 按钮（fabs.md:252-253），这里另加「手指落在菜单外（点击或开始滑动）」
 *   与返回键收起。菜单外的触摸只旁听不拦截，列表照常滚动、点到的条目照常响应，不吞第一下。
 *
 * 是 [BoxScope] 扩展：旁听层要铺满调用方的整个 Box，调用方把它放在页面内容之后即可。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> BoxScope.SelectionFabMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<T>,
    selected: T,
    iconRes: (T) -> Int,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    /** 收起态 FAB 的无障碍描述：说明它打开什么、当前是什么。必填，漏传是编译错。 */
    collapsedDescription: String,
    modifier: Modifier = Modifier,
    itemTestTag: (T) -> String? = { null },
) {
    Box(Modifier.matchParentSize().then(DismissOnOutsidePressElement(expanded) { onExpandedChange(false) }))
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = expanded,
        onBackCompleted = { onExpandedChange(false) },
    )

    val labels = options.map { label(it) }
    val labelStyle = MaterialTheme.typography.labelLarge
    val selectedLabelStyle = MaterialTheme.typography.labelLargeEmphasized
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelWidth = remember(labels, labelStyle, selectedLabelStyle, density) {
        val widest = labels.maxOfOrNull { text ->
            maxOf(
                textMeasurer.measure(text, labelStyle, maxLines = 1).size.width,
                textMeasurer.measure(text, selectedLabelStyle, maxLines = 1).size.width,
            )
        } ?: 0
        with(density) { widest.toDp() }
    }

    ExpressiveMotion {
        FloatingActionButtonMenu(
            expanded = expanded,
            button = {
                // 颜色在 Composable 上下文取值、lambda 内只插值（containerColor/iconColor lambda 本身非 Composable）；
                // progress 夹紧 [0,1] 防颜色 overshoot——checkedProgress 是 spatial 弹簧会瞬时越过 1.0
                val containerInitial = MaterialTheme.colorScheme.primaryContainer
                val containerFinal = MaterialTheme.colorScheme.secondaryContainer
                val iconInitial = MaterialTheme.colorScheme.onPrimaryContainer
                val iconFinal = MaterialTheme.colorScheme.onSecondaryContainer
                ToggleFloatingActionButton(
                    checked = expanded,
                    onCheckedChange = onExpandedChange,
                    containerColor = { progress ->
                        lerp(containerInitial, containerFinal, progress.coerceIn(0f, 1f))
                    },
                    modifier = Modifier
                        .testTag("SelectionFab")
                        .then(
                            if (!expanded) {
                                Modifier.semantics { contentDescription = collapsedDescription }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Icon(
                        painter = painterResource(if (expanded) R.drawable.close_24 else iconRes(selected)),
                        contentDescription = if (expanded) stringResource(R.string.close) else null,
                        // animateIcon 默认即 small 档图标：24dp 收起 → 20dp 展开（FabInitialIconSize → FabFinalIconSize）
                        modifier = with(ToggleFloatingActionButtonDefaults) {
                            Modifier.animateIcon(
                                { checkedProgress },
                                color = { progress ->
                                    lerp(iconInitial, iconFinal, progress.coerceIn(0f, 1f))
                                },
                            )
                        },
                    )
                }
            },
            modifier = modifier.align(Alignment.BottomEnd),
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = option == selected
                FloatingActionButtonMenuItem(
                    onClick = {
                        onSelect(option)
                        onExpandedChange(false)
                    },
                    icon = { Icon(painterResource(iconRes(option)), null) },
                    text = {
                        Text(
                            text = labels[index],
                            style = if (isSelected) selectedLabelStyle else labelStyle,
                            modifier = Modifier.width(labelWidth),
                        )
                    },
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = itemTestTag(option)?.let { Modifier.testTag(it) } ?: Modifier,
                )
            }
        }
    }
}

/**
 * 菜单外触摸的旁听层：铺满调用方 Box、叠在页面内容之上菜单之下，仅在菜单展开时生效。
 * 手指一按下就收起菜单，但不消费事件，并与下层兄弟节点共享命中（[sharePointerInputWithSiblings]），
 * 所以列表照常滚动、条目照常点击。落在菜单项与 close 按钮上的触摸先被它们命中，到不了这里。
 * 常驻而非展开时才挂载：按下即收起，若随之卸载本节点，会连带取消这次手势，列表就滚不动了。
 */
private data class DismissOnOutsidePressElement(
    val enabled: Boolean,
    val onDismiss: () -> Unit,
) : ModifierNodeElement<DismissOnOutsidePressNode>() {
    override fun create() = DismissOnOutsidePressNode(enabled, onDismiss)

    override fun update(node: DismissOnOutsidePressNode) {
        node.enabled = enabled
        node.onDismiss = onDismiss
    }
}

private class DismissOnOutsidePressNode(
    var enabled: Boolean,
    var onDismiss: () -> Unit,
) : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (enabled && pass == PointerEventPass.Main && pointerEvent.type == PointerEventType.Press) onDismiss()
    }

    override fun onCancelPointerInput() = Unit

    override fun sharePointerInputWithSiblings() = true
}
