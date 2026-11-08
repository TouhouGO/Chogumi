package io.github.touhougo.chogumi.core.ui.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.touhougo.chogumi.core.ui.common.navigation.PageSheetRegistry
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import io.github.touhougo.chogumi.core.ui.common.navigation.rememberPageSheetNavEntryDecorator

/**
 * 把 [content] 放进一个导航项里画，带页面面板层：页面面板（`PageModalSheet`）只能用在导航项里。
 * 装饰器的顺序与 App 一致（可保存状态在前，面板层在后）。[registry] 给要看「开没开面板」的测试。
 */
@Composable
fun PageSheetTestHost(registry: PageSheetRegistry = remember { PageSheetRegistry() }, content: @Composable () -> Unit) {
    val backStack = remember { mutableStateListOf<NavKey>(Route.Home) }
    NavDisplay(
        backStack = backStack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberPageSheetNavEntryDecorator(registry),
        ),
        entryProvider = { key -> NavEntry(key) { content() } },
    )
}
