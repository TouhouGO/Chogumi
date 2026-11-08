package io.github.touhougo.chogumi.core.ui.stability

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagBo
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Compose 稳定性配置（`gradle/compose-stability.conf`）接上了：模型、日期，以及直接装着模型的页面状态按「值相等」跳过重组。
 * 没有它，强跳过对这些类型只认同一个实例，ViewModel 重算后发出的新对象（内容没变）会让每张卡重组。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StabilityConfigurationTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 每个可组合项每跑一次记下它拿到的值。 */
    private val seen = mutableMapOf<String, MutableList<Any>>()

    /** 页面状态里的一行：本模块编译的类，稳不稳定由它直接装的模型决定（装在 `List` 里的不算，接口本来就按 equals 比）。 */
    private data class Card(val subject: SubjectBo, val mine: Boolean)

    @Test
    fun equalNewInstancesSkipRecomposition() {
        var tick by mutableIntStateOf(0)
        composeRule.setContent { Parent(tick) }
        composeRule.runOnIdle { tick++ }
        composeRule.waitForIdle()

        assertEquals("父级拿到下一拍、重组了", listOf<Any>(0, 1), seen["parent"])
        assertEquals("每个子项只在第一帧跑过", CHILDREN.associateWith { 1 }, CHILDREN.associateWith { seen[it]?.size })
    }

    /** 每拍都新建一份内容相同的值，像 ViewModel 重算后发出的新状态。 */
    @Composable
    private fun Parent(tick: Int) {
        record("parent", tick)
        SubjectChild(subject())
        CardChild(Card(subject(), mine = true))
        DateChild(LocalDate.of(2026, 10, 16))
    }

    @Composable
    private fun SubjectChild(subject: SubjectBo) = record("subject", subject)

    @Composable
    private fun CardChild(card: Card) = record("card", card)

    @Composable
    private fun DateChild(date: LocalDate) = record("date", date)

    private fun record(name: String, value: Any) {
        seen.getOrPut(name) { mutableListOf() } += value
    }

    private companion object {
        val CHILDREN = listOf("subject", "card", "date")

        fun subject() = SubjectBo(
            id = 1,
            type = SubjectType.ANIME,
            name = "作品",
            nameCn = "作品",
            tags = listOf(TagBo(name = "原创", count = 10)),
        )
    }
}
