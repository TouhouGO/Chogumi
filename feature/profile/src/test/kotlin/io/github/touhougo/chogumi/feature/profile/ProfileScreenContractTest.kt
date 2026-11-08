package io.github.touhougo.chogumi.feature.profile

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 契约测试：保证个人页（[ProfileScreen]）的动态卡片遵守版式与权限规范：
 * 1. 必须使用连续进度合并 [mergeConsecutiveTimelineItems]；
 * 2. 动态行之间不渲染分割线（分段列表靠项间距分隔）；
 * 3. 日期头是文字加细线，日期头与每一行各占一个通栏。
 *
 * 能不能删只看 isOwn，由 [ProfileTimelineDeleteTest] 按行为测。
 */
class ProfileScreenContractTest {

    private val profileScreen = RepoFiles.file("feature/profile/src/main/kotlin/io/github/touhougo/chogumi/feature/profile/ProfileScreen.kt")
    private val timelineTab = RepoFiles.file("feature/profile/src/main/kotlin/io/github/touhougo/chogumi/feature/profile/ProfileTimelineTab.kt")

    @Test
    fun `动态 tab 合并连续进度_行间不画分割线_按日分组`() {
        val file = timelineTab
        assertTrue(file.exists(), "ProfileTimelineTab.kt 必须存在于预期位置")
        val content = file.readText()

        // 验证合并了连续单集动作
        assertTrue(
            content.contains("mergeConsecutiveTimelineItems"),
            "ProfileTimelineTab.kt 必须调用 mergeConsecutiveTimelineItems 进行连续单集本地合并",
        )
        assertTrue(
            profileScreen.readText().contains("remember(timeline.items, tab)"),
            "动态的合并与分组按动态列表 remember，不在 LazyGrid 内容构建器里逐次重算",
        )

        // 验证动态行之间不渲染分割线（分段列表靠项间距分隔）。
        // 只约束动态时间线卡片流（timelineItems 函数体），个人页其他处可以用 HorizontalDivider。
        val activitySection = content.substringAfter("fun LazyGridScope.timelineItems(").substringBefore("\n}\n")
        assertFalse(
            activitySection.contains("HorizontalDivider"),
            "ProfileTimelineTab.kt 动态时间线卡片之间不得渲染分割线",
        )

        assertTrue(
            content.substringAfter("fun timelineDaysOf(").substringBefore("\n\n").contains("groupTimelineByDay"),
            "个人页动态必须按自然日分组展示",
        )
        assertFalse(
            activitySection.contains("mergeConsecutiveTimelineItems") || activitySection.contains("groupTimelineByDay"),
            "合并与分组不得写在 LazyGrid 内容构建器里（构建器随每次重组重跑）",
        )
        assertTrue(
            activitySection.contains("ProfileTimelineBottomSpacer"),
            "个人页动态必须为底栏保留导航 inset，避免末项被遮挡",
        )
    }

    /**
     * 日期头照原型 `.daylbl`：onSurfaceVariant 文字 + outlineVariant 细线，不用抢眼的色块；
     * 日期头与每一行各占一个通栏 item：一天几十行放进同一个 item 会在一帧里全部组合，滑到那天就卡。
     */
    @Test
    fun `时间线日期头是文字加细线_日期头与每一行各占一个通栏`() {
        val content = timelineTab.readText()

        val activitySection = content.substringAfter("fun LazyGridScope.timelineItems(").substringBefore("\n}\n")
        assertTrue(activitySection.contains("timelineDay("), "骨架与真实动态都经 timelineDay 排成日期头 + 行")
        assertFalse(activitySection.contains("forEachIndexed"), "一天的行不得在同一个 item 里逐行画出")

        val daySection = content.substringAfter("private fun LazyGridScope.timelineDay(").substringBefore("\n}\n")
        assertTrue(
            daySection.contains("contentType = \"profile-timeline-day\"") && daySection.contains("items(count = count"),
            "日期头一个通栏 item，后面每行一个 item",
        )

        val headerSection = content.substringAfter("private fun ProfileTimelineDayHeader(").substringBefore("private fun ProfileTimelineBottomSpacer")
        assertFalse(
            headerSection.contains("Container"),
            "日期头不得用色块容器，原型是文字 + 细线",
        )
        assertTrue(
            headerSection.contains("colorScheme.onSurfaceVariant") && headerSection.contains("colorScheme.outlineVariant"),
            "日期头必须是 onSurfaceVariant 文字 + outlineVariant 细线（原型 `.daylbl`）",
        )
    }

    /**
     * 资料头与「概览」加载时版面不动：每一块恒画（骨架或内容），资料到了只换内容。
     * 不许用展开过渡把后到的区块插进来——那会把下面的分段与生涯卡往下推。几何见 [ProfileHeaderFixedHeightTest]。
     */
    @Test
    fun `资料头与概览不用展开过渡插入后到的区块`() {
        listOf(
            profileScreen,
            RepoFiles.file("feature/profile/src/main/kotlin/io/github/touhougo/chogumi/feature/profile/ProfileHeader.kt"),
            RepoFiles.file("feature/profile/src/main/kotlin/io/github/touhougo/chogumi/feature/profile/ProfileOverviewTab.kt"),
        ).forEach { file ->
            val text = file.readText()
            assertFalse(text.contains("AnimatedVisibility") || text.contains("ExpandReveal"), "${file.name} 不得用展开过渡插入区块")
        }
        assertFalse(
            profileScreen.readText().contains("numberLineItems.isNotEmpty()"),
            "数字行恒画（资料未到是骨架、全为 0 留一行），不按有没有项决定出不出现",
        )
    }

    /**
     * 个人页的间距都写成各块自己的内边距：不用负外边距把内容推出自己的测量范围——
     * 展开过渡、裁切层会把推出去的那几 dp 裁掉（docs/code-standards.md「只在自己量出的边界里画」）。
     */
    @Test
    fun `个人页不用负外边距把内容推出测量范围`() {
        listOf(
            profileScreen,
            timelineTab,
            RepoFiles.file("feature/profile/src/main/kotlin/io/github/touhougo/chogumi/feature/profile/ProfileHeader.kt"),
        ).forEach { file ->
            val text = file.readText()
            assertFalse(text.contains("place(-"), "${file.name} 不得把内容摆到自己的测量范围之外")
        }
    }
}
