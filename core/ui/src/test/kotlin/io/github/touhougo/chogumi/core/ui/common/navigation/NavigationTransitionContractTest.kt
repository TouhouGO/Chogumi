package io.github.touhougo.chogumi.core.ui.common.navigation

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 页面转场的**可执行守卫**：`NavDisplay` 的三个 transition spec 必须统一走
 * [chogumiTransition]（唯一定义处），不得再有第二处 `ContentTransform(...)` 或
 * 手写进出场动画。
 *
 * 页面进出、返回、切 tab、封面到详情的转场；本测试锁住「结构上只能有一处」。这一处也不自己写规格（时长与缓动只来自
 * `PageMotion`），由门禁 `tools/guards/check_motion.py` 守（Motion.kt 之外的主源码都不许写 `tween` / `spring` / `keyframes`）。
 */
class NavigationTransitionContractTest {

    private val allowedFile =
        "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/common/navigation/ChogumiTransitions.kt"

    @Test
    fun `ContentTransform 只允许出现在转场唯一定义处`() {
        val offenders = RepoFiles.offenders("ContentTransform", allowedFile)
        assertTrue(
            "页面转场只允许在 $allowedFile 里定义；NavDisplay 的三个 spec 一律调用 chogumiTransition。\n" +
                "违规处：\n" + offenders.joinToString("\n"),
        ) { offenders.isEmpty() }
    }
}
