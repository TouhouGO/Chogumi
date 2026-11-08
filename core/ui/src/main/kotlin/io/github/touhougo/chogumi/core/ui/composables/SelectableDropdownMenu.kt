package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 菜单里的一组单选：有哪些项、选中哪一项、怎么叫、点了做什么。
 * [SelectableDropdownMenu] 的第二组（分隔线下面，如排序的两种顺序）用它给出。
 */
class SelectableMenuGroup<T>(
    val options: List<T>,
    val selected: T?,
    val label: @Composable (T) -> String,
    val onPick: (T) -> Unit,
) {
    @Composable
    internal fun Items(onDone: () -> Unit) {
        options.forEach { option ->
            SelectableMenuItem(
                text = label(option),
                selected = option == selected,
                onClick = {
                    onDone()
                    onPick(option)
                },
            )
        }
    }
}

/**
 * 单选下拉菜单（原型 `.menu` / `.dmenu`，两处 CSS 相同）：当前项 `secondaryContainer` 高亮、700 字重。
 * 排序行（[ListSortBar]：讨论版话题、日志 / 目录留言、目录收录）与顶栏排序按钮（[SortMenuAction]）的菜单都用这一份。
 *
 * 菜单圆角 16、项高 48（M3 菜单项）、项圆角 12、项左右缩进 4（设计决定，not in M3，原型值）。
 * [secondGroup] 给了时，分隔线下面再排一组单选（排序的两种顺序）；M3 菜单用分隔线把项分成组
 * （feedback.md Menus「Dividers」），分隔线上下各留 8dp（原型 `.mdiv`，设计决定，not in M3）。
 */
@Composable
fun <T> SelectableDropdownMenu(
    open: Boolean,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
    secondGroup: SelectableMenuGroup<*>? = null,
) {
    AppDropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        SelectableMenuGroup(options, selected, label, onPick).Items(onDone = onDismiss)
        if (secondGroup != null) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            secondGroup.Items(onDone = onDismiss)
        }
    }
}

/** 菜单里的一项：选中的那项 `secondaryContainer` 底、emphasized 字阶，读屏念「已选中」。 */
@Composable
private fun SelectableMenuItem(text: String, selected: Boolean, onClick: () -> Unit) {
    val contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                style = if (selected) MaterialTheme.typography.bodyMediumEmphasized else MaterialTheme.typography.bodyMedium,
            )
        },
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
            .semantics { this.selected = selected },
        colors = MenuDefaults.itemColors(textColor = contentColor),
    )
}
