package io.github.touhougo.chogumi.core.model

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/** 维度词典与稳定夹具双向一致；网页标签和查询用 metaTag 可以不同。 */
class SearchDimensionFixtureTest {

    private data class Entry(val typeId: Int, val dimension: String, val label: String, val metaTag: String)

    /** 维度枚举 → 夹具里的网页维度标题。仅测试用的对照表，产品代码不消费。 */
    private val dimensionNames = mapOf(
        SearchDimension.CATEGORY to "分类",
        SearchDimension.SERIES to "系列",
        SearchDimension.PLATFORM to "平台",
        SearchDimension.SOURCE to "来源",
        SearchDimension.GENRE to "类型",
        SearchDimension.THEME to "题材",
        SearchDimension.REGION to "地区",
        SearchDimension.AUDIENCE to "受众",
        SearchDimension.CONTENT_RATING to "分级",
        SearchDimension.PUBLICATION_STATUS to "进度",
    )

    /** 这些标签在分类浏览中没有对应 wiki 标签，因此不收录。 */
    private val excludedFromDictionary = setOf(
        Entry(4, "受众", "Galgame", "Galgame"),
        Entry(4, "平台", "iOS", "iOS"),
        Entry(4, "平台", "PS Vita", "PSV"),
        Entry(1, "受众", "GL", "GL"),
    )

    private fun fixtureEntries(): Set<Entry> {
        val file = RepoFiles.file("core/model/src/test/resources/search-dimension-audit/search-dimensions.tsv")
        return file.readLines(Charsets.UTF_8).drop(1).map { line ->
            val (typeId, dimension, webTag, metaTag) = line.split('\t')
            Entry(typeId.toInt(), dimension, webTag, metaTag)
        }.toSet()
    }

    private fun dictionaryEntries(): Set<Entry> =
        listOf(SubjectType.ANIME, SubjectType.BOOK, SubjectType.GAME, SubjectType.MUSIC, SubjectType.REAL)
            .flatMap { type ->
                searchDimensionsFor(type).flatMap { group ->
                    group.options.map { Entry(type.value, dimensionNames.getValue(group.dimension), it.label, it.metaTag) }
                }
            }
            .toSet()

    @Test
    fun 维度夹具使用稳定名称与字段() {
        val directory = RepoFiles.file("core/model/src/test/resources/search-dimension-audit")
        val files = directory.listFiles()?.filter { it.isFile }?.map { it.name }?.toSet()
        org.junit.Assert.assertEquals(setOf("search-dimensions.tsv"), files)
        val lines = directory.resolve("search-dimensions.tsv").readLines(Charsets.UTF_8)
        org.junit.Assert.assertEquals("typeId\tdimension\twebTag\tmetaTag", lines.first())
        assertTrue(lines.drop(1).all { line ->
            val fields = line.split('\t')
            fields.size == 4 && fields.all { it.isNotBlank() } && fields[0].toIntOrNull() != null
        })
    }

    @Test
    fun 夹具字段映射保留网页标签与查询别名() {
        val entries = fixtureEntries()
        assertTrue(Entry(2, "分类", "TV", "TV") in entries)
        assertTrue(Entry(4, "平台", "PS Vita", "PSV") in entries)
    }

    @Test
    fun 词典每个取值都有夹具条目() {
        val missingFromFixture = dictionaryEntries() - fixtureEntries()
        assertTrue("词典含夹具没有的取值：$missingFromFixture", missingFromFixture.isEmpty())
    }

    @Test
    fun 每条夹具条目都已收录或明确排除() {
        org.junit.Assert.assertEquals(4, excludedFromDictionary.size)
        assertTrue(fixtureEntries().containsAll(excludedFromDictionary))
        assertTrue((excludedFromDictionary intersect dictionaryEntries()).isEmpty())
        val missing = (fixtureEntries() - excludedFromDictionary) - dictionaryEntries()
        assertTrue("夹具含词典未收录的取值：$missing", missing.isEmpty())
    }
}
