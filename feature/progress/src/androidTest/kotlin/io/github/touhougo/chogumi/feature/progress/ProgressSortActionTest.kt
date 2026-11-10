package io.github.touhougo.chogumi.feature.progress

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.touhougo.chogumi.core.model.ProgressOrder
import io.github.touhougo.chogumi.core.model.ProgressSort
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 进度页的排序菜单：每类有哪些档（关掉公共评分时没有公共评分）、日期档的叫法、分隔线下两种顺序的叫法与回调。 */
class ProgressSortActionTest {

    @get:Rule
    val compose = createComposeRule()

    private val sorts = mutableListOf<ProgressSort>()
    private val directions = mutableListOf<Boolean>()

    private fun open(type: SubjectType?, order: ProgressOrder, publicRatings: Boolean = true) {
        compose.setContent {
            ChogumiTheme {
                ProgressSortAction(
                    type = type,
                    publicRatings = publicRatings,
                    order = order,
                    onSelectSort = { sorts += it },
                    onSelectDirection = { directions += it },
                )
            }
        }
        compose.onNodeWithContentDescription("排序", substring = true).performClick()
    }

    @Test
    fun 动画页有最近更新_日期叫放送日期_时间档是新到旧() {
        open(SubjectType.ANIME, ProgressOrder())
        compose.onNodeWithText("最近更新").assertIsDisplayed()
        compose.onNodeWithText("放送日期").assertIsDisplayed()
        compose.onNodeWithText("新到旧").assertIsDisplayed()
        compose.onNodeWithText("旧到新").assertIsDisplayed()
    }

    @Test
    fun 书籍页没有最近更新_日期叫发售日期() {
        open(SubjectType.BOOK, ProgressOrder())
        compose.onNodeWithText("最近更新").assertDoesNotExist()
        compose.onNodeWithText("发售日期").assertIsDisplayed()
    }

    @Test
    fun 全部页日期只叫日期() {
        open(null, ProgressOrder())
        compose.onNodeWithText("日期").assertIsDisplayed()
        compose.onNodeWithText("最近更新").assertIsDisplayed()
    }

    @Test
    fun 名称档的两种顺序是A到Z与Z到A_点反向回调反向() {
        open(SubjectType.ANIME, ProgressOrder(ProgressSort.TITLE))
        compose.onNodeWithText("A 到 Z").assertIsDisplayed()
        compose.onNodeWithText("Z 到 A").performClick()
        assertEquals(listOf(true), directions)
    }

    @Test
    fun 评分档的两种顺序是高到低与低到高_当前的那项是选中态() {
        open(SubjectType.ANIME, ProgressOrder(ProgressSort.PUBLIC_RATING, reversed = true))
        compose.onNodeWithText("高到低").assertIsNotSelected()
        compose.onNodeWithText("低到高").assertIsSelected()
        compose.onNodeWithText("公共评分").assertIsSelected()
        compose.onNodeWithText("个人评分").assertIsNotSelected()
    }

    @Test
    fun 点另一档回调该档() {
        open(SubjectType.ANIME, ProgressOrder())
        compose.onNodeWithText("个人评分").performClick()
        assertEquals(listOf(ProgressSort.MY_RATING), sorts)
    }

    @Test
    fun 关掉公共评分时没有公共评分档_个人评分还在() {
        open(SubjectType.ANIME, ProgressOrder(), publicRatings = false)
        compose.onNodeWithText("公共评分").assertDoesNotExist()
        compose.onNodeWithText("个人评分").assertIsDisplayed()
    }

    @Test
    fun 排序按钮读屏念当前档与顺序() {
        compose.setContent {
            ChogumiTheme {
                ProgressSortAction(
                    type = SubjectType.ANIME,
                    publicRatings = true,
                    order = ProgressOrder(ProgressSort.DATE, reversed = true),
                    onSelectSort = {},
                    onSelectDirection = {},
                )
            }
        }
        compose.onNodeWithContentDescription("排序：放送日期，旧到新").assertIsDisplayed()
    }
}
