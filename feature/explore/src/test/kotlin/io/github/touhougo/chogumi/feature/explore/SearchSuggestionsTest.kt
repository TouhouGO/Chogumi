package io.github.touhougo.chogumi.feature.explore

import io.github.touhougo.chogumi.core.data.db.tag.TagCatalogDao
import io.github.touhougo.chogumi.core.data.db.tag.TagCatalogEntity
import io.github.touhougo.chogumi.core.data.repository.FakeTagCatalogDao
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testNsfwFilter
import io.github.touhougo.chogumi.core.data.repository.testTagCatalogRepository
import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagCatalogEntry
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * 面板搜索框的建议合成：本地词典在前、目录结果在后，落到维度上的目录词不重复列。
 *
 * 「去掉落到维度上的目录词」是这条规则里最容易漏的一环——漏了就会同一条建议出现两次
 * （一次以维度取值的身份、一次以目录标签的身份），点哪一个的去处还不一样。
 */
class SearchSuggestionsTest {
    @get:Rule
    val resources = TestResources()


    private fun catalog(vararg pairs: Pair<String, Int>) =
        pairs.mapIndexed { i, (name, count) -> TagCatalogEntry(name, count, i + 1) }

    @Test
    fun `输入为空不列建议`() {
        assertNull(filterSuggestions(SubjectType.ANIME, ""))
        assertNull(filterSuggestions(SubjectType.ANIME, "   "))
        assertNull(filterSuggestions(SubjectType.ANIME, "  ", catalog("奇幻" to 10)))
    }

    @Test
    fun `本地维度取值在前_目录结果在后`() {
        val rows = filterSuggestions(SubjectType.ANIME, "奇幻", catalog("奇幻世界" to 12000))!!
        assertEquals("奇幻" to FilterSuggestion.Dimension::class, rows.first().label to rows.first()::class)
        val catalogRow = rows.filterIsInstance<FilterSuggestion.Catalog>().single()
        assertEquals("奇幻世界", catalogRow.label)
        assertEquals(12000, catalogRow.count)
    }

    @Test
    fun `目录里会落到维度上的词不重复列出`() {
        // 「TV」是动画 CATEGORY 的取值：本地已作为维度建议收过，目录里再来一条就是重复。
        val rows = filterSuggestions(SubjectType.ANIME, "TV", catalog("TV" to 99999))!!
        assertEquals(1, rows.count { it.label == "TV" })
        assertTrue(rows.single { it.label == "TV" } is FilterSuggestion.Dimension)
        assertTrue(rows.none { it is FilterSuggestion.Catalog })
    }

    /**
     * 与上一条的区别：目录词是 metaTag（「香港」），本地维度取值是网页原文（「中国香港」）。
     * 两个 [label] 不同，所以「同名去重」挡不住它，只有「落到维度上就去掉」这条规则能挡住。
     * 少了这条，同一次输入会给出两条都落到地区维度的建议。
     */
    @Test
    fun `目录里的metaTag别名也会落到维度上_同样不重复列出`() {
        val rows = filterSuggestions(SubjectType.ANIME, "香港", catalog("香港" to 88888))!!
        assertTrue(
            rows.none { it is FilterSuggestion.Catalog },
            "「香港」经 resolveTagInput 落到地区维度，不该再作为目录建议出现",
        )
        assertTrue(rows.any { it is FilterSuggestion.Dimension && it.label == "中国香港" })
    }

    @Test
    fun `目录里与本地同名的词只留先出现的那条`() {
        val rows = filterSuggestions(SubjectType.ANIME, "治愈", catalog("治愈" to 500))!!
        assertEquals(1, rows.count { it.label == "治愈" })
        assertTrue(rows.single { it.label == "治愈" } is FilterSuggestion.Word)
    }

    @Test
    fun `建议总数上限八条_末行自由词不计入`() {
        val many = catalog(*(1..20).map { "测试词$it" to (100 - it) }.toTypedArray())
        val rows = filterSuggestions(SubjectType.ANIME, "测试词", many)!!
        // 8 条建议 + 1 条自由词
        assertEquals(SUGGESTION_LIMIT + 1, rows.size)
        assertTrue(rows.last() is FilterSuggestion.Word)
        assertEquals("测试词", rows.last().label)
    }

    @Test
    fun `输入正好等于某条建议时不出自由词行`() {
        val rows = filterSuggestions(SubjectType.ANIME, "TV", catalog())!!
        assertTrue(rows.none { it is FilterSuggestion.Word })
    }

    @Test
    fun `没有目录结果时只给本地建议_不报错`() {
        val rows = filterSuggestions(SubjectType.ANIME, "奇幻")!!
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.none { it is FilterSuggestion.Catalog })
    }

    @Test
    fun `全部类型没有维度词典_目录词照样列出`() {
        val rows = filterSuggestions(null, "京阿尼", catalog("京阿尼" to 215952))!!
        val catalogRow = rows.filterIsInstance<FilterSuggestion.Catalog>().single()
        assertEquals("京阿尼", catalogRow.label)
    }

    /** 保证测试用的维度取值确实是词典里有的，别把断言建在错误前提上。 */
    @Test
    fun `测试前提_TV与奇幻确实在动画词典里`() {
        assertTrue(filterSuggestions(SubjectType.ANIME, "TV", emptyList())!!.any { it is FilterSuggestion.Dimension })
        assertTrue(
            filterSuggestions(SubjectType.ANIME, "TV", emptyList())!!
                .filterIsInstance<FilterSuggestion.Dimension>()
                .any { it.dimension == SearchDimension.CATEGORY },
        )
    }

    @Test
    fun `目录表读不出时这一次没有建议_流不结束_下一次输入照常查到`() = runBlocking {
        val real = FakeTagCatalogDao()
        real.upsertAll(listOf(TagCatalogEntity(type = SubjectType.ANIME.value, name = "奇幻世界", count = 12000, rank = 1)))
        val failReads = AtomicBoolean(true)
        val dao = object : TagCatalogDao by real {
            override suspend fun search(type: Int, keyword: String, limit: Int): List<TagCatalogEntity> {
                if (failReads.get()) throw IOException("tag_catalog unreadable")
                return real.search(type, keyword, limit)
            }
        }
        val client = resources.apiClient(MockEngine { respond("{}", HttpStatusCode.NotFound) })
        val catalog = testTagCatalogRepository(NextApi(client), dao, testNsfwFilter(resources))
        val inputs = MutableStateFlow<Pair<String, SubjectType?>>("奇" to SubjectType.ANIME)
        val results = mutableListOf<Pair<String, List<TagCatalogEntry>>>()
        val collector = launch { catalog.suggestionsFor(inputs).collect { results += it } }

        awaitUntil("读不出时给空建议") { results.size == 1 }
        assertEquals("奇" to emptyList(), results[0])

        failReads.set(false)
        inputs.value = "奇幻" to SubjectType.ANIME
        awaitUntil("下一次输入照常查到目录") { results.size == 2 }
        assertEquals(listOf("奇幻世界"), results[1].second.map { it.name })
        collector.cancel()
    }
}
