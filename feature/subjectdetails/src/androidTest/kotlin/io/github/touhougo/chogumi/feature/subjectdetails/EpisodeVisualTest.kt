package io.github.touhougo.chogumi.feature.subjectdetails

import android.graphics.Bitmap
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.EpPendingVisual
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.EpisodeType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.composables.EpButton
import io.github.touhougo.chogumi.core.ui.composables.EpisodeTileGrid
import io.github.touhougo.chogumi.core.ui.composables.EpisodeTileTag
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

/**
 * EP 按钮与章节方格的**在机渲染**。
 *
 * 为什么需要它：方格的各种格子（连段、看到这里、下一集、已播未看、未播出、网页上标的想看与抛弃）、提示与
 * EP 按钮的五种形态在假数据下才拍得全，而**真机上点章节/EP 会写用户进度**（不许在测试号上这样做）。
 * 这里直接渲染**生产组件**（不是复刻实现），数据全是本地构造的，不连账号、不发任何网络。
 *
 * 每张图渲染一个形态，落盘到 [saveScreenshot] 的 `episode-tiles/`。
 * 深浅色各出一套：颜色角色在暗色下的对比关系是 M3 的验收点之一。
 */
@VisualCapture
class EpisodeVisualTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate.of(2026, 9, 28)

    /** [count] 集正片，第 [airedUpTo] 集及以前已播出；看过哪些由 [states] 给。 */
    private fun episodes(
        count: Int = 24,
        airedUpTo: Int = count,
        startSort: Int = 1,
    ): List<EpisodeBo> = (0 until count).map { i ->
        val sort = startSort + i
        EpisodeBo(
            id = 1000 + sort,
            subjectId = 8,
            type = EpisodeType.NORMAL,
            name = "ep$sort",
            nameCn = "",
            sort = sort.toDouble(),
            airdate = if (sort <= airedUpTo) "2026-09-%02d".format((sort % 28) + 1) else "2026-10-05",
        )
    }

    private fun states(vararg sorts: Int): Map<Int, EpisodeStatus> =
        sorts.associate { (1000 + it) to EpisodeStatus.DONE }

    @Test
fun 方格与EP按钮各形态落盘() {
    // setContent 每个用例只能调一次，故用一个 Shot 状态驱动，逐个场景切过去拍。
    var shot by mutableStateOf<Shot?>(null)

    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
        val current = shot
        ChogumiTheme(darkTheme = current?.dark == true) {
            // Surface 给出页面的底色与正文色（页面里由 Scaffold 给），深色截图里不带色的字才不是黑的
            Surface(Modifier.fillMaxSize()) {
                Column {
                    when (val s = current) {
                        null -> Unit
                        is Shot.Tiles -> Tiles(s.episodes, s.watchStates, s.offerMarkDone)
                        Shot.Buttons -> EpButtonGallery()
                        Shot.Specials -> SpecialsGallery()
                    }
                }
            }
        }
    }

    // 1. 连段：看 1..5（第一行满）+ 6..7，两段各自连起来，段尾是「看到这里」，第 8 集是圆形的下一集
    shoot("tiles_runs", Shot.Tiles(episodes(), states(1, 2, 3, 4, 5, 6, 7))) { shot = it }
    // 2. 稀疏：只看过 3、5、7，各成一格
    shoot("tiles_sparse", Shot.Tiles(episodes(), states(3, 5, 7))) { shot = it }
    // 3. 未播出：后 8 集未播，一个月内要播的写播出日
    shoot("tiles_unaired", Shot.Tiles(episodes(airedUpTo = 16), states(1, 2, 3))) { shot = it }
    // 4. 网页上单独标的想看、抛弃：照样画出来，方格下面出图例
    val marked = states(1, 2, 3, 4) + mapOf(1006 to EpisodeStatus.WISH, 1007 to EpisodeStatus.DROPPED, 1009 to EpisodeStatus.WISH)
    shoot("tiles_marks", Shot.Tiles(episodes(), marked)) { shot = it }
    // 5. 深色：颜色角色在暗色下的对比是 M3 的验收点
    shoot("tiles_runs", Shot.Tiles(episodes(), states(1, 2, 3, 4, 5, 6, 7), dark = true)) { shot = it }
    shoot("tiles_marks", Shot.Tiles(episodes(), marked, dark = true)) { shot = it }
    // 6. 全部看完、收藏还不是「看过」：方格下面建议标为看过
    shoot("tiles_finished", Shot.Tiles(episodes(count = 12), (1..12).associate { (1000 + it) to EpisodeStatus.DONE }, offerMarkDone = true)) { shot = it }
    // 7. 大跳确认：看到第 2 集时点第 20 集（一次标 18 集），会标上的格子预览出来，旁边是富提示
    shoot("tiles_confirm", Shot.Tiles(episodes(), states(1, 2))) { shot = it }
    composeRule.onAllNodesWithTag(EpisodeTileTag)[19].performClick()
    settle()
    saveScreenshot(FOLDER, "light_tiles_confirm_bubble", capture())
    // 8. 点未播出的格子：正上方一行普通提示
    shoot("tiles_unaired", Shot.Tiles(episodes(airedUpTo = 16), states(1, 2, 3))) { shot = it }
    composeRule.onAllNodesWithTag(EpisodeTileTag)[20].performClick()
    settle()
    saveScreenshot(FOLDER, "light_tiles_unaired_tip", capture())
    // 9. EP 按钮五态
    shoot("ep_button_states", Shot.Buttons) { shot = it }
    // 10. 特别篇：接口给的 1–6 各成一组（SP 看过为实心格），脏数据 type=99 不显示
    shoot("episode_specials", Shot.Specials) { shot = it }
}

/** 长番（海贼王式 1140 集）：看到 1090 集，默认落在 1001–1100 那一段，导航条整部看到哪一眼可见。 */
@Test
fun 长番分段落盘() {
    var shot by mutableStateOf<Shot?>(null)
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
        val current = shot
        ChogumiTheme(darkTheme = current?.dark == true) {
            Surface(Modifier.fillMaxSize()) {
                if (current is Shot.Tiles) Tiles(current.episodes, current.watchStates, current.offerMarkDone)
            }
        }
    }
    val eps = episodes(count = 1140, airedUpTo = 1140)
    val states = (1..1090).associate { (1000 + it) to EpisodeStatus.DONE }
    shoot("long_series_current_range", Shot.Tiles(eps, states)) { shot = it }
}

/** 小数集号：12.5 话必须显示成 12.5。 */
@Test
fun 小数集号落盘() {
    var shot by mutableStateOf<Shot?>(null)
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
        val current = shot
        ChogumiTheme(darkTheme = current?.dark == true) {
            Surface(Modifier.fillMaxSize()) {
                if (current is Shot.Tiles) Tiles(current.episodes, current.watchStates, current.offerMarkDone)
            }
        }
    }
    // 1..11 整数 + 12.5：方格里 12.5 那一格必须显示 12.5
    val half = (1..11).map { sort ->
        EpisodeBo(id = 1000 + sort, subjectId = 8, type = EpisodeType.NORMAL, name = "ep$sort", nameCn = "",
            sort = sort.toDouble(), airdate = "2026-09-01")
    } + EpisodeBo(id = 1012, subjectId = 8, type = EpisodeType.NORMAL, name = "ep12.5", nameCn = "",
        sort = 12.5, airdate = "2026-09-01")
    shoot("half_episode", Shot.Tiles(half, (1..11).associate { (1000 + it) to EpisodeStatus.DONE })) { shot = it }
}

/** 按下态：官方 Button 的状态层与形状形变。 */
@Test
fun 按下态落盘() {
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
        ChogumiTheme(darkTheme = false) {
            Surface(Modifier.fillMaxSize()) {
                PressedButton()
            }
        }
    }
    settle()
    saveScreenshot(FOLDER, "ep_button_pressed", capture())
}

/** 场景描述：切一次状态、拍一张图。 */
private sealed interface Shot {
    val dark: Boolean

    /** 章节方格（生产组件），什么都写不了。 */
    data class Tiles(
        val episodes: List<EpisodeBo>,
        val watchStates: Map<Int, EpisodeStatus>,
        val offerMarkDone: Boolean = false,
        override val dark: Boolean = false,
    ) : Shot

    data object Buttons : Shot { override val dark = false }

    /** 特别篇列表：type 4／5／6（预告、MAD、其他）必须各自有前缀、且不被脏数据冒充。 */
    data object Specials : Shot { override val dark = false }
}

@Composable
private fun Tiles(episodes: List<EpisodeBo>, watchStates: Map<Int, EpisodeStatus>, offerMarkDone: Boolean) {
    EpisodeTileGrid(
        subjectType = SubjectType.ANIME,
        episodes = episodes,
        watchStates = watchStates,
        today = today,
        pendingCommit = null,
        offerMarkDone = offerMarkDone,
        onWatchUpTo = gatedNoop1(),
        onToggleSpecial = gatedNoop1(),
        onMarkDone = gatedNoop(),
        onEpisodeLongPress = {},
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

/**
 * 按下态：向官方 Button 的 interactionSource 播一个 [PressInteraction.Press]。
 *
 * 为什么不用 `performTouchInput { down(center) }`：测试规则不会 hold 住手势，
 * 按下态在截图前就弹回了；直接播交互事件才是确定性的按下帧。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PressedButton() {
    val source = androidx.compose.runtime.remember { MutableInteractionSource() }
    val next = EpisodeTracking.epButtonState(episodes(), states(1, 2, 3, 4, 5), today)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        source.emit(PressInteraction.Press(androidx.compose.ui.geometry.Offset.Zero))
    }
    EpButton(
        state = next,
        onNext = {},
        onMarkFinished = {},
        showTarget = true,
        subjectType = SubjectType.ANIME,
        interactionSource = source,
        modifier = Modifier.padding(16.dp),
    )
}

/** EP 按钮各形态并排：可点 / 待提交 / 等待 / 完结。 */
@Composable
private fun EpButtonGallery() {
    Column(Modifier.padding(16.dp)) {
        val next = EpisodeTracking.epButtonState(episodes(), states(1, 2, 3, 4, 5), today)
        EpButton(
            state = next,
            onNext = {},
            onMarkFinished = {},
            showTarget = true,
            subjectType = SubjectType.ANIME,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        EpButton(
            state = next,
            onNext = {},
            onMarkFinished = {},
            showTarget = true,
            subjectType = SubjectType.ANIME,
            pending = EpPendingVisual(count = 3, commitAtNanos = 0L),
            modifier = Modifier.padding(vertical = 4.dp),
        )
        EpButton(
            state = EpButtonState.Waiting(episodes()[22], daysUntil = 5, position = 23, intervalDays = 7),
            onNext = {},
            onMarkFinished = {},
            showTarget = true,
            subjectType = SubjectType.ANIME,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        EpButton(
            state = EpButtonState.Finished,
            onNext = {},
            onMarkFinished = {},
            showTarget = true,
            subjectType = SubjectType.ANIME,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

/**
 * 特别篇方格：走生产组件 [EpisodesSection]（不是复刻），因此分组、过滤、文案
 * 全部是线上那一套。数据只放非正片 + 一条脏数据 `type = 99`。
 */
@Composable
private fun SpecialsGallery() {
    // 1..2 正片；SP/OP/ED/预告/MAD/其他 各一行；最后一条是脏数据
    val specials = listOf(
        EpisodeBo(id = 901, subjectId = 8, type = EpisodeType.SPECIAL, name = "sp1", nameCn = "特别篇一", sort = 1.0, airdate = "2026-09-01"),
        EpisodeBo(id = 902, subjectId = 8, type = EpisodeType.OP, name = "op1", nameCn = "片头曲", sort = 1.0, airdate = "2026-09-01"),
        EpisodeBo(id = 903, subjectId = 8, type = EpisodeType.ED, name = "ed1", nameCn = "片尾曲", sort = 1.0, airdate = "2026-09-01"),
        EpisodeBo(id = 904, subjectId = 8, type = EpisodeType.PRE, name = "pv1", nameCn = "预告片", sort = 2.0, airdate = "2026-09-01"),
        EpisodeBo(id = 905, subjectId = 8, type = EpisodeType.MAD, name = "mad1", nameCn = "MAD 作品", sort = 3.0, airdate = "2026-09-01"),
        EpisodeBo(id = 906, subjectId = 8, type = EpisodeType.OTHER, name = "etc1", nameCn = "其他影像", sort = 4.0, airdate = "2026-09-01"),
        // 脏数据：接口没给这个 type，必须**不出现**在列表里
        EpisodeBo(id = 907, subjectId = 8, type = 99, name = "junk", nameCn = "脏数据不该出现", sort = 5.0, airdate = "2026-09-01"),
    )
    EpisodesSection(
        subjectType = SubjectType.ANIME,
        episodes = AsyncList(Load.Ready(episodes(count = 2) + specials), onRetry = {}),
        // SP 看过（实心格），其余未看（描边格）
        watchStates = mapOf(901 to EpisodeStatus.DONE),
        today = today,
        expectedEpisodes = 2,
        pendingCommit = null,
        offerMarkDone = false,
        onWatchUpTo = gatedNoop1(),
        onToggleSpecial = gatedNoop1(),
        onMarkDone = gatedNoop(),
        onEpisodeLongPress = {},
    )
}

/** 切到 [shot] 场景、等稳定、落盘，再把状态交回调用方（供下一张切换）。 */
private fun shoot(
    name: String,
    next: Shot,
    apply: (Shot?) -> Unit,
) {
    apply(next)
    settle()
    saveScreenshot(FOLDER, "${if (next.dark) "dark" else "light"}_$name", capture())
}

/**
 * 等界面稳定：**不能用 `waitForIdle()`**——提交态的 Loading indicator 是
 * `withInfiniteAnimationFrameNanos` 驱动的无限动画，autoAdvance 下测试框架会一直等它空闲
 * （实测直接卡死、最终进程崩溃）。这里关掉自动推进，手动推几帧让布局与短动画落位
 * 避免截图采样受到加载环动画影响。
 */
private fun settle() {
    repeat(30) { composeRule.mainClock.advanceTimeBy(16) }
}

private fun capture(): Bitmap =
    composeRule.onRoot().captureToImage().asAndroidBitmap()

private companion object {
    const val FOLDER = "episode-tiles"
}
}
