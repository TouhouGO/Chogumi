package io.github.touhougo.chogumi.core.ui.composables

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/** 真格子、演示与进度尺不各自解释同一种视觉状态。 */
class TileStyleSourceTest {
    @Test
    fun `演示不另写格子种类映射`() {
        val path = "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/tour/EpisodeDemo.kt"
        val copies = RepoFiles.codeLines(RepoFiles.file(path)).filter { Regex("""->\s*EpisodeCellKind\.""").containsMatchIn(it) }
        assertTrue("演示的格子种类应由 EpisodeTracking 推导：$copies", copies.isEmpty())
    }

    @Test
    fun `格子的形状强调与描边判定只有一份`() {
        val paths = listOf(
            "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/composables/EpisodeTile.kt",
            "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/tour/EpisodeDemo.kt",
            "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/composables/TileRuler.kt",
        )
        val decisions = Regex("""\b(?:kind|shown)\s*==\s*EpisodeCellKind\.NEXT\b|emphasized\s*=.*EpisodeCellKind""")
        val copies = paths.flatMap { path ->
            RepoFiles.codeLines(RepoFiles.file(path)).withIndex()
                .filter { decisions.containsMatchIn(it.value) }
                .map { "$path:${it.index + 1}: ${it.value.trim()}" }
        }
        assertTrue("格子视觉规则应只在 TileStyle 定义：$copies", copies.isEmpty())
    }
}
