package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整理对布局的改动：拖动排序只排显示着的、隐藏的接在后面；放上页面挪到最前（近期热门下面第一条）；
 * 两种编辑面板的保存改哪条、留在哪、记下什么；「添加货架」的清单里每一行是哪条货架、在不在页面上。
 */
class ExploreShelfEditingTest {

    private val anime = SubjectType.ANIME
    private val defaults = defaultShelfLayout(anime)

    private val fantasy = checkNotNull(shelfPresetOf(anime, "奇幻"))

    private fun work(layout: List<ShelfEntry>, id: String) = layout.single { it.id == id } as WorkShelfEntry

    private fun tiles(layout: List<ShelfEntry>, id: String) = layout.single { it.id == id } as TileShelfEntry

    @Test
    fun `拖动排序_显示着的按新顺序在前_隐藏的按原顺序接在后面`() {
        val layout = defaults.withHidden("preset:恋爱")
        val shown = layout.filterNot { it.hidden }.map { it.id }
        val moved = listOf(shown.last()) + shown.dropLast(1)

        val reordered = layout.reordered(moved)

        assertEquals(moved, reordered.take(moved.size).map { it.id })
        assertEquals(listOf("preset:恋爱"), reordered.drop(moved.size).map { it.id })
        assertEquals(layout.toSet(), reordered.toSet())
    }

    @Test
    fun `拖动排序_不认识的与重复的id不算`() {
        val ids = defaults.map { it.id }
        val reordered = defaults.reordered(listOf(ids[1], "custom:99", ids[1], ids[0]))

        assertEquals(listOf(ids[1], ids[0]) + ids.drop(2), reordered.map { it.id })
    }

    @Test
    fun `放上页面_移除过的放出来挪到最前_改过的样式留着`() {
        val layout = defaults
            .withWorkShelfSaved(anime, "preset:恋爱", checkNotNull(shelfPresetOf(anime, "恋爱")).criteria, "恋爱", WorkShelfStyle.DECK)
            .withHidden("preset:恋爱")

        val back = layout.withShown(presetShelfEntry(checkNotNull(shelfPresetOf(anime, "恋爱"))))

        val first = back.first() as WorkShelfEntry
        assertEquals("preset:恋爱", first.id)
        assertFalse(first.hidden)
        assertEquals(WorkShelfStyle.DECK, first.style)
        assertEquals(layout.size, back.size)
    }

    @Test
    fun `放上页面_不在布局里的放在最前`() {
        val isekai = presetShelfEntry(checkNotNull(shelfPresetOf(anime, "异世界")))

        val added = defaults.withShown(isekai)

        assertEquals(isekai, added.first())
        assertEquals(defaults, added.drop(1))
    }

    @Test
    fun `保存固定货架_只换样式_位置不变`() {
        val index = defaults.indexOfFirst { it.id == "fixed:RANKING" }
        val other = SearchCriteria(subjectType = anime, sort = SearchSort.COLLECTS).pickWord("百合", WordPick.ADD)

        val saved = defaults.withWorkShelfSaved(anime, "fixed:RANKING", other, "不该用", WorkShelfStyle.SPINE)

        assertEquals("fixed:RANKING", saved[index].id)
        assertEquals(fixedWorkShelfEntry(FixedWorkShelf.RANKING).copy(style = WorkShelfStyle.SPINE), saved[index])
        assertEquals(defaults.size, saved.size)
    }

    @Test
    fun `保存预设_词没变_记下排序_名字与预设名相同不记`() {
        val otherSort = fantasy.criteria.availableSorts().first { it != fantasy.sort }

        val saved = defaults.withWorkShelfSaved(anime, "preset:奇幻", fantasy.criteria.copy(sort = otherSort), fantasy.name, WorkShelfStyle.FOCUS)

        assertEquals(WorkShelfSource.Preset(presetId = "奇幻", sort = otherSort, name = null), work(saved, "preset:奇幻").source)
        assertEquals(defaults.map { it.id }, saved.map { it.id })
    }

    @Test
    fun `保存预设_词没变_改了名字就记下`() {
        val saved = defaults.withWorkShelfSaved(anime, "preset:奇幻", fantasy.criteria, "魔法", fantasy.style)

        assertEquals("魔法", (work(saved, "preset:奇幻").source as WorkShelfSource.Preset).name)
    }

    @Test
    fun `保存预设_词变了_原位换成一条自建货架`() {
        val index = defaults.indexOfFirst { it.id == "preset:奇幻" }
        val changed = fantasy.criteria.pickWord("百合", WordPick.ADD)

        val saved = defaults.withWorkShelfSaved(anime, "preset:奇幻", changed, "奇幻 × 百合", WorkShelfStyle.BENTO)

        assertEquals(
            WorkShelfEntry(id = "custom:1", source = WorkShelfSource.Custom(changed, "奇幻 × 百合"), style = WorkShelfStyle.BENTO),
            saved[index],
        )
        assertTrue(saved.none { it.id == "preset:奇幻" })
        assertEquals(defaults.size, saved.size)
    }

    @Test
    fun `保存自建货架_换条件与名字_位置不变`() {
        val first = SearchCriteria(subjectType = anime, sort = SearchSort.RANK).pickWord("百合", WordPick.ADD)
        val layout = defaults.pinned(anime, first, "百合")
        val second = first.pickWord("校园", WordPick.ADD)

        val saved = layout.withWorkShelfSaved(anime, "custom:1", second, "校园百合", WorkShelfStyle.CHART)

        assertEquals(WorkShelfEntry("custom:1", WorkShelfSource.Custom(second, "校园百合"), WorkShelfStyle.CHART), saved.first())
        assertEquals(layout.drop(1), saved.drop(1))
    }

    @Test
    fun `新建作品货架_按选的样式放在最前`() {
        val criteria = SearchCriteria(subjectType = anime, sort = SearchSort.RANK).pickWord("百合", WordPick.ADD)

        val saved = defaults.withWorkShelfSaved(anime, null, criteria, "百合", WorkShelfStyle.TIMELINE)

        assertEquals(WorkShelfEntry("custom:1", WorkShelfSource.Custom(criteria, "百合"), WorkShelfStyle.TIMELINE), saved.first())
        assertEquals(defaults, saved.drop(1))
    }

    @Test
    fun `保存预设方块_方块与默认一样存回跟随默认_少了一块就记下`() {
        val source = TileShelfSource.Fixed(TileSet.THEME)
        val keys = defaultTileKeys(anime, source)

        val same = defaults.withTileShelfSaved(anime, "tiles:THEME", "不用", keys, TileDrawing.COLOR)
        assertNull(tiles(same, "tiles:THEME").tileKeys)
        assertEquals(TileDrawing.COLOR, tiles(same, "tiles:THEME").drawing)
        assertEquals(source, tiles(same, "tiles:THEME").source)

        val fewer = defaults.withTileShelfSaved(anime, "tiles:THEME", "不用", keys.drop(1), TileDrawing.STACK)
        assertEquals(keys.drop(1), tiles(fewer, "tiles:THEME").tileKeys)
        assertEquals(defaults.map { it.id }, fewer.map { it.id })
    }

    @Test
    fun `新建方块货架_自己的名字与方块_放在最前`() {
        val keys = defaultTileKeys(anime, TileShelfSource.Fixed(TileSet.THEME)).take(3)

        val saved = defaults.withTileShelfSaved(anime, null, "我的题材", keys, TileDrawing.COVER)

        assertEquals(TileShelfEntry("custom:1", TileShelfSource.Custom("我的题材"), keys, TileDrawing.COVER), saved.first())
        assertEquals(defaults, saved.drop(1))
    }

    @Test
    fun `添加货架清单_布局里的用布局那条_移除了的不在页面上`() {
        val layout = defaults.withHidden("preset:奇幻").withHidden("fixed:RANKING")

        val choices = shelfChoicesFor(anime, layout)
        val fixed = choices.fixed.associateBy { it.entry.id }
        val presets = choices.presets.flatMap { it.second }.associateBy { it.entry.id }

        assertFalse(checkNotNull(fixed["fixed:RANKING"]).shown)
        assertTrue(checkNotNull(fixed["fixed:TODAY_ON_AIR"]).shown)
        assertTrue(checkNotNull(fixed["tiles:THEME"]).shown)
        assertFalse(checkNotNull(presets["preset:奇幻"]).shown)
        assertEquals(layout.single { it.id == "preset:奇幻" }, presets["preset:奇幻"]?.entry)
        assertFalse(checkNotNull(presets["preset:异世界"]).shown)
        assertEquals(emptyList<ShelfChoice>(), choices.custom)
    }

    @Test
    fun `添加货架清单_预设按大类分组_名字与词不同才有说明`() {
        val choices = shelfChoicesFor(anime, defaults)
        val group = choices.presets.single { it.first == "奇幻" }.second.associateBy { it.entry.id }

        assertNull(group["preset:异世界"]?.detail)
        val detail = checkNotNull(group["preset:奇幻+战斗"]?.detail)
        assertTrue(detail, "奇幻" in detail && "战斗" in detail && detail != "奇幻战斗")
    }

    @Test
    fun `添加货架清单_固定货架只列这个分类能有的_自建的列在我建的`() {
        val criteria = SearchCriteria(subjectType = SubjectType.BOOK, sort = SearchSort.RANK).pickWord("百合", WordPick.ADD)
        val layout = defaultShelfLayout(SubjectType.BOOK).pinned(SubjectType.BOOK, criteria, "百合")

        val choices = shelfChoicesFor(SubjectType.BOOK, layout)

        assertEquals(listOf("fixed:RANKING"), choices.fixed.map { it.entry.id }.filter { it.startsWith("fixed:") })
        assertEquals(listOf("custom:1"), choices.custom.map { it.entry.id })
        assertTrue(choices.custom.single().shown)
    }
}
