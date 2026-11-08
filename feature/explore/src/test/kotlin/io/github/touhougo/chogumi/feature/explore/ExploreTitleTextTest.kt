package io.github.touhougo.chogumi.feature.explore

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import io.github.touhougo.chogumi.core.model.ExploreTitle
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.shelfPresetsFor
import io.github.touhougo.chogumi.core.res.R
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ExploreTitleTextTest {

    private val baseTitles: List<ExploreTitle> = listOf(
        // 固定入口 (3)
        ExploreTitle.DailyCalendar,
        ExploreTitle.Filters,
        ExploreTitle.Ranking,

        // 带参 (4 个带参类型，枚举各种具体参数)
        ExploreTitle.NewSeason(10),
        ExploreTitle.CatchUpSeason(7),
        ExploreTitle.ThisMonth(SubjectType.BOOK),
        ExploreTitle.ThisMonth(SubjectType.MUSIC),
        ExploreTitle.ThisMonth(SubjectType.GAME),
        ExploreTitle.ThisMonth(SubjectType.REAL),
        ExploreTitle.ThisMonth(SubjectType.ANIME),

        // 基础货架
        ExploreTitle.Trending,
        ExploreTitle.TodayOnAir,
        ExploreTitle.ThemeBrowse,
        ExploreTitle.DecadeBrowse,
        ExploreTitle.CompanyBrowse(SubjectType.ANIME),
        ExploreTitle.CompanyBrowse(SubjectType.GAME),
        ExploreTitle.PlatformBrowse,
        ExploreTitle.GenreBrowse,
        ExploreTitle.FormatBrowse,
        ExploreTitle.RegionBrowse,
        ExploreTitle.Tokusatsu,

    )

    private val stringXmlMap by lazy { loadStringsXml() }
    private val resIdToKeyMap by lazy { loadResIdToKeyMap() }

    private fun formatTitleText(title: ExploreTitle): String =
        when (val spec = exploreTitleTextSpec(title)) {
            is ExploreTitleTextSpec.Literal -> spec.text
            is ExploreTitleTextSpec.Res -> {
                val key = resIdToKeyMap[spec.stringRes] ?: error("未找到资源 ID ${spec.stringRes} 对应的名称")
                val rawTemplate = stringXmlMap[key] ?: error("未在 strings.xml 中找到键 $key")
                if (spec.formatArgs.isEmpty()) rawTemplate
                else String.format(rawTemplate, *spec.formatArgs.toTypedArray())
            }
        }

    @Test
    fun `每个ExploreTitle都给出非空文字且互不重复`() {
        val texts = baseTitles.map { title ->
            val text = formatTitleText(title)
            assertTrue("$title 的映射文案不能为空", text.isNotBlank())
            title to text
        }

        // 打印映射全表，作为交付凭证
        println("=== ${baseTitles.size} 个 ExploreTitle 中文文案全表 ===")
        texts.forEachIndexed { index, (title, text) ->
            println("[${index + 1}] ${title.javaClass.simpleName}: $text")
        }

        val textList = texts.map { it.second }
        val duplicates = textList.groupBy { it }.filterValues { it.size > 1 }
        assertTrue("存在重复的文案：${duplicates.keys}", duplicates.isEmpty())
        assertEquals(baseTitles.size, textList.toSet().size)
    }

    /** 预设名与用户起的名字原样显示，两种语言一样；名字直接从预设目录里取，不手抄一份。 */
    @Test
    fun `名字货架的文案就是名字本身`() {
        val names = SubjectType.entries.flatMap { shelfPresetsFor(it) }.map { it.name } + "我起的名字"
        for (name in names) {
            assertEquals(ExploreTitleTextSpec.Literal(name), exploreTitleTextSpec(ExploreTitle.Named(name)))
        }
    }

    @Test
    fun `带参的名字正确带入参数`() {
        val newSeason = formatTitleText(ExploreTitle.NewSeason(10))
        assertTrue("NewSeason 未包含月份：$newSeason", newSeason.contains("10"))

        val catchUp = formatTitleText(ExploreTitle.CatchUpSeason(7))
        assertTrue("CatchUpSeason 未包含月份：$catchUp", catchUp.contains("7"))

        val bookMonth = formatTitleText(ExploreTitle.ThisMonth(SubjectType.BOOK))
        assertTrue("书籍 ThisMonth 应为新刊：$bookMonth", bookMonth.contains("新刊"))

        val musicMonth = formatTitleText(ExploreTitle.ThisMonth(SubjectType.MUSIC))
        assertTrue("音乐 ThisMonth 应为新碟：$musicMonth", musicMonth.contains("新碟"))

        val gameMonth = formatTitleText(ExploreTitle.ThisMonth(SubjectType.GAME))
        assertTrue("游戏 ThisMonth 应为发售：$gameMonth", gameMonth.contains("发售"))

        val realMonth = formatTitleText(ExploreTitle.ThisMonth(SubjectType.REAL))
        assertTrue("三次元 ThisMonth 应为开播：$realMonth", realMonth.contains("开播"))

        val animeCompany = formatTitleText(ExploreTitle.CompanyBrowse(SubjectType.ANIME))
        assertTrue("动画 CompanyBrowse 应为动画公司：$animeCompany", animeCompany.contains("动画公司"))

        val gameCompany = formatTitleText(ExploreTitle.CompanyBrowse(SubjectType.GAME))
        assertTrue("游戏 CompanyBrowse 应为游戏公司：$gameCompany", gameCompany.contains("游戏公司"))
    }

    private fun loadResIdToKeyMap(): Map<Int, String> {
        val fields = R.string::class.java.fields
        return buildMap {
            for (field in fields) {
                if (field.type == Int::class.javaPrimitiveType) {
                    val id = field.getInt(null)
                    put(id, field.name)
                }
            }
        }
    }

    private fun loadStringsXml(): Map<String, String> {
        val stringsFile = RepoFiles.file("core/res/src/main/res/values/strings.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsFile)
        val nodes = doc.getElementsByTagName("string")
        return buildMap {
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as Element
                put(element.getAttribute("name"), element.textContent)
            }
        }
    }
}
