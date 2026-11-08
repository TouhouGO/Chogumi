package io.github.touhougo.chogumi.feature.explore

import io.github.touhougo.chogumi.core.model.ExploreTitle
import io.github.touhougo.chogumi.core.model.SubjectType
import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreTileStyleTest {

    @Test
    fun `公司货架永远是 Studio 规格`() {
        assertEquals(ExploreTileStyle.Studio, exploreTileStyle(ExploreTitle.CompanyBrowse(SubjectType.ANIME)))
        assertEquals(ExploreTileStyle.Studio, exploreTileStyle(ExploreTitle.CompanyBrowse(SubjectType.GAME)))
    }

    @Test
    fun `年代货架是 Decade 规格`() {
        assertEquals(ExploreTileStyle.Decade, exploreTileStyle(ExploreTitle.DecadeBrowse))
    }

    @Test
    fun `其余货架都是通用 Dimension 规格`() {
        listOf(
            ExploreTitle.ThemeBrowse,
            ExploreTitle.PlatformBrowse,
            ExploreTitle.GenreBrowse,
            ExploreTitle.FormatBrowse,
            ExploreTitle.RegionBrowse,
            ExploreTitle.Tokusatsu,
        ).forEach { title ->
            assertEquals("货架 $title 应当用通用规格", ExploreTileStyle.Dimension, exploreTileStyle(title))
        }
    }
}
