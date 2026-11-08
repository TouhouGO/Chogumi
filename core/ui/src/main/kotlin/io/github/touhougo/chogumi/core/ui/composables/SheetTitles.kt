package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

/**
 * 面板的标题行：标题 `titleLarge` 靠左占满剩余宽度，确认类动作放 [trailing]（标题行右侧；下滑或点遮罩就是取消）。
 * 所有面板共用这一份：起始边距 [ChogumiDimens.ScreenPadding]、最小高度 48dp、下边距 8dp。
 * 标题只占一行，放不下在句尾省略；[trailing] 保持完整。
 */
@Composable
fun SheetTitleRow(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    SheetTitleRow(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        },
        modifier = modifier,
        trailing = trailing,
    )
}

/**
 * 同一个标题行，标题位放的是一组页签（铃铛面板的「通知 | 短信」）：面板的几页共用一行，不再另写一行标题。
 * [title] 自己占满剩余宽度（`Modifier.weight(1f)`）；边距、高度与 [trailing] 同上。
 */
@Composable
fun SheetTitleRow(
    title: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.sheetTitleFrame(),
        horizontalArrangement = Arrangement.spacedBy(SheetTitleRowSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        title()
        trailing()
    }
}

/**
 * 面板标题由一组页签和一个文字动作组成时使用的标题行。先在动作占位后的剩余宽度里排整组；
 * 整组会被迫收进「更多」时，把动作移到第二行靠右，让选择组先拿到整行，再按它自己的溢出规则排。
 * 放得下时与 [SheetTitleRow] 的单行布局相同，1× 常规宽度不变。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> ChoiceSheetTitleRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    trailingText: String,
    trailingEnabled: Boolean,
    onTrailing: () -> Unit,
    modifier: Modifier = Modifier,
    badge: (T) -> Int = { 0 },
) {
    val labels = options.map { label(it) }
    val labelIndex = remember(options) { options.withIndex().associate { (index, option) -> option to index } }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val measurer = rememberTextMeasurer()
        val actionStyle = ButtonDefaults.textStyleFor(ButtonDefaults.MinHeight)
        val actionWidth = remember(trailingText, actionStyle, density, layoutDirection) {
            with(density) {
                val textWidth = measurer.measure(trailingText, actionStyle, maxLines = 1, softWrap = false).size.width
                val padding = ButtonDefaults.TextButtonContentPadding
                val contentWidth = textWidth + padding.calculateLeftPadding(layoutDirection).roundToPx() +
                    padding.calculateRightPadding(layoutDirection).roundToPx()
                maxOf(contentWidth, ButtonDefaults.MinWidth.roundToPx())
            }
        }
        val frameAndActionWidth = with(density) {
            2 * ChogumiDimens.ScreenPadding.roundToPx() + actionWidth + SheetTitleRowSpacing.roundToPx()
        }
        val choiceRoom = (constraints.maxWidth - frameAndActionWidth).coerceAtLeast(0)
        val fitWithAction = rememberTextChoiceFit(labels, options.map(badge), choiceRoom)
        val stackTrailing = fitWithAction is ChoiceFit.Overflow

        @Composable
        fun Choice() {
            ConnectedChoiceGroup(
                options = options,
                selected = selected,
                label = { labels[labelIndex.getValue(it)] },
                onSelect = onSelect,
                badge = badge,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        @Composable
        fun Trailing() {
            TextButton(
                onClick = onTrailing,
                enabled = trailingEnabled,
            ) {
                Text(trailingText)
            }
        }

        if (stackTrailing) {
            Column(
                modifier = Modifier.sheetTitleFrame(),
                // 两行属于同一个标题区，仍用一个按钮间距；我的设计决定（not in M3）。
                verticalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                Choice()
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Trailing() }
            }
        } else {
            SheetTitleRow(title = { Box(Modifier.weight(1f)) { Choice() } }) { Trailing() }
        }
    }
}

/** 标题区的框：起始边距、最小高度与下边距，单行标题行与两行的页签标题共用。 */
private fun Modifier.sheetTitleFrame(): Modifier = fillMaxWidth()
    .heightIn(min = SheetTitleRowMinHeight)
    .padding(horizontal = ChogumiDimens.ScreenPadding)
    .padding(bottom = 8.dp)

private val SheetTitleRowMinHeight = 48.dp
private val SheetTitleRowSpacing = 12.dp

/**
 * 面板里一组设置的分组标题：`labelLarge`、`onSurfaceVariant`（docs/design/typography.md「面板里一项设置的名字」）。
 * [color] 默认 `onSurfaceVariant`，需要表达可用 / 不可用时由调用处换。[trailing] 是紧跟标题的小标记（如「单选」）。
 */
@Composable
fun SheetSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = color)
        trailing?.invoke()
    }
}
