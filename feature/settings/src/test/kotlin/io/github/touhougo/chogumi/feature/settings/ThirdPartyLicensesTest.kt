package io.github.touhougo.chogumi.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 关于页：随包的许可原文齐全、与仓库里的 LICENSE 一致，第三方清单与 docs 说明逐项对得上。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class ThirdPartyLicensesTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun 随包的GPL原文与仓库根的LICENSE一致() {
        assertEquals(unwrapParagraphs(RepoFiles.read("LICENSE")), licenseText(context, LicenseKind.GPL_3))
    }

    @Test
    fun 每种带原文的许可都随包且不为空() {
        LicenseKind.entries.filter { it.asset != null }.forEach { kind ->
            assertTrue("${kind.label} 的原文太短", licenseText(context, kind).length > 500)
        }
    }

    @Test
    fun 段内的单个换行并成空格_空行保留为分段() {
        assertEquals("a b\n\nc", unwrapParagraphs("  a\nb\n\n\n  c\n"))
    }

    @Test
    fun 随包原文显示前没有段内硬换行() {
        LicenseKind.entries.filter { it.asset != null }.forEach { kind ->
            val text = licenseText(context, kind)
            assertTrue("${kind.label} 还有段内换行", text.split("\n\n").none { "\n" in it })
        }
    }

    @Test
    fun 随包清单与运行时数据清单与说明文档的表格双向一致() {
        val notices = RepoFiles.read("docs/third-party-notices.md")
        fun tableRows(heading: String) = notices.substringAfter("## $heading\n", "")
            .substringBefore("\n## ")
            .lines().filter { it.startsWith("| ") }.drop(1).map { row ->
                row.removePrefix("| ").removeSuffix(" |").split(" | ")
            }
        fun catalogRows(catalog: List<ThirdPartyNotice>) = catalog.map { notice ->
            listOf(notice.id, context.getString(notice.nameRes), notice.license.label(context), notice.url)
        }
        assertEquals("文档与 About 的随包第三方清单必须逐行一致", catalogRows(BundledThirdPartyNotices), tableRows("随 APK 分发的第三方清单"))
        assertEquals("文档与 About 的运行时数据清单必须逐行一致", catalogRows(RuntimeDataNotices), tableRows("运行时取用的第三方数据"))
        val ids = (BundledThirdPartyNotices + RuntimeDataNotices).map { it.id }
        assertEquals("清单标识必须唯一", ids.size, ids.toSet().size)
        assertTrue("API 快照只在仓库，不属于随包清单", "## 仅存于源码仓库的 Bangumi API 契约" in notices)
    }

    @Test
    fun 随包Bangumi表情在关于页有来源与版权条目() {
        val bundledSmileys = RepoFiles.sourceFiles("gif")
            .filter { it.parentFile?.name == "drawable-nodpi" && it.name.startsWith("smiley_") }
        assertEquals("随包 Bangumi 表情数量变化时，必须同步核对 About 与第三方说明", 12, bundledSmileys.size)

        val entry = BundledThirdPartyNotices.single { it.id == "bangumi-smileys" }
        assertEquals("Bangumi 表情", context.getString(entry.nameRes))
        assertEquals("版权归 Bangumi 番组计划，未声明开源许可", entry.license.label(context))
        assertEquals("https://lain.bgm.tv/img/smiles/", entry.url)
        assertTrue("表情版权不能标成开源许可", entry.license is ThirdPartyLicense.Notice)
    }

    @Test
    fun 关于页显示Bangumi表情版权并打开素材来源_普通库链接也可打开() {
        val opened = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                PageSheetTestHost { AboutScreen(onBack = {}) }
            }
        }

        compose.onNodeWithTag(AboutListTag).performScrollToNode(hasText("Bangumi 表情"))
        compose.onNodeWithText("版权归 Bangumi 番组计划，未声明开源许可").assertIsDisplayed()
        compose.onNodeWithText("Bangumi 表情").assertHasClickAction().performClick()
        compose.onNodeWithTag(AboutListTag).performScrollToNode(hasText("Coil"))
        compose.onNodeWithText("Coil").assertHasClickAction().performClick()
        assertEquals(listOf("https://lain.bgm.tv/img/smiles/", "https://coil-kt.github.io/coil/"), opened)
    }

    @Test
    fun 关于页的运行时数据条目显示许可并打开来源() {
        val opened = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                PageSheetTestHost { AboutScreen(onBack = {}) }
            }
        }

        compose.onNodeWithTag(AboutListTag).performScrollToNode(hasText("bangumi-data（放送时刻）"))
        compose.onNodeWithText("CC-BY-4.0").assertIsDisplayed()
        compose.onNodeWithText("bangumi-data（放送时刻）").assertHasClickAction().performClick()
        assertEquals(listOf("https://github.com/bangumi-data/bangumi-data"), opened)
    }

    @Test
    fun 关于页的隐私说明行打开仓库里的PRIVACY() {
        val opened = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                PageSheetTestHost { AboutScreen(onBack = {}) }
            }
        }

        compose.onNodeWithText("隐私说明").assertHasClickAction().performClick()
        assertEquals(listOf("https://github.com/TouhouGO/Chogumi/blob/main/PRIVACY.md"), opened)
        assertTrue("链接指向的文件要在仓库根", "bgm.tv" in RepoFiles.read("PRIVACY.md"))
    }

    @Test
    fun 点许可原文行打开面板并显示原文() {
        compose.setContent { PageSheetTestHost { AboutScreen(onBack = {}) } }
        compose.onNodeWithTag(AboutListTag).performScrollToNode(hasText("GPL-3.0"))
        compose.onNodeWithText("GPL-3.0").performClick()
        compose.onNodeWithText("GNU GENERAL PUBLIC LICENSE", substring = true).assertIsDisplayed()
    }
}
