package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import io.github.touhougo.chogumi.core.model.ExperienceMode
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SearchDimensionOption
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.appFeatures
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * 详情页点分类必须把它落到当前条目类型的维度上（直接产出搜索条件）。
 *
 * 为什么需要它：搜索页从路由只收一整组条件，条件里的 `subjectType` 决定分类落到哪个维度
 * （`tagSearchCriteria` 的 `withCategoriesPlaced`）。少了它，从动画详情页点「TV」
 * 会按「全部」搜、落到错误的维度——而这条链路上没有任何单测守得住，因为它要真的点一下 chip。
 *
 * 判据用**生产组件** [SubjectDetailsHeader]（不是复刻实现），点击真实的分类 chip。
 */
class SubjectTagSeedTypeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val subject = SubjectBo(
        id = 8,
        type = SubjectType.ANIME,
        name = "Sousou no Frieren",
        nameCn = "葬送的芙莉莲",
        metaTags = listOf("TV", "漫画改"),
        tags = emptyList(),
    )

    @Test
    fun 点分类发出的条件必须带条目类型并把分类落成维度() {
        var captured: SearchCriteria? = null
        composeRule.setContent {
            ChogumiTheme(darkTheme = false) {
                SubjectDetailsHeader(
                    subject = subject,
                    topPadding = androidx.compose.ui.unit.Dp(0f),
                    // 可见性走生产函数 appFeatures（Header 需要它）；
                    // 本条测的是标签点击，与专注模式无关，取全功能模式即可。
                    features = appFeatures(ExperienceMode.FULL, showPublicRatings = true, showCoverScores = true, showFriendMarks = true),
                    onRatingClick = null,
                    onInfoClick = {},
                    onTagClick = { captured = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule.onAllNodesWithText("TV").onFirst().performClick()

        assertNotNull("点击后必须发出搜索条件", captured)
        assertEquals(
            "条件必须带上条目类型，否则搜索会落到错误的维度",
            SubjectType.ANIME,
            captured!!.subjectType,
        )
        assertEquals(
            "「TV」是动画分类词典里的取值，必须落成 CATEGORY 维度的一个选择",
            listOf(SearchDimensionOption("TV", "TV")),
            captured.filters.dimensions[SearchDimension.CATEGORY],
        )
    }
}
