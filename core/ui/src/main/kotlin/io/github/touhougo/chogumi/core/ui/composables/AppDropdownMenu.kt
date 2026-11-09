package io.github.touhougo.chogumi.core.ui.composables

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource

/**
 * 全 App 唯一的下拉菜单：M3 vertical menu（feedback.md Menus：新设计推荐 vertical menu，圆角），
 * 形状 `shapes.large`、容器色 `surfaceContainerLow`（standard 配色）。页面不直接调 M3 的 `DropdownMenu`（门禁 check_menus.py）。
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        content = content,
    )
}

/**
 * 分组的下拉菜单：M3 vertical menu 的 grouped 布局（feedback.md Menus「Gaps」：组与组之间留 gap，比分割线更清楚地表达
 * 哪些项是一类），每组一块圆角容器（[MenuDefaults.groupShape] 按组的位置给首、中、尾的形状），standard 配色。
 * gap 一个菜单至多一两道（[groups] 至多三组），组里不滚动（gap 不能用在会滚动的菜单里）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    groups: List<@Composable ColumnScope.() -> Unit>,
) {
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismissRequest) { MenuGroups(groups) }
}

/** 分组菜单里的各组：每组一块，组间 M3 菜单的 gap。弹出的 [AppDropdownMenu] 与手势引导里长按菜单的示意都画它。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MenuGroups(groups: List<@Composable ColumnScope.() -> Unit>) {
    groups.forEachIndexed { index, group ->
        if (index > 0) Spacer(Modifier.height(MenuDefaults.GroupSpacing))
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(index, groups.size), content = group)
    }
}

/**
 * 菜单里的一项（M3 vertical menu 的 menu item）：文字，可带前置图标（颜色 `onSurfaceVariant` 由 M3 菜单项默认色承担）。
 * 全 App 的菜单项只此一种画法（单选菜单的选中项在 [SelectableDropdownMenu]）：点了先 [onDismiss] 收起菜单，再执行 [onClick]。
 */
@Composable
fun AppMenuItem(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = icon?.let { { Icon(painterResource(it), contentDescription = null) } },
        onClick = {
            onDismiss()
            onClick()
        },
        modifier = modifier,
    )
}
