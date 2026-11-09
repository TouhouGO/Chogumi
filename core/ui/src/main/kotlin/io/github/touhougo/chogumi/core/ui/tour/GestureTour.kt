package io.github.touhougo.chogumi.core.ui.tour

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.TabPager
import io.github.touhougo.chogumi.core.ui.composables.rememberTabPagerState
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

/** 引导的四页，顺序即翻页顺序：每页一处只靠长按才找得到的操作。 */
private enum class TourPage(@StringRes val title: Int, @StringRes val text: Int) {
    MARK(R.string.gesture_tour_mark_title, R.string.gesture_tour_mark_text),
    EPISODE(R.string.gesture_tour_episode_title, R.string.gesture_tour_episode_text),
    FLOOR(R.string.gesture_tour_floor_title, R.string.gesture_tour_floor_text),
    ARRANGE(R.string.gesture_tour_arrange_title, R.string.gesture_tour_arrange_text),
}

/** 宽屏上引导的内容最宽这么多、居中：演示台是正方形，再宽就高过一屏（设计决定，not in M3）。 */
private val TourMaxWidth = 480.dp

/** 页码点：一个点的直径、当前页拉成的长度、点与点的间隔（设计决定，not in M3：M3 没有页码指示器）。 */
private val DotSize = 6.dp
private val DotCurrentWidth = 18.dp
private val DotGap = 6.dp

/**
 * 手势引导：只靠长按才找得到的几处（封面长按环、按住一集看讨论、楼层长按菜单、整理探索页），各一页会动的演示，
 * 演示只在翻到那一页、停稳时放（[TabPager] 的当前页）。第一次打开与升级之后由外壳自动放一次，设置的「使用技巧」重看。
 *
 * 版式按 M3 的引导流程：左右滑或点「下一步」（tonal 按钮，buttons.md：onboarding 的 Next）翻页，最后一页是「开始使用」（filled），
 * 右上角「跳过」（text 按钮）随时结束，最后一页不再有。「跳过」「开始使用」都调 [onDone]；返回键由放它的地方接。
 * 自己留出系统栏；宽屏上不宽过 [TourMaxWidth]、居中。页内容放不下（横屏、矮窗口）时竖向滚动，按钮一直在底下。
 */
@Composable
fun GestureTour(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val pages = TourPage.entries
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val pager = rememberTabPagerState(selected, pages.size) { selected = it }
    val shown = pager.shownPage
    val last = shown == pages.lastIndex
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = TourMaxWidth).fillMaxHeight()) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = LocalMinimumInteractiveComponentSize.current).padding(end = 8.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    if (!last) TextButton(onClick = onDone) { Text(stringResource(R.string.gesture_tour_skip)) }
                }
                TabPager(pager, Modifier.weight(1f), key = { pages[it].name }) { index ->
                    TourPageContent(pages[index], playing = shown == index)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = ChogumiDimens.ScreenPadding * 1.5f, vertical = ChogumiDimens.ScreenPadding),
                ) {
                    TourDots(count = pages.size, current = shown)
                    Spacer(Modifier.weight(1f))
                    if (last) {
                        Button(onClick = onDone) { Text(stringResource(R.string.gesture_tour_start)) }
                    } else {
                        FilledTonalButton(onClick = { selected = shown + 1 }) { Text(stringResource(R.string.gesture_tour_next)) }
                    }
                }
            }
        }
    }
}

/** 一页：演示台，下面是标题（headlineSmall）与一句说明（bodyLarge、onSurfaceVariant）。 */
@Composable
private fun TourPageContent(page: TourPage, playing: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ChogumiDimens.ScreenPadding),
    ) {
        when (page) {
            TourPage.MARK -> MarkDemo(playing)
            TourPage.EPISODE -> EpisodeDemo(playing)
            TourPage.FLOOR -> FloorDemo(playing)
            TourPage.ARRANGE -> ArrangeDemo(playing)
        }
        Text(
            text = stringResource(page.title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 24.dp),
        )
        Text(
            text = stringResource(page.text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = ChogumiDimens.ScreenPadding),
        )
    }
}

/** 页码点：当前页拉长、primary，其余 outlineVariant；翻页时长度走 spatial、颜色走 effects。读屏不念（每页的标题已经说了在哪）。 */
@Composable
private fun TourDots(count: Int, current: Int) {
    val motion = MaterialTheme.motionScheme
    Row(horizontalArrangement = Arrangement.spacedBy(DotGap), modifier = Modifier.clearAndSetSemantics {}) {
        repeat(count) { index ->
            val on = index == current
            val width by animateDpAsState(if (on) DotCurrentWidth else DotSize, motion.fastSpatialSpec(), label = "tour_dot_width")
            val color by animateColorAsState(
                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                motion.fastEffectsSpec(),
                label = "tour_dot_color",
            )
            Box(Modifier.size(width = width, height = DotSize).background(color, CircleShape))
        }
    }
}
