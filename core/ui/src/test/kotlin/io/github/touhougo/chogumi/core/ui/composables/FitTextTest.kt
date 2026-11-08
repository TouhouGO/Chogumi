package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 一串文字放不下一行时只缩可缩的那一段（[fitPlaceholder]、[fitSegment]、[dropTrailingToFit]、[fitKeep]）。宽度按「一个字 = 1」量。 */
class FitTextTest {

    private val width: (String) -> Int = { it.length }

    @Test
    fun `放得下就原样`() {
        assertEquals("在「むちまろ」下搜索", fitPlaceholder("在「むちまろ」下搜索", "むちまろ", 20, width))
    }

    @Test
    fun `放不下时只截取值，句子其余部分保持完整`() {
        val text = "在「生徒会にも穴はある！公式アンソロジー」下搜索"
        val fitted = fitPlaceholder(text, "生徒会にも穴はある！公式アンソロジー", 14, width)
        assertEquals("在「生徒会にも穴は…」下搜索", fitted)
        assertEquals(14, fitted.length)
    }

    @Test
    fun `没有可缩的段时交给句尾省略`() {
        assertEquals("搜索动画、书籍、游戏…", fitPlaceholder("搜索动画、书籍、游戏…", null, 5, width))
    }

    @Test
    fun `连只剩省略号都放不下时原样返回`() {
        assertEquals("在「むちまろ」下搜索", fitPlaceholder("在「むちまろ」下搜索", "むちまろ", 4, width))
    }

    /** 候选各段拼起来的宽度，加上段与段之间的固定宽度（段间空白）。 */
    private fun metaWidth(gap: Int): (List<AnnotatedString>) -> Int = { parts -> parts.sumOf { it.length } + gap * (parts.size - 1) }

    private fun segments(vararg texts: String) = texts.map(::AnnotatedString)

    private fun fit(parts: List<AnnotatedString>, shrink: Int, maxWidth: Int, widthOf: (List<AnnotatedString>) -> Int) =
        fitSegment(parts, shrink, maxWidth, widthOf).map { it.text }

    @Test
    fun `眉题：作品名过长时只截作品名，讨论版保持完整`() {
        val parts = segments("葬送のフリーレン第二季特别篇", "讨论版")
        val fitted = fit(parts, shrink = 0, maxWidth = 12, widthOf = metaWidth(gap = 2))
        assertEquals(listOf("葬送のフリー…", "讨论版"), fitted)
        assertEquals(12, metaWidth(2)(segments(*fitted.toTypedArray())))
    }

    @Test
    fun `段间固定宽度要算进去`() {
        val parts = segments("一二三四五六七八", "后缀")
        assertEquals(listOf("一二三四五六七八", "后缀"), fit(parts, 0, 12, metaWidth(2)))
        val fitted = fit(parts, 0, 12, metaWidth(3))
        assertEquals(listOf("一二三四五六…", "后缀"), fitted)
        assertEquals(12, metaWidth(3)(segments(*fitted.toTypedArray())))
    }

    @Test
    fun `缩的是中间一段时前后各段都不动`() {
        val parts = segments("甲", "一二三四五六", "乙")
        assertEquals(listOf("甲", "一二三四…", "乙"), fit(parts, shrink = 1, maxWidth = 7, widthOf = { it.sumOf(AnnotatedString::length) }))
    }

    @Test
    fun `其余各段本身就放不下时原样返回`() {
        val parts = segments("一二三", "后缀很长很长")
        assertEquals(listOf("一二三", "后缀很长很长"), fit(parts, shrink = 0, maxWidth = 5, widthOf = { it.sumOf(AnnotatedString::length) }))
    }

    @Test
    fun `下标越界或空段时原样返回`() {
        val parts = segments("甲", "")
        assertEquals(listOf("甲", ""), fit(parts, shrink = 5, maxWidth = 1, widthOf = { it.sumOf(AnnotatedString::length) }))
        assertEquals(listOf("甲", ""), fit(parts, shrink = 1, maxWidth = 0, widthOf = { it.sumOf(AnnotatedString::length) }))
    }

    private fun indexed(vararg texts: String) = texts.mapIndexed { index, text -> IndexedValue(index, AnnotatedString(text)) }

    private fun dropped(parts: List<IndexedValue<AnnotatedString>>, droppableFrom: Int, maxWidth: Int) =
        dropTrailingToFit(parts, droppableFrom, shrinkIndex = null, maxWidth = maxWidth) { kept ->
            metaWidth(gap = 2)(kept.map { it.value })
        }.map { it.value.text }

    @Test
    fun `可有可无的末段：放不下整段去掉，放得下或只剩一段时不动`() {
        val parts = indexed("在看", "看到第 3 集", "更新于 1 小时前")
        assertEquals(listOf("在看", "看到第 3 集", "更新于 1 小时前"), dropped(parts, droppableFrom = 2, maxWidth = 30))
        assertEquals(listOf("在看", "看到第 3 集"), dropped(parts, droppableFrom = 2, maxWidth = 20))
        assertEquals("只去末段这一段，其余交给截短与句尾省略", listOf("在看", "看到第 3 集"), dropped(parts, droppableFrom = 2, maxWidth = 5))
        assertEquals(listOf("更新于 1 小时前"), dropped(indexed("更新于 1 小时前"), droppableFrom = 0, maxWidth = 1))
    }

    @Test
    fun `越靠后越可有可无：一直去到只剩保底的第一段`() {
        val parts = indexed("电视动画", "2024 年 4 月", "12 话", "资料表")
        assertEquals(listOf("电视动画"), dropped(parts, droppableFrom = 1, maxWidth = 1))
        assertEquals(listOf("电视动画", "2024 年 4 月"), dropped(parts, droppableFrom = 1, maxWidth = 20))
    }

    @Test
    fun `fitKeep：放得下返回 null，放不下返回最长可保留字数`() {
        assertNull(fitKeep(5) { true })
        assertEquals(2, fitKeep(5) { keep -> keep + 1 <= 3 && keep < 5 })
        assertNull(fitKeep(5) { false })
    }
}
