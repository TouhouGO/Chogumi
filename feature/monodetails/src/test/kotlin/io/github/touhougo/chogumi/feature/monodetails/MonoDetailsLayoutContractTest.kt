package io.github.touhougo.chogumi.feature.monodetails

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 契约测试：角色 / 人物页的排版与取数约定：
 * 1. 立绘走 PortraitImage（顶对齐裁切、关掉 crossfade，否则对齐失效、头被切掉）；
 * 2. 详情页露哪几部作品、什么顺序只由 monoWorksPreview 决定，「全部」子页经 sortMonoWorks 排序；
 * 3. 子页的筛选项与计数来自 monoWorkFilterOptions，不在页面里另写职位 / 类型的提取；
 * 4. 吐槽全量逐条进列表，不截断。
 */
class MonoDetailsLayoutContractTest {

    private val dir = "feature/monodetails/src/main/kotlin/io/github/touhougo/chogumi/feature/monodetails"

    private fun read(name: String): String = RepoFiles.file("$dir/$name").also { assertTrue(it.exists(), "$name 必须存在") }.readText()

    @Test
    fun `立绘走 PortraitImage`() {
        val header = read("ui/MonoHeaderSection.kt")
        // 顶对齐与关掉 crossfade 的实现只在 core:ui 的 PortraitImage（PortraitImageContractTest 钉住）
        assertTrue(header.contains("PortraitImage(url = url"), "立绘必须走 PortraitImage（顶对齐裁切、关掉 crossfade）")
    }

    @Test
    fun `详情页作品横排只由 monoWorksPreview 决定，子页排序与筛选走同一组函数`() {
        val screen = read("MonoDetailsScreen.kt")
        assertTrue(
            screen.contains("monoWorksPreview(uiState.monoType, works, features.publicRatings)"),
            "详情页海报横排露哪几部、什么顺序必须来自 monoWorksPreview",
        )
        val list = read("MonoListScreens.kt")
        assertTrue(list.contains("sortMonoWorks("), "子页必须经 sortMonoWorks 排序")
        assertTrue(list.contains("monoWorkFilterOptions(monoType, works, state.filter)"), "子页筛选项必须来自 monoWorkFilterOptions")
    }

    @Test
    fun `吐槽全量逐条平铺不截断`() {
        val screen = read("MonoDetailsScreen.kt")
        assertTrue(
            Regex("""floorCommentsBody\(\s*comments = uiState\.comments,""").containsMatchIn(screen),
            "吐槽必须整份交给评论区段落逐条进 LazyColumn，不截断预览",
        )
        assertFalse(Regex("""comments[.\w]*\.take\(""").containsMatchIn(screen), "吐槽不截断成前几条")
    }
}
