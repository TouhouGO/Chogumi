package io.github.touhougo.chogumi.core.ui.composables

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 加载契约：内容还没到时画骨架（列表页 [ListPageSkeleton]、帖子页 `ThreadPageSkeleton`、详情页各自的整页骨架、
 * 区块里与真实行同一组件的占位行），再经 [SkeletonCrossfade] 或区块自身的三态换成内容；不转圈。
 *
 * 加载圈（[ContentLoadingIndicator]，或直接调官方 `LoadingIndicator`）只许出现在 [ALLOWED] 列的函数里：
 * 按钮里的进行中、续取页脚，以及还没有任何页面可画骨架的启动提示。页面、面板、区块的首屏一律不许。
 */
class PageLoadingSkeletonContractTest {

    @Test
    fun `加载圈只出现在白名单函数里`() {
        val found = mutableSetOf<Pair<String, String>>()
        val offenders = RepoFiles.mainKotlinFiles("app", "core", "feature").flatMap { file ->
            val lines = file.readLines()
            lines.withIndex()
                .filter { (_, line) -> !isComment(line) && SPINNER_CALL.containsMatchIn(line) && !SPINNER_DECLARATION.containsMatchIn(line) }
                .mapNotNull { (index, line) ->
                    val key = file.name to enclosingFunction(lines, index)
                    found += key
                    if (key in ALLOWED) null else "${RepoFiles.relativePath(file)}:${index + 1}: ${line.trim()}"
                }
        }
        assertEquals(emptyList(), offenders, "内容未到时画与真实内容同一组件的骨架，不转圈；加载圈只留给 ALLOWED 里的场合")
        assertEquals(emptyList(), (ALLOWED.keys - found).map { "${it.first} ${it.second}" }, "白名单里已经没有加载圈的条目要删掉")
    }

    private fun isComment(line: String): Boolean = line.trimStart().startsWith("*") || line.trimStart().startsWith("//")

    /** 往上找最近的函数声明；找不到时是空串（顶层属性等），不会落进白名单。 */
    private fun enclosingFunction(lines: List<String>, index: Int): String =
        (index downTo 0).firstNotNullOfOrNull { FUN_DECL.find(lines[it])?.groupValues?.get(1) }.orEmpty()

    private companion object {
        /** 加载圈调用：项目的 [ContentLoadingIndicator] 与官方 `LoadingIndicator`（不含下拉刷新自带的 `PullToRefreshDefaults.LoadingIndicator`）。 */
        val SPINNER_CALL = Regex("""(?<![\w.])(?:Content)?LoadingIndicator\(""")
        val SPINNER_DECLARATION = Regex("""\bfun\s+ContentLoadingIndicator\(""")
        val FUN_DECL = Regex("""^\s*(?:(?:private|internal|public|override)\s+)*fun\s+(?:<[^>]*>\s+)?(?:[\w.]+\.)?(\w+)\s*\(""")

        /** 允许转圈的函数（文件名, 函数名）→ 原因。 */
        val ALLOWED = mapOf(
            ("ContentLoadingIndicator.kt" to "ContentLoadingIndicator") to "加载圈本身",
            ("LoadMoreFooter.kt" to "LoadMoreFooter") to "续取页脚：新内容将出现的位置",
            ("AppShell.kt" to "SlowStartNotice") to "慢启动提示：还没有任何页面，没有可画的骨架",
            ("BusyButton.kt" to "BusyButton") to "确认按钮（面板的保存、发送、提交举报，登录页的登录）里的进行中",
            ("CollectCountButton.kt" to "CollectCountButton") to "按钮里的进行中",
            ("SubjectDetailsScreen.kt" to "CollectionFabLabel") to "收藏按钮里的进行中（M3 FAB 与玻璃版共用的那一行）",
        )
    }
}
