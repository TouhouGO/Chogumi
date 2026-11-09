package io.github.touhougo.chogumi

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底部提示条（Snackbar）规则的**可执行守卫**：`showSnackbar(` 只允许出现在
 * `core:ui` 的统一失败入口 `FailureSnackbar.kt` 里。
 *
 * 规则（产品决定，单一真源）：提示条**只在操作没办成时出现**——
 * 请求失败 / 超时 / 无响应、操作被拒（如需登录）。成功与纯告知（已保存 / 已固定 /
 * 已全部标为已读 / 建设中）一律不弹，靠界面自身的变化表达。
 *
 * 守卫的是「结构上只能这样写」：失败提示由 ViewModel 发到 `FailureNotices`、界面经
 * [io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect] 显示；任何模块绕过它直接调 `showSnackbar`，本测试即红。
 */
class SnackbarFailureOnlyContractTest {

    private val allowedFile =
        "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/composables/FailureSnackbar.kt"

    @Test
    fun `showSnackbar 只允许出现在 core ui 的统一失败提示入口文件里`() {
        val offenders = RepoFiles.offenders("showSnackbar", allowedFile)

        assertTrue(
            "底部提示条只允许经统一失败入口 FailureNoticeEffect 显示，原始 showSnackbar 只许出现在 $allowedFile。\n" +
                "成功 / 告知类操作不要弹提示条，改为让界面自身变化表达。违规调用点：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
