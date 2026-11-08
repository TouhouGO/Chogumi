package io.github.touhougo.chogumi.core.ui.composables

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 可选中 chip 唯一真源契约：选中的勾、字重与切换动效只在 [SelectableChip]。
 * 页面直接用裸 `FilterChip` 时切换没有动画，勾选逻辑各抄一份。唯一例外是主题色那排：前置位是色块，不是图标。
 */
class SelectableChipSingleSourceTest {

    private val allowed = setOf("SelectableChip.kt", "ThemeColorSettings.kt")

    @Test
    fun `除 SelectableChip 与主题色外，主源码不得直接用 FilterChip`() {
        val offenders = RepoFiles.mainKotlinFiles("app", "core", "feature")
            .filter { it.name !in allowed && Regex("""\bFilterChip\(""").containsMatchIn(it.readText()) }
            .map(RepoFiles::relativePath)
        assertEquals(emptyList(), offenders, "可选中 chip 一律走 SelectableChip")
    }
}
