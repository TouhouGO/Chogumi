package io.github.touhougo.chogumi.core.ui.composables

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.model.IndexTarget
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.LocalIndexPicker
import io.github.touhougo.chogumi.core.ui.common.LoginGate
import io.github.touhougo.chogumi.core.ui.common.shareLink

/**
 * 一页在旧站的网页：[url] 只由 core:model 的 `BgmWeb` 拼，[title] 是分享时放在链接上面的那一行
 * （作品、人物用 `displayNameOf`；还没到时给空串，分享就只有链接）。
 */
@Immutable
data class PageLink(val title: String, val url: String)

/**
 * 这一页能收进自己的目录：[target] 是页面交出的语义目标（作品、角色、人物、章节、日志、话题）；
 * 点「收录」先过登录门 [gate]，未登录去登录页。只给能收录的页面用。
 */
@Immutable
class IndexCollect(val target: IndexTarget, val gate: LoginGate)

/**
 * 页面顶栏右上角的 ⋮（M3 顶栏的溢出菜单，`more_vert`）：**全 App 唯一的页面「更多」菜单**。
 *
 * 固定的项是「网页」（经 [LocalUriHandler] 在 Custom Tab 打开 [link] 的地址）、「分享」（系统分享面板，[shareLink]），
 * 能收录的页面（给了 [collect]）再加「收录」（打开 [LocalIndexPicker] 的收录面板）；
 * 页面自己的项由 [extra] 排在后面，另成一组，与固定项之间隔一道 gap（分组的 [AppDropdownMenu]；feedback.md Menus：gap 把菜单项分组，每个菜单一到两道）。
 * 菜单与收录面板的开合都在这里，页面不另存一份；点了哪一项菜单都先收起。
 */
@Composable
fun PageMoreMenu(link: PageLink, collect: IndexCollect? = null, extra: (@Composable PageMoreMenuScope.() -> Unit)? = null) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val picker = LocalIndexPicker.current
    val browser = LocalUriHandler.current
    val context = LocalContext.current
    val scope = PageMoreMenuScope { expanded = false }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.more_vert_24), stringResource(R.string.page_more))
        }
        val fixed: @Composable ColumnScope.() -> Unit = {
            scope.item(R.string.page_more_web, R.drawable.open_in_new_24) { browser.openUri(link.url) }
            scope.item(R.string.page_more_share, R.drawable.share_24) { context.shareLink(link.title, link.url) }
            if (collect != null && picker != null) {
                scope.item(R.string.page_more_collect, R.drawable.playlist_add_24) { collect.gate.run { picking = true } }
            }
        }
        // 这一页自己的操作另成一组，与网页、分享之间是 M3 菜单的 gap（与楼层长按菜单同一个分组菜单）
        val own: (@Composable ColumnScope.() -> Unit)? = extra?.let { items -> { scope.items() } }
        AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, groups = listOfNotNull(fixed, own))
    }
    if (picking && collect != null && picker != null) picker(collect.target) { picking = false }
}

/** [PageMoreMenu] 里的项（[AppMenuItem]：文字 + 前置图标），点了先收起菜单再执行 [item] 的动作。 */
class PageMoreMenuScope internal constructor(private val dismiss: () -> Unit) {
    @Composable
    fun item(@StringRes text: Int, @DrawableRes icon: Int, onClick: () -> Unit) {
        AppMenuItem(stringResource(text), onDismiss = dismiss, icon = icon, onClick = onClick)
    }
}
