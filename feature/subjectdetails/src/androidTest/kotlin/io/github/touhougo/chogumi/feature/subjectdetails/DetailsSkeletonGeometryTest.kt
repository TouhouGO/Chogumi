package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.RelatedCharacterBo
import io.github.touhougo.chogumi.core.model.RelatedPersonBo
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectRelationBo
import io.github.touhougo.chogumi.core.model.SubjectSeries
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.staffPositionRows
import io.github.touhougo.chogumi.core.reader.SynopsisTag
import io.github.touhougo.chogumi.core.ui.composables.CarouselPersonTag
import io.github.touhougo.chogumi.core.ui.composables.EpisodeTileTag
import io.github.touhougo.chogumi.core.ui.composables.SkeletonEpisodeTileTag
import io.github.touhougo.chogumi.core.ui.composables.rememberCollapsibleHeaderState
import io.github.touhougo.chogumi.core.ui.testing.SkeletonAnchor
import io.github.touhougo.chogumi.core.ui.testing.assertSkeletonMatchesReal
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 作品详情页的骨架与真实内容的**几何同源**：内容出现时，骨架里已画出的区块不许挪位置。
 *
 * 做法同 core:ui 的 `SkeletonGeometryTest`：同一个窗口里先渲染骨架、读锚点，再翻转 state 换成真实内容，
 * 上、下、左三边逐一比较（容差 0.5dp）。每组配一条 fontScale = 2 的用例。
 *
 * - 首屏：整页骨架对 [SubjectDetailsFirstScreen]（底色、封面、标题、元信息行、两枚小标、标签行、选项卡）；
 * - 各区块：章节网格 / 概览（简介、角色横排、关联作品横排）/ 人物 tab，
 *   各自经**生产的区块函数**走一遍「加载中 → 有内容」。
 */
@RunWith(AndroidJUnit4::class)
class DetailsSkeletonGeometryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var showReal by mutableStateOf(false)

    private val features = AppFeatures(community = true, publicRatings = true, coverScores = true, friendMarks = true)

    private fun setContent(fontScale: Float, content: @Composable () -> Unit) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    Box(Modifier.width(400.dp)) { content() }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun flipToReal() {
        composeRule.runOnIdle { showReal = true }
        composeRule.waitForIdle()
    }

    private fun sameTag(name: String, tag: String) = SkeletonAnchor(
        name,
        { composeRule.onNodeWithTag(tag, useUnmergedTree = true) },
        { composeRule.onNodeWithTag(tag, useUnmergedTree = true) },
    )

    // ---- 首屏 ----

    private fun assertFirstScreen(fontScale: Float, features: AppFeatures = this.features) {
        setContent(fontScale) {
            if (showReal) {
                SubjectDetailsFirstScreen(
                    subject = DetailsSkeletonFixtures.subject,
                    topPadding = TOP_PADDING,
                    features = features,
                    sections = visibleSectionsFor(DetailsSkeletonFixtures.subject, features),
                    selectedSection = SubjectDetailsSection.OVERVIEW,
                    onSelectSection = {},
                    onInfoClick = {},
                    onTagClick = {},
                    headerState = rememberCollapsibleHeaderState(),
                    glass = null,
                    content = {},
                )
            } else {
                SubjectDetailsPageSkeleton(topPadding = TOP_PADDING, features = features, glass = null)
            }
        }
        assertSkeletonMatchesReal(
            anchors = listOf(
                sameTag("底色", DetailsBannerTag),
                sameTag("封面框", DetailsPosterTag),
                sameTag("标题", DetailsTitleTag),
                sameTag("元信息行", DetailsMetaTag),
                sameTag("小标行", DetailsHeaderChipsTag),
                sameTag("标签行", DetailsTagsTag),
                sameTag("选项卡行", DetailsTabsTag),
            ),
            flipToReal = ::flipToReal,
        )
    }

    @Test
    fun 详情页首屏骨架与真实内容同几何() = assertFirstScreen(fontScale = 1f)

    @Test
    fun 字体放大两倍时详情页首屏骨架与真实内容同几何() = assertFirstScreen(fontScale = 2f)

    @Test
    fun 专注模式下详情首屏骨架与真实内容同几何() =
        assertFirstScreen(1f, AppFeatures(community = false, publicRatings = false, coverScores = false, friendMarks = false))

    @Test
    fun 两倍字号专注模式下详情首屏骨架与真实内容同几何() =
        assertFirstScreen(2f, AppFeatures(community = false, publicRatings = false, coverScores = false, friendMarks = false))

    // ---- 半截条目 → 完整条目：已画出的区块不许往下移（与骨架无关的另一条下移路径） ----

    private var firstScreenSubject by mutableStateOf(DetailsSkeletonFixtures.partialSubject)

    private fun setFirstScreen(fontScale: Float) {
        setContent(fontScale) {
            SubjectDetailsFirstScreen(
                subject = firstScreenSubject,
                topPadding = TOP_PADDING,
                features = features,
                sections = visibleSectionsFor(firstScreenSubject, features),
                selectedSection = SubjectDetailsSection.OVERVIEW,
                onSelectSection = {},
                onInfoClick = {},
                onTagClick = {},
                headerState = rememberCollapsibleHeaderState(),
                glass = null,
                content = {},
            )
        }
    }

    /** 半截条目与它对应的完整条目之间，元信息行、标签行、选项卡行的上/下/左三边必须一致。 */
    private fun assertPartialToFullKeepsBlocks(fontScale: Float, partial: SubjectBo, target: SubjectBo) {
        firstScreenSubject = partial
        setFirstScreen(fontScale)
        assertSkeletonMatchesReal(
            anchors = listOf(
                sameTag("元信息行", DetailsMetaTag),
                sameTag("标签行", DetailsTagsTag),
                sameTag("选项卡行", DetailsTabsTag),
            ),
            flipToReal = {
                composeRule.runOnIdle { firstScreenSubject = target }
                composeRule.waitForIdle()
            },
        )
    }

    @Test
    fun 半截条目的元信息与标签占位不被完整数据推下去() =
        assertPartialToFullKeepsBlocks(1f, DetailsSkeletonFixtures.partialSubject, DetailsSkeletonFixtures.fullSubject)

    @Test
    fun 字体放大两倍时半截条目的元信息与标签占位不被完整数据推下去() =
        assertPartialToFullKeepsBlocks(2f, DetailsSkeletonFixtures.partialSubject, DetailsSkeletonFixtures.fullSubject)

    /** v0 半截行：有用户标签、没有分类，完整条目补上分类（排在前面）——标签行只横向变，不许上下动。 */
    @Test
    fun 列表行只有用户标签时完整数据不推动下面的区块() =
        assertPartialToFullKeepsBlocks(1f, DetailsSkeletonFixtures.partialSearchSubject, DetailsSkeletonFixtures.fullSearchSubject)

    @Test
    fun 字体放大两倍时列表行只有用户标签时完整数据不推动下面的区块() =
        assertPartialToFullKeepsBlocks(2f, DetailsSkeletonFixtures.partialSearchSubject, DetailsSkeletonFixtures.fullSearchSubject)

    /** 完整条目本身没有标签：标签行整块上收是允许的，但绝不许往下移。 */
    private fun assertEmptyBlocksOnlyShrink(fontScale: Float) {
        firstScreenSubject = DetailsSkeletonFixtures.partialSubject
        setFirstScreen(fontScale)
        assertSkeletonMatchesReal(
            anchors = listOf(sameTag("选项卡行", DetailsTabsTag)),
            flipToReal = {
                composeRule.runOnIdle { firstScreenSubject = DetailsSkeletonFixtures.fullSubjectWithoutTagsOrSynopsis }
                composeRule.waitForIdle()
            },
            // 缺席的区块只能把下面的内容往上收。
            allowUpwardMovement = true,
        )
    }

    @Test
    fun 完整条目没有标签时占位只上收() = assertEmptyBlocksOnlyShrink(1f)

    @Test
    fun 字体放大两倍时完整条目没有标签占位只上收() = assertEmptyBlocksOnlyShrink(2f)

    // ---- 章节网格 ----

    /** 骨架阶段是「等待」，翻到真实内容后是「就绪」。 */
    private fun <T> asyncList(items: List<T>) = AsyncList(if (showReal) Load.Ready(items) else Load.Pending, onRetry = {})

    private var episodes by mutableStateOf(emptyList<EpisodeBo>())

    /**
     * 骨架的第一格与真实方格的第一格同框。[count] 集正片、条目资料里的话数也是 [count]：
     * 超过一百集时两边都有分段导航条（骨架按话数预留），方格不被顶下去。
     */
    private fun assertEpisodesGrid(fontScale: Float, count: Int = 12) {
        episodes = emptyList()
        setContent(fontScale) {
            // 页面里章节 tab 是列表的一项（高度不限）：一段一百集的方格比屏幕高，这里同样放进可滚的列
            Column(Modifier.verticalScroll(rememberScrollState())) {
                EpisodesSection(
                    subjectType = SubjectType.ANIME,
                    episodes = asyncList(episodes),
                    watchStates = emptyMap(),
                    today = LocalDate.of(2026, 10, 3),
                    expectedEpisodes = count,
                    pendingCommit = null,
                    offerMarkDone = false,
                    onWatchUpTo = gatedNoop1(),
                    onToggleSpecial = gatedNoop1(),
                    onMarkDone = gatedNoop(),
                    onEpisodeLongPress = {},
                )
            }
        }
        assertSkeletonMatchesReal(
            anchors = listOf(
                SkeletonAnchor(
                    "章节方格第一格",
                    { composeRule.onAllNodesWithTag(SkeletonEpisodeTileTag, useUnmergedTree = true)[0] },
                    { composeRule.onAllNodesWithTag(EpisodeTileTag, useUnmergedTree = true)[0] },
                ),
            ),
            flipToReal = {
                composeRule.runOnIdle { episodes = DetailsSkeletonFixtures.episodes(count); showReal = true }
                composeRule.waitForIdle()
            },
        )
    }

    @Test
    fun 章节方格骨架第一格与真实第一格同框() = assertEpisodesGrid(fontScale = 1f)

    @Test
    fun 字体放大两倍时章节方格骨架第一格与真实第一格同框() = assertEpisodesGrid(fontScale = 2f)

    @Test
    fun 长篇分段时骨架预留导航条_第一格同框() = assertEpisodesGrid(fontScale = 1f, count = 120)

    // ---- 概览：简介、角色横排、关联作品横排 ----

    private var persons by mutableStateOf(emptyList<RelatedPersonBo>())
    private var characters by mutableStateOf(emptyList<RelatedCharacterBo>())
    private var relations by mutableStateOf(emptyList<SubjectRelationBo>())
    private var overviewSubject by mutableStateOf<SubjectBo?>(null)


    private fun assertOverview(fontScale: Float, wrapped: Boolean = false) {
        characters = emptyList()
        relations = emptyList()
        overviewSubject = null
        setContent(fontScale) {
            SubjectOverviewSection(
                synopsis = firstScreenSlots(overviewSubject).synopsis,
                synopsisExpanded = false,
                onSynopsisToggle = {},
                characters = asyncList(characters),
                relations = asyncList(relations),
                subject = DetailsSkeletonFixtures.subject,
                series = Load.Ready(SubjectSeries(before = emptyList(), after = emptyList())),
                onCharacterClick = {},
                onSubjectClick = {},
                onCharactersSeeAll = {},
                onRelationsSeeAll = {},
            )
        }
        assertSkeletonMatchesReal(
            anchors = listOf(
                sameTag("简介", SynopsisTag),
                headerAnchor("角色标题行", 0),
                carouselPersonAnchor("角色横排首项", 0),
                headerAnchor("关联作品标题行", 1),
                SkeletonAnchor(
                    "关联作品首张卡",
                    { composeRule.onAllNodesWithTag(DetailsRelationCardTag, useUnmergedTree = true)[0] },
                    { composeRule.onAllNodesWithTag(DetailsRelationCardTag, useUnmergedTree = true)[0] },
                ),
            ),
            flipToReal = {
                composeRule.runOnIdle {
                    overviewSubject = DetailsSkeletonFixtures.subject
                    characters = DetailsSkeletonFixtures.characters.map {
                        if (wrapped) it.copy(name = "足够长的角色名字用于溢出省略") else it
                    }
                    relations = DetailsSkeletonFixtures.relations
                    showReal = true
                }
                composeRule.waitForIdle()
            },
        )
    }

    private fun headerAnchor(name: String, index: Int) = SkeletonAnchor(
        name,
        { composeRule.onAllNodesWithTag(DetailsSectionHeaderTag, useUnmergedTree = true)[index] },
        { composeRule.onAllNodesWithTag(DetailsSectionHeaderTag, useUnmergedTree = true)[index] },
    )

    private fun carouselPersonAnchor(name: String, index: Int) = SkeletonAnchor(
        name,
        { composeRule.onAllNodesWithTag(CarouselPersonTag, useUnmergedTree = true)[index] },
        { composeRule.onAllNodesWithTag(CarouselPersonTag, useUnmergedTree = true)[index] },
    )

    @Test
    fun 概览骨架与真实内容同几何() = assertOverview(fontScale = 1f)

    @Test
    fun 字体放大两倍时概览骨架与真实内容同几何() = assertOverview(fontScale = 2f)

    @Test
    fun 长名字的概览横排骨架同框() = assertOverview(1f, wrapped = true)

    @Test
    fun 两倍字号长名字的概览横排骨架同框() = assertOverview(2f, wrapped = true)

    // ---- 人物 tab ----

    private fun assertPeople(fontScale: Float) {
        persons = emptyList()
        characters = emptyList()
        setContent(fontScale) {
            SubjectPeopleSection(
                characters = asyncList(characters),
                persons = asyncList(persons),
                staffRows = staffPositionRows(persons, DetailsSkeletonFixtures.subject.type),
                selected = PeopleGroup.CHARACTERS,
                onSelect = {},
                onCharacterClick = {},
                onPersonClick = {},
            )
        }
        assertSkeletonMatchesReal(
            anchors = listOf(
                SkeletonAnchor(
                    "角色列表首项",
                    { composeRule.onAllNodesWithTag(DetailsPeopleItemTag, useUnmergedTree = true)[0] },
                    { composeRule.onAllNodesWithTag(DetailsPeopleItemTag, useUnmergedTree = true)[0] },
                ),
            ),
            flipToReal = {
                composeRule.runOnIdle {
                    persons = DetailsSkeletonFixtures.persons
                    characters = DetailsSkeletonFixtures.characters
                    showReal = true
                }
                composeRule.waitForIdle()
            },
        )
    }

    @Test
    fun 人物tab骨架首项与真实首项同框() = assertPeople(fontScale = 1f)

    @Test
    fun 字体放大两倍时人物tab骨架首项与真实首项同框() = assertPeople(fontScale = 2f)

    private companion object {
        /** 模拟状态栏高度：banner 高度含这一段。 */
        val TOP_PADDING = 24.dp
    }
}
