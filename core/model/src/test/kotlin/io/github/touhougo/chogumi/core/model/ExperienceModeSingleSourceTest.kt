package io.github.touhougo.chogumi.core.model

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
/**
 * 「功能可见性只在一个纯函数里判断」的源码守卫。
 *
 * 红线：任何页面都不许自己写 `if (专注)`。判定的唯一落点是 [appFeatures]，
 * 其余代码只允许读它派生出的 [AppFeatures]（`features.community` / `features.publicRatings`）。
 *
 * 这条守卫把红线变成可执行的断言：一旦有人图省事在界面里直接比对模式枚举，
 * 本测试立刻变红并指出文件。做法与 `TopLevelSourceContractTest` 的源码扫描一致。
 *
 * 允许的例外只有**模式本身的定义与派生处**（core:model 的 ExperienceMode.kt）与
 * **设置页的模式开关**——后者要显示"当前是不是专注"，那是模式值本身的用途，
 * 不是功能可见性判断。二者在下方白名单里显式列出，不靠"包含 features 就放行"这类模糊规则。
 */
class ExperienceModeSingleSourceTest {

    @Test
    fun `生产代码里不得出现模式枚举的直接比对`() {
        val offenders = productionFiles()
            .filter { file ->
                val text = file.readText()
                text.contains(FOCUS_LITERAL) || text.contains("== ExperienceMode.") || text.contains("!= ExperienceMode.")
            }
            .map(RepoFiles::relativePath)
            .filterNot { it in ALLOWED_MODE }

        assertTrue(
            "功能可见性只允许由 appFeatures 判定，以下文件直接比对了模式：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `页面与业务代码不得直接读取showPublicRatings偏好`() {
        val offenders = productionFiles()
            .filter { file -> file.readText().contains(SHOW_RATINGS_LITERAL) }
            .map(RepoFiles::relativePath)
            .filterNot { it in ALLOWED_SHOW_RATINGS }

        assertTrue(
            "公共评分可见性只允许由 AppFeatures.publicRatings 读取，以下文件直接引用了 $SHOW_RATINGS_LITERAL：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `白名单文件确实存在且确实引用了受保护的标识`() {
        ALLOWED_MODE.forEach { relative ->
            val file = RepoFiles.file(relative)
            assertTrue("模式白名单文件不存在：$relative", file.exists())
            val text = file.readText()
            assertTrue("模式白名单文件已不再引用模式字面量：$relative", text.contains(FOCUS_LITERAL))
        }
        ALLOWED_SHOW_RATINGS.forEach { relative ->
            val file = RepoFiles.file(relative)
            assertTrue("showPublicRatings 白名单文件不存在：$relative", file.exists())
            assertTrue("showPublicRatings 白名单文件已不再引用 $SHOW_RATINGS_LITERAL：$relative", file.readText().contains(SHOW_RATINGS_LITERAL))
        }
    }

    // 只扫生产代码：测试可以（也应该）显式构造两种模式来验证隐藏行为
    private fun productionFiles(): List<File> = RepoFiles.mainKotlinFiles("core", "feature", "app")

    private companion object {
        const val FOCUS_LITERAL = "ExperienceMode.FOCUS"
        const val SHOW_RATINGS_LITERAL = "showPublicRatings"

        val ALLOWED_MODE = setOf(
            "core/model/src/main/kotlin/io/github/touhougo/chogumi/core/model/ExperienceMode.kt",
            "feature/settings/src/main/kotlin/io/github/touhougo/chogumi/feature/settings/PreferenceSettings.kt",
        )

        val ALLOWED_SHOW_RATINGS = setOf(
            "core/data/src/main/kotlin/io/github/touhougo/chogumi/core/data/repository/PreferenceKeys.kt",
            "core/data/src/main/kotlin/io/github/touhougo/chogumi/core/data/repository/PreferencesRepository.kt",
            "core/model/src/main/kotlin/io/github/touhougo/chogumi/core/model/ExperienceMode.kt",
            "feature/settings/src/main/kotlin/io/github/touhougo/chogumi/feature/settings/PreferenceSettings.kt",
            "feature/settings/src/main/kotlin/io/github/touhougo/chogumi/feature/settings/PreferenceSettingsViewModel.kt",
        )
    }
}

