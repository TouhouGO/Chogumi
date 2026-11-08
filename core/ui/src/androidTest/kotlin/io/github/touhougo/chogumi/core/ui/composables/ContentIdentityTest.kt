package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.common.LocalAppFeatures
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import io.github.touhougo.chogumi.core.ui.testing.SkeletonAnchor
import io.github.touhougo.chogumi.core.ui.testing.assertSkeletonMatchesReal
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentIdentityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun subject(
        image: String? = "https://example.com/cover.jpg",
        rank: Int = 0,
        score: Double = 8.2,
    ) = SubjectBo(
        id = 8,
        type = SubjectType.ANIME,
        name = "葬送的芙莉莲",
        nameCn = "葬送的芙莉莲",
        imageLarge = image,
        ratingScore = score,
        ratingRank = rank,
    )

    @Test
    fun `无图封面只渲染占位而不渲染图片节点`() {
        composeRule.setContent {
            SubjectCardVertical(subject = subject(image = null), onClick = {}, modifier = ShelfCardWidth)
        }
        composeRule.onNodeWithTag("subject_cover_placeholder", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("subject_cover", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `有图封面渲染图片节点且无占位`() {
        composeRule.setContent {
            SubjectCardVertical(subject = subject(), onClick = {}, modifier = ShelfCardWidth)
        }
        composeRule.onNodeWithTag("subject_cover", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("subject_cover_placeholder", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `区块头渲染标题与单一尾部动作`() {
        var clicks = 0
        composeRule.setContent {
            SectionHeader(
                title = "近期注目",
                trailingText = "全部",
                onTrailingClick = { clicks++ },
            )
        }
        composeRule.onNodeWithText("近期注目").assertExists()
        composeRule.onNodeWithText("全部").assertExists()
        composeRule.onAllNodesWithTag("section_header_trailing", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithTag("section_header_trailing", useUnmergedTree = true).performClick()
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun `货架宽度下海报卡根节点108dp且封面为108乘144`() {
        composeRule.setContent {
            SubjectCardVertical(
                subject = subject(image = null),
                onClick = {},
                modifier = ShelfCardWidth.testTag("subject_card"),
            )
        }
        composeRule.onNodeWithTag("subject_card").assertWidthIsEqualTo(108.dp)
        composeRule.onNodeWithTag("subject_cover_placeholder", useUnmergedTree = true)
            .assertWidthIsEqualTo(108.dp)
            .assertHeightIsEqualTo(144.dp)
    }

    @Test
    fun `区块头尾部动作存在且触控区至少48dp`() {
        composeRule.setContent {
            SectionHeader(
                title = "近期注目",
                trailingText = "全部",
                onTrailingClick = {},
            )
        }
        composeRule.onNodeWithText("全部").assertExists()
        val trailing = composeRule.onNodeWithTag(
            "section_header_trailing",
            useUnmergedTree = true,
        )
        val minTouchPx = with(composeRule.density) { 48.dp.toPx() }
        val touchBounds = trailing.fetchSemanticsNode().touchBoundsInRoot
        assertTrue(
            "语义触控区 ${touchBounds.width}x${touchBounds.height}px 应至少 ${minTouchPx}px",
            touchBounds.width >= minTouchPx && touchBounds.height >= minTouchPx,
        )
    }

    @Test
    fun `带文字动作的标题骨架与真实标题同高`() = assertSectionHeaderGeometry(fontScale = 1f)

    @Test
    fun `两倍字号时带文字动作的标题骨架与真实标题同高`() = assertSectionHeaderGeometry(fontScale = 2f)

    private fun assertSectionHeaderGeometry(fontScale: Float) {
        val real = mutableStateOf(false)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (real.value) {
                            SectionHeader("近期注目", Modifier.testTag("header"), trailingText = "全部", onTrailingClick = {})
                        } else {
                            SectionHeaderSkeleton(Modifier.testTag("header"), hasTrailingAction = true)
                        }
                    }
                }
            }
        }
        assertSkeletonMatchesReal(
            anchors = listOf(SkeletonAnchor("带动作的标题行", { composeRule.onNodeWithTag("header") }, { composeRule.onNodeWithTag("header") })),
            flipToReal = { composeRule.runOnIdle { real.value = true } },
        )
        val trailing = composeRule.onNodeWithTag("section_header_trailing", useUnmergedTree = true)
        val minimum = with(composeRule.density) { 48.dp.toPx() }
        val touch = trailing.fetchSemanticsNode().touchBoundsInRoot
        assertTrue("字号 $fontScale 的文字动作触控目标达标", touch.width >= minimum && touch.height >= minimum)
    }

    @Test
    fun `海报卡有评分时徽章在封面右下角而不在标题下方`() {
        composeRule.setContent {
            SubjectCardVertical(subject = subject(score = 8.2), onClick = {}, modifier = ShelfCardWidth)
        }
        val badge = composeRule.onNodeWithTag("subject_score_badge", useUnmergedTree = true)
        badge.assertExists()
        composeRule.onNodeWithText("★ 8.2").assertExists()

        // 1. 验证徽章处于封面范围内（右下区域）
        val coverBounds = composeRule.onNodeWithTag("subject_cover", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val badgeBounds = badge.getUnclippedBoundsInRoot()
        assertTrue("徽章应在封面水平范围内", badgeBounds.right <= coverBounds.right && badgeBounds.left >= coverBounds.left)
        assertTrue("徽章应在封面垂直范围内", badgeBounds.bottom <= coverBounds.bottom && badgeBounds.top >= coverBounds.top)
        val insetPx = with(composeRule.density) { CoverBadgeInset.roundToPx() }
        val rightGapPx = with(composeRule.density) { (coverBounds.right - badgeBounds.right).roundToPx() }
        val bottomGapPx = with(composeRule.density) { (coverBounds.bottom - badgeBounds.bottom).roundToPx() }
        assertEquals("徽章右边距是封面内边距", insetPx, rightGapPx)
        assertEquals("徽章下边距是封面内边距", insetPx, bottomGapPx)

        // 2. 验证分数挂在封面上，严格高于标题顶部（不在标题下方单开一行）
        val titleBounds = composeRule.onNodeWithText("葬送的芙莉莲", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(
            "分数徽章底部 (${badgeBounds.bottom}) 应高于标题顶部 (${titleBounds.top})",
            badgeBounds.bottom <= titleBounds.top,
        )
    }

    @Test
    fun `关闭公共评分时封面上不渲染分数徽章`() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAppFeatures provides AppFeatures(community = true, publicRatings = false, coverScores = false, friendMarks = true),
            ) {
                SubjectCardVertical(subject = subject(score = 8.2), onClick = {}, modifier = ShelfCardWidth)
            }
        }
        composeRule.onNodeWithTag("subject_score_badge", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("★ 8.2").assertDoesNotExist()
    }

    @Test
    fun `关闭封面评分时封面上不渲染分数徽章`() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAppFeatures provides AppFeatures(community = true, publicRatings = true, coverScores = false, friendMarks = true),
            ) {
                SubjectCardVertical(subject = subject(score = 8.2), onClick = {}, modifier = ShelfCardWidth)
            }
        }
        composeRule.onNodeWithTag("subject_score_badge", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("★ 8.2").assertDoesNotExist()
    }

    @Test
    fun `排行榜场景下名次与分数同时存在且不重叠`() {
        composeRule.setContent {
            SubjectCardVertical(
                subject = subject(score = 8.2),
                onClick = {},
                modifier = ShelfCardWidth,
                coverOverlay = {
                    androidx.compose.material3.Text(
                        text = "1",
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .testTag("rank_numeral"),
                    )
                },
            )
        }
        val rankNode = composeRule.onNodeWithTag("rank_numeral", useUnmergedTree = true)
        val badgeNode = composeRule.onNodeWithTag("subject_score_badge", useUnmergedTree = true)
        rankNode.assertExists()
        badgeNode.assertExists()

        val rankBounds = rankNode.getUnclippedBoundsInRoot()
        val badgeBounds = badgeNode.getUnclippedBoundsInRoot()
        // 名次在左上角，分数在右下角，水平或垂直方向无交集
        assertTrue(
            "名次 (${rankBounds.right}) 应在分数徽章左侧 (${badgeBounds.left})，或者垂直方向无交集",
            rankBounds.right <= badgeBounds.left || rankBounds.bottom <= badgeBounds.top,
        )
    }
}

/** 海报卡的宽度由调用方给；这里按探索货架横排的宽度画。 */
private val ShelfCardWidth = Modifier.width(ChogumiDimens.CoverWidth)
