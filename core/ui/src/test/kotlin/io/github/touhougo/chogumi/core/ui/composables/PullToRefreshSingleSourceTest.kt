package io.github.touhougo.chogumi.core.ui.composables

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 下拉刷新唯一真源契约。
 *
 * 钉住两件事：
 * 1. 全仓所有 PullToRefresh 使用点都走 [ChogumiPullToRefreshBox]（core:ui 唯一真源），
 *    严禁任何 feature 直接用裸 `PullToRefreshBox`——否则指示器口径分裂；
 * 2. 刷新态用官方 **Contained** 变体（spec feedback.md:187「Use the container with
 *    pull-to-refresh behavior」），严禁退回裸 LoadingIndicator 或自绘 spinner。
 */
class PullToRefreshSingleSourceTest {

    /** core:ui 内的合法实现文件（唯一真源本体）。 */
    private val sourceOfTruth: File
        get() = RepoFiles.file("core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/composables/ChogumiPullToRefresh.kt")

    @Test
    fun `全仓使用点一律走ChogumiPullToRefreshBox唯一真源`() {
        // 除 core:ui 实现文件外，feature 与 app 里出现裸 PullToRefreshBox 的文件应为空集
        val offenders = RepoFiles.mainKotlinFiles("feature", "app")
            .filter { it.readText().contains(Regex("""(^|[^A-Za-z])PullToRefreshBox\(""")) }
            .map(RepoFiles::relativePath)
        // core:ui 的 PageScaffold 同模块消费也算使用点，但走同包组件名不算裸用
        assertTrue(
            offenders.isEmpty(),
            "以下文件绕过 ChogumiPullToRefreshBox 直接使用裸 PullToRefreshBox（破坏唯一真源）: $offenders",
        )
    }

    @Test
    fun `拉动与刷新态统一使用官方LoadingIndicator`() {
        assertTrue(sourceOfTruth.exists(), "唯一真源 ChogumiPullToRefresh.kt 必须存在")
        val content = sourceOfTruth.readText()

        assertTrue(
            content.contains("PullToRefreshDefaults.LoadingIndicator("),
            "拉动与刷新态必须共用官方带容器的形变指示器",
        )
        assertFalse(
            content.contains("CircularProgressIndicator"),
            "严禁 CircularProgressIndicator（那是进度指示器，不是 M3 EX 形变加载指示器）",
        )
    }

    @Test
    fun `八个已知使用点全部接线到唯一真源`() {
        // 使用点清单（首页经 PageScaffold 的 onRefresh 接线，PageScaffold 自己包着 ChogumiPullToRefreshBox）：
        val expected = listOf(
            "feature/calendar/src/main/kotlin/io/github/touhougo/chogumi/feature/calendar/CalendarScreen.kt",
            "feature/monodetails/src/main/kotlin/io/github/touhougo/chogumi/feature/monodetails/MonoDetailsScreen.kt",
            "feature/progress/src/main/kotlin/io/github/touhougo/chogumi/feature/progress/ProgressScreen.kt",
            "feature/explore/src/main/kotlin/io/github/touhougo/chogumi/feature/explore/ExploreScreen.kt",
            "feature/profile/src/main/kotlin/io/github/touhougo/chogumi/feature/profile/ProfileScreen.kt",
            "feature/subjectdetails/src/main/kotlin/io/github/touhougo/chogumi/feature/subjectdetails/SubjectDetailsScreen.kt",
            "feature/timeline/src/main/kotlin/io/github/touhougo/chogumi/feature/timeline/TimelineScreen.kt",
            "feature/community/src/main/kotlin/io/github/touhougo/chogumi/feature/community/CommunityScreen.kt",
        )
        val missing = expected.filter {
            !RepoFiles.read(it).contains("ChogumiPullToRefreshBox(")
        }
        assertTrue(missing.isEmpty(), "以下使用点未接线到 ChogumiPullToRefreshBox: $missing")
    }
}
