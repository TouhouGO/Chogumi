package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.res.R

/**
 * 排序的两种顺序（菜单分隔线下的一组）：[forward] 是这一档的默认顺序、[backward] 是反过来，
 * 都是写给人看的叫法（「新到旧」「A 到 Z」）；[reversed] 为当前是否反过来。
 */
class SortDirection(
    val reversed: Boolean,
    val forward: String,
    val backward: String,
    val onSelect: (reversed: Boolean) -> Unit,
)

/**
 * 排序按钮：排序图标，点开是与 [ListSortBar] 同一份单选菜单 [SelectableDropdownMenu]，当前项高亮。
 * 页面上没有别的东西要和排序并排时用它，排序不独占一行；读屏念「排序：当前项」（有 [direction] 时连顺序一起念）。
 *
 * [direction] 给了时，菜单在分隔线下再列两种顺序（M3 菜单单选、分组用分隔线），直接点选，不靠「再点一次反过来」。
 */
@Composable
fun <T> SortMenuAction(
    sort: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    direction: SortDirection? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val current = label(sort)
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                painter = painterResource(R.drawable.sort_24),
                contentDescription = if (direction == null) {
                    stringResource(R.string.sort_menu_description, current)
                } else {
                    stringResource(
                        R.string.sort_menu_description_with_direction,
                        current,
                        if (direction.reversed) direction.backward else direction.forward,
                    )
                },
            )
        }
        SelectableDropdownMenu(
            open = menuOpen,
            options = options,
            selected = sort,
            label = label,
            onDismiss = { menuOpen = false },
            onPick = onSelect,
            secondGroup = direction?.let {
                SelectableMenuGroup(
                    options = listOf(false, true),
                    selected = it.reversed,
                    label = { reversed -> if (reversed) it.backward else it.forward },
                    onPick = it.onSelect,
                )
            },
        )
    }
}
