package io.github.touhougo.chogumi.feature.explore

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.ExploreTile
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TileLabel
import io.github.touhougo.chogumi.core.model.TileSet
import io.github.touhougo.chogumi.core.model.TileShelfSource
import io.github.touhougo.chogumi.core.model.tileCandidates
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 探索页方块下方的文字按当前语言取：年代与「全部…」随语言变，词与预设名原样。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExploreTileLabelTextTest {

    @get:Rule
    val compose = createComposeRule()

    private fun tilesOf(type: SubjectType, set: TileSet): List<ExploreTile> = tileCandidates(type, TileShelfSource.Fixed(set))

    /** 一次组合里取全部文字（一个测试只能 setContent 一次）。 */
    private fun labelTexts(tiles: List<ExploreTile>): List<String> {
        var texts = emptyList<String>()
        compose.setContent { texts = tiles.map { exploreTileLabelText(it.label) } }
        compose.waitForIdle()
        return texts
    }

    @Test
    @Config(qualifiers = "en")
    fun 英文界面的年代方块写成1990s() {
        assertEquals(
            listOf("2020s", "2010s", "2000s", "1990s", "1980s", "1970s"),
            labelTexts(tilesOf(SubjectType.ANIME, TileSet.DECADE)),
        )
    }

    @Test
    @Config(qualifiers = "en")
    fun 英文界面的特摄组头写成All加词() {
        assertEquals(
            listOf("All 特摄", "假面骑士", "超级战队", "奥特曼"),
            labelTexts(tilesOf(SubjectType.REAL, TileSet.TOKUSATSU)),
        )
    }

    @Test
    @Config(qualifiers = "zh")
    fun 中文界面的年代与特摄组头() {
        val decade1990 = tilesOf(SubjectType.ANIME, TileSet.DECADE)[3]
        val tokusatsuHead = tilesOf(SubjectType.REAL, TileSet.TOKUSATSU).first()
        assertEquals(listOf("1990 年代", "全部特摄"), labelTexts(listOf(decade1990, tokusatsuHead)))
    }

    @Test
    @Config(qualifiers = "en")
    fun 预设方块写预设名() {
        val tile = tileCandidates(SubjectType.ANIME, TileShelfSource.Custom("任意"))
            .single { it.label == TileLabel.Preset("异世界+转生", "转生异世界") }
        assertEquals(listOf("转生异世界"), labelTexts(listOf(tile)))
    }
}
