package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.ui.composables.ConnectedButtonGroup
import io.github.touhougo.chogumi.core.ui.composables.ConnectedButtonGroupSkeleton

/**
 * 选项卡切换栏的**外框**——真实切换栏与骨架共用的唯一出处：整行的内边距只写在这里。
 * 原型 `.tabs{padding:12px 16px}`：上下 12 里有 4dp 已由按钮 40dp 容器外的 48dp 触控区给出，这里只补 8。
 * 按钮间距：真实切换栏由 [ConnectedButtonGroup] 给（`ButtonGroupDefaults.ConnectedSpaceBetween`），骨架用同一个值。
 */
private fun Modifier.tabGroupFrame(): Modifier = this
    .testTag(DetailsTabsTag)
    .fillMaxWidth()
    .padding(horizontal = 16.dp, vertical = 8.dp)

/** 选中格相对其余格的宽度（原型 `.cbg button[aria-pressed]{flex-grow:2.2}`；设计决定，not in M3）。 */
private const val SELECTED_TAB_WEIGHT = 2.2f

/**
 * 条目详情的选项卡切换栏：连通按钮组，走 core:ui 的 [ConnectedButtonGroup]
 * （全仓单选分段的唯一骨架：形状、配色、按压弹性只此一处）。
 * 每格是图标，**只有选中格**伸展并带上文字（原型 `.cbg .lb`）；读屏始终念完整标签。
 * 有哪些格由 [visibleSectionsFor] 唯一决定，这里只渲染。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SubjectDetailsTabGroup(
    sections: List<SubjectDetailsSection>,
    selected: SubjectDetailsSection,
    onSelect: (SubjectDetailsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val titleStyle = MaterialTheme.typography.labelLargeEmphasized
    val selectedTitle = stringResource(selected.titleRes)
    BoxWithConstraints(modifier.tabGroupFrame()) {
        // 选中格放得下文字才写；窄屏、大字号下放不下就只留图标（读屏始终念完整标签），不把文字裁成半截
        val showTitle = with(LocalDensity.current) {
            val gaps = ButtonGroupDefaults.ConnectedSpaceBetween.roundToPx() * (sections.size - 1)
            val selectedWidth = (constraints.maxWidth - gaps) * SELECTED_TAB_WEIGHT / (sections.size - 1 + SELECTED_TAB_WEIGHT)
            val room = selectedWidth - (TAB_PADDING * 2 + TAB_ICON + TAB_ICON_GAP).roundToPx()
            measurer.measure(selectedTitle, titleStyle, maxLines = 1, softWrap = false).size.width <= room
        }
        ConnectedButtonGroup(
            options = sections,
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = TAB_PADDING),
            selectedWeight = SELECTED_TAB_WEIGHT,
        ) { section, checked ->
            TabCell(section, showTitle && checked, titleStyle)
        }
    }
}

@Composable
private fun TabCell(section: SubjectDetailsSection, showTitle: Boolean, titleStyle: TextStyle) {
    val title = stringResource(section.titleRes)
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = title },
        horizontalArrangement = Arrangement.spacedBy(TAB_ICON_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(section.iconRes),
            contentDescription = null,
            modifier = Modifier.size(TAB_ICON),
        )
        if (showTitle) {
            Text(text = title, style = titleStyle, maxLines = 1)
        }
    }
}

/** 选项卡格内的左右内边距、图标边长、图标与文字的间距。 */
private val TAB_PADDING = 8.dp
private val TAB_ICON = 20.dp
private val TAB_ICON_GAP = 6.dp

/**
 * 切换栏的骨架：与 [SubjectDetailsTabGroup] 同一个外框（[tabGroupFrame]）、同一个按钮间距。
 *
 * 骨架阶段拿不到条目，选项卡数按「条目未知」画——同样经 [visibleSectionsFor]（唯一判定），
 * 不另写一份「几个选项卡」的规则；游戏少一个第 2 个 tab、人物为空时少人物，只影响按钮宽度。
 *
 * 占位走 core:ui 的 [ConnectedButtonGroupSkeleton]（按钮容器高、48dp 触控区都在那里）。
 */
@Composable
internal fun SubjectDetailsTabGroupSkeleton(
    features: AppFeatures,
    modifier: Modifier = Modifier,
) {
    // 骨架阶段选中的是首格（概览），与真实切换栏同样伸展
    ConnectedButtonGroupSkeleton(
        weights = visibleSectionsFor(null, features).indices.map { if (it == 0) SELECTED_TAB_WEIGHT else 1f },
        modifier = modifier.tabGroupFrame(),
    )
}
