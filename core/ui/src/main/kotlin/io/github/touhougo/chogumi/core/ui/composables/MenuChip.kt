package io.github.touhougo.chogumi.core.ui.composables

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.touhougo.chogumi.core.res.R

/**
 * 下拉 chip：[icon] + 当前项文案 + 下拉箭头，点开是单选菜单 [SelectableDropdownMenu]，当前项高亮。
 * 排序行（[ListSortBar]）的排序、吐槽箱标题右侧的状态筛选共用这一个。
 */
@Composable
fun <T> MenuChip(
    selected: T,
    options: List<T>,
    label: @Composable (T) -> String,
    @DrawableRes icon: Int,
    onSelect: (T) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        SelectableChip(
            label = label(selected),
            selected = false,
            unselectedLeadingIcon = icon,
            trailingIcon = R.drawable.expand_more_24,
            onClick = { menuOpen = true },
        )
        SelectableDropdownMenu(
            open = menuOpen,
            options = options,
            selected = selected,
            label = label,
            onDismiss = { menuOpen = false },
            onPick = onSelect,
        )
    }
}
