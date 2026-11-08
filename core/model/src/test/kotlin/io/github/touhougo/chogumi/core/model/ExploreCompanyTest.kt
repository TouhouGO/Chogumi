package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ExploreCompany] 守卫测试。
 *
 * 验证名单规则（公司按用户标签浏览）：
 * 1. 方块产出的 criteria 形状：类型正确、排序 RANK、恰好一个 USER 来源标签且值等于主标签；
 * 2. 同一类型内主标签与显示名均不重复，人物条目 id 全名单不重复；
 * 3. 未配置名单的类型返回空列表；
 * 4. 字段有效性（显示名与主标签非空，人物条目 id 为正）。
 */
class ExploreCompanyTest {

    @Test
    fun 公司方块的criteria走单用户标签浏览且按排名排序() {
        for (type in SubjectType.entries) {
            for (company in exploreCompaniesFor(type)) {
                val criteria = company.toCriteria(type)
                assertEquals(
                    "公司 ${company.name} 的 criteria 类型必须是 $type",
                    type,
                    criteria.subjectType,
                )
                assertEquals("公司 ${company.name} 必须按排名排序", SearchSort.RANK, criteria.sort)
                assertEquals("公司 ${company.name} 不能带关键词", "", criteria.query)
                assertTrue(
                    "公司 ${company.name} 的条件必须走单用户标签浏览（isTagBrowse）",
                    criteria.isTagBrowse(),
                )
                val tags = criteria.filters.tags
                assertEquals("公司 ${company.name} 只能有一个标签", 1, tags.size)
                val tag = tags.single()
                assertEquals(
                    "公司 ${company.name} 的标签必须是 USER 来源（服务端按用户标签处理）",
                    TagSource.USER,
                    tag.source(),
                )
                assertEquals(
                    "公司 ${company.name} 的标签值必须等于主标签",
                    company.tag,
                    tag.value,
                )
            }
        }
    }

    @Test
    fun 动画与游戏名单非空() {
        for (type in listOf(SubjectType.ANIME, SubjectType.GAME)) {
            assertTrue("$type 公司名单不应为空", exploreCompaniesFor(type).isNotEmpty())
        }
    }

    @Test
    fun 同一类型内显示名与主标签都不重复() {
        for (type in listOf(SubjectType.ANIME, SubjectType.GAME)) {
            val companies = exploreCompaniesFor(type)
            val names = companies.map { it.name }
            assertEquals("$type 公司显示名存在重复: $names", names.toSet().size, names.size)
            val tags = companies.map { it.tag }
            assertEquals("$type 公司主标签存在重复: $tags", tags.toSet().size, tags.size)
        }
        val personIds = (exploreCompaniesFor(SubjectType.ANIME) + exploreCompaniesFor(SubjectType.GAME)).map { it.personId }
        assertEquals("公司的人物条目 id 存在重复: $personIds", personIds.toSet().size, personIds.size)
    }

    @Test
    fun 未配置公司名单的类型返回空列表() {
        val unsupportedTypes = listOf(
            SubjectType.BOOK,
            SubjectType.MUSIC,
            SubjectType.REAL,
            SubjectType.UNKNOWN,
        )
        for (type in unsupportedTypes) {
            val companies = exploreCompaniesFor(type)
            assertTrue("类型 $type 不应有公司名单，但返回了 $companies", companies.isEmpty())
        }
    }

    /** 「主标签非空」全仓只断言这一处（名单非空另见 动画与游戏名单非空）。 */
    @Test
    fun 名单字段基础有效性() {
        val allCompanies = exploreCompaniesFor(SubjectType.ANIME) + exploreCompaniesFor(SubjectType.GAME)
        for (company in allCompanies) {
            assertTrue("公司显示名不能为空: ${company.name}", company.name.isNotBlank())
            assertTrue("公司主标签不能为空: ${company.tag}", company.tag.isNotBlank())
            assertTrue("公司 ${company.name} 的人物条目 id 必须是正数", company.personId > 0)
        }
    }
}
