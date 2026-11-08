package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 搜索页图钉与布局的增删改名：判等只认 [presetKey]，同一组条件在一份布局里只有一条货架；
 * 预设货架与自建货架都算「已在探索页上」；移除是隐藏，再钉一次放回来挪到最前。
 */
class ExploreShelfPinTest {

    private val anime = SubjectType.ANIME
    private val defaults = defaultShelfLayout(anime)

    /** 默认布局里的「奇幻」预设：条件与按同样的词、同样的排序在搜索页筛出来的一样。 */
    private val fantasy = checkNotNull(shelfPresetOf(anime, "奇幻"))

    /** 一组不在默认布局里的条件：两个词的交集。 */
    private val custom = SearchCriteria(subjectType = anime, sort = SearchSort.COLLECTS)
        .pickWord("奇幻", WordPick.ADD)
        .pickWord("百合", WordPick.ADD)

    @Test
    fun `与默认预设条件相同的搜索_图钉亮着_名字是预设名`() {
        val pinned = pinnedShelfOf(anime, defaults, fantasy.criteria)

        assertEquals(PinnedShelf(id = "preset:奇幻", name = fantasy.name), pinned)
        assertEquals("已在探索页上的条件再钉一次，布局不变", defaults, defaults.pinned(anime, fantasy.criteria, "随便"))
    }

    @Test
    fun `排序不同就是另一组条件`() {
        val otherSort = fantasy.criteria.availableSorts().first { it != fantasy.sort }

        assertNull(pinnedShelfOf(anime, defaults, fantasy.criteria.copy(sort = otherSort)))
    }

    @Test
    fun `新条件落在最前_自建海报行_再钉一次不重复`() {
        val once = defaults.pinned(anime, custom, "奇幻 × 百合")

        val created = once.first() as WorkShelfEntry
        assertEquals("custom:1", created.id)
        assertEquals(WorkShelfSource.Custom(custom, "奇幻 × 百合"), created.source)
        assertEquals(WorkShelfStyle.POSTER_ROW, created.style)
        assertEquals(defaults, once.drop(1))
        assertEquals(once, once.pinned(anime, custom.copy(query = "关键词不算"), "另一个名字"))
        assertEquals(PinnedShelf("custom:1", "奇幻 × 百合"), pinnedShelfOf(anime, once, custom))
    }

    @Test
    fun `自建序号接着已有的最大号`() {
        val two = defaults.pinned(anime, custom, "一").pinned(anime, custom.pickWord("校园", WordPick.ADD), "二")

        assertEquals(listOf("custom:2", "custom:1"), two.take(2).map { it.id })
    }

    @Test
    fun `移除是隐藏_图钉熄灭_再钉一次放回来挪到最前`() {
        val hidden = defaults.withHidden("preset:奇幻")

        assertEquals(true, hidden.single { it.id == "preset:奇幻" }.hidden)
        assertNull(pinnedShelfOf(anime, hidden, fantasy.criteria))

        val back = hidden.pinned(anime, fantasy.criteria, "随便")
        assertEquals(defaults.single { it.id == "preset:奇幻" }, back.first())
        assertEquals(defaults.size, back.size)
    }

    @Test
    fun `改名_预设记下自己的名字_自建换名字_固定货架不动`() {
        val layout = defaults.pinned(anime, custom, "旧名")
            .withShelfName("preset:奇幻", "魔法")
            .withShelfName("custom:1", "新名")
            .withShelfName("fixed:RANKING", "不该改")

        assertEquals("魔法", pinnedShelfOf(anime, layout, fantasy.criteria)?.name)
        assertEquals("新名", pinnedShelfOf(anime, layout, custom)?.name)
        assertEquals(defaults.single { it.id == "fixed:RANKING" }, layout.single { it.id == "fixed:RANKING" })
    }
}
