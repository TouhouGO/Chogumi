package io.github.touhougo.chogumi.core.ui.composables

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 人物图唯一画法契约：顶对齐裁切 + 按请求关掉 crossfade 只在 [PortraitImage] 一处；
 * 圆形头像、收藏 / 好友网格、角色人物页立绘都必须走它。漏关一处 crossfade，
 * 那处就走全局 crossfade、顶对齐失效，竖构图的角色只剩身子和腿。
 */
class PortraitImageContractTest {

    private fun read(path: String) = RepoFiles.file(path).also { assertTrue(it.exists(), "$path 必须存在") }.readText()

    @Test
    fun `PortraitImage 顶对齐裁切且关掉 crossfade`() {
        val source = read("core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/composables/PortraitImage.kt")
        assertTrue(source.contains("alignment = Alignment.TopCenter"), "必须顶对齐裁切")
        assertTrue(source.contains(".crossfade(0)"), "必须按请求关掉 crossfade，否则顶对齐失效")
    }

    @Test
    fun `头像、网格与立绘都走 PortraitImage`() {
        listOf(
            "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/composables/PersonCarousel.kt",
            "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/composables/PersonGridItem.kt",
            "feature/monodetails/src/main/kotlin/io/github/touhougo/chogumi/feature/monodetails/ui/MonoHeaderSection.kt",
        ).forEach { path ->
            val source = read(path)
            assertTrue(source.contains("PortraitImage("), "$path 必须用 PortraitImage 画人物图")
            assertTrue(!source.contains("AsyncImage("), "$path 不得再直接用 AsyncImage 画人物图")
        }
    }
}
