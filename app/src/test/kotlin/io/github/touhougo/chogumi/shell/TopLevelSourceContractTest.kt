package io.github.touhougo.chogumi.shell

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopLevelSourceContractTest {
    /**
     * 顶级页不留一条占地方的顶栏，头部直接从页面顶上开始。唯一例外是 `HeaderTopAppBar`：它叠在头部之上、不占高度，
     * 头部铺到状态栏下的页面（「我的」）靠它在内容滚上来时盖住状态栏、钉住操作按钮。
     */
    @Test
    fun `三个顶级页不用占位顶栏或 PageScaffold，只许叠在头部上的 HeaderTopAppBar`() {
        val topLevelPages = listOf(
            "feature/progress/src/main/kotlin/io/github/touhougo/chogumi/feature/progress/ProgressScreen.kt",
            "feature/community/src/main/kotlin/io/github/touhougo/chogumi/feature/community/CommunityScreen.kt",
            "feature/profile/src/main/kotlin/io/github/touhougo/chogumi/feature/profile/ProfileScreen.kt",
            "feature/profile/src/main/kotlin/io/github/touhougo/chogumi/feature/profile/ProfileTopBar.kt",
        )
        topLevelPages.forEach { relativePath ->
            val content = RepoFiles.read(relativePath)
            val topBars = TopAppBarReference.findAll(content).map { it.value }.toSet()
            assertTrue("页面 $relativePath 不应引用 $topBars", topBars.isEmpty())
            assertFalse("页面 $relativePath 不应包含 PageScaffold", content.contains("PageScaffold"))
        }
    }

    @Test
    fun `AppShell 必须提供 LocalAppFeatures 且取自 ShellViewModel 的状态`() {
        // 红线守卫：LocalAppFeatures 的默认值是完整模式，若 AppShell 忘了 provide，
        // 专注模式在真机上会静默失效（功能照常显示），而所有单元测试仍然是绿的。
        // 所以在这里钉住"唯一提供点"确实存在，且值来自 state.features（单一真源）。
        val mainView = RepoFiles.read("app/src/main/kotlin/io/github/touhougo/chogumi/shell/AppShell.kt")
        assertTrue("AppShell 必须 provide LocalAppFeatures", mainView.contains("LocalAppFeatures provides"))
        assertTrue(
            "LocalAppFeatures 的取值必须来自 ShellViewModel 的状态（state.features）",
            mainView.contains("LocalAppFeatures provides state.features"),
        )
    }

    @Test
    fun `AppShell 必须提供 LocalFriends 且取自 ShellViewModel 的好友名单`() {
        // LocalFriends 默认谁也没关注：AppShell 忘了 provide 时「好友」小牌在真机上一个也不画，单元测试仍是绿的。
        val mainView = RepoFiles.read("app/src/main/kotlin/io/github/touhougo/chogumi/shell/AppShell.kt")
        assertTrue("AppShell 必须 provide LocalFriends", mainView.contains("LocalFriends provides friends"))
        assertTrue("LocalFriends 的取值必须来自 ShellViewModel.friends", mainView.contains("mainViewModel.friends.collectAsStateWithLifecycle()"))
    }
}

/** 除 `HeaderTopAppBar` 以外任何名字里带 TopAppBar 的标识符（M3 各种顶栏与其 Defaults）。 */
private val TopAppBarReference = Regex("""\b(?!HeaderTopAppBar\b)\w*TopAppBar\w*""")
