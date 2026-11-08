package io.github.touhougo.chogumi

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单选分段（连通按钮组）的**可执行守卫**：首 / 中 / 尾连通形状只许出现在 core:ui 的
 * `ConnectedChoiceGroup.kt`，也就是只能经 `ConnectedButtonGroup` / `ConnectedChoiceGroup` 画。
 *
 * 自己拼一套 ToggleButton 就跟不上全仓的按压弹性（搜索排序这样出过错），
 * 按钮组只许一种写法。手拼必然要写连通形状，本测试即红。
 */
class ConnectedButtonGroupContractTest {

    private val allowedFile =
        "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/composables/ConnectedChoiceGroup.kt"

    @Test
    fun `连通按钮形状只允许出现在 core ui 的按钮组唯一实现里`() {
        val offenders = listOf(
            "connectedLeadingButtonShapes",
            "connectedMiddleButtonShapes",
            "connectedTrailingButtonShapes",
        ).flatMap { RepoFiles.offenders(it, allowedFile) }

        assertTrue(
            "单选分段一律走 core:ui 的 ConnectedButtonGroup / ConnectedChoiceGroup（形状、配色、按压弹性只此一处），" +
                "不要自己拼 ToggleButton。违规处：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
