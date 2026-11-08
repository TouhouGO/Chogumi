package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import io.github.touhougo.chogumi.core.glass.GlassActionButton
import io.github.touhougo.chogumi.core.glass.GlassHeaderFrameState
import io.github.touhougo.chogumi.core.glass.GlassLayer
import io.github.touhougo.chogumi.core.glass.GlassTabs
import io.github.touhougo.chogumi.core.glass.GlassTabsHeight
import io.github.touhougo.chogumi.core.glass.rememberGlassHeaderFrameState
import io.github.touhougo.chogumi.core.glass.rememberGlassLayer
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo

/**
 * 作品详情的玻璃版外框（设置里的「玻璃效果」开着时，core:glass）：一层采样（头部与选项卡内容）与头部的收起状态。
 * 关着时 [rememberSubjectGlass] 是 null，页面画原来的顶栏、切换栏与 FAB，代码路径不变。
 */
@Stable
internal class SubjectGlass(val layer: GlassLayer, val frame: GlassHeaderFrameState)

@Composable
internal fun rememberSubjectGlass(): SubjectGlass? {
    val layer = rememberGlassLayer() ?: return null
    val frame = rememberGlassHeaderFrameState()
    return remember(layer, frame) { SubjectGlass(layer, frame) }
}

/**
 * 玻璃版的选项卡切换：钉住的玻璃胶囊（[GlassTabs]），每格只写文字、选中格主色加粗，选中的那滴在格间滑动。
 * 和 [SubjectDetailsTabGroup] 一样只渲染 [visibleSectionsFor] 的结果。
 */
@Composable
internal fun GlassSubjectTabs(
    layer: GlassLayer,
    sections: List<SubjectDetailsSection>,
    selected: SubjectDetailsSection,
    onSelect: (SubjectDetailsSection) -> Unit,
) {
    GlassTabs(
        layer = layer,
        options = sections,
        selected = selected,
        onSelect = onSelect,
        height = GlassTabsHeight,
        modifier = Modifier.testTag(DetailsTabsTag),
    ) { section, isSelected ->
        Text(
            text = stringResource(section.titleRes),
            style = if (isSelected) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * 玻璃版的收藏按钮：带色玻璃胶囊（[GlassActionButton]），里面是和 [CollectionFab] 同一行内容（[CollectionFabLabel]），
 * 忙时同样读屏念「不可用」、点击由 ViewModel 按 busy 判定不响应。
 */
@Composable
internal fun GlassCollectionButton(
    layer: GlassLayer,
    state: CollectionFabState,
    collection: UserCollectionBo?,
    subjectType: SubjectType?,
    onClick: () -> Unit,
) {
    GlassActionButton(
        layer = layer,
        onClick = onClick,
        modifier = if (state.busy) Modifier.semantics { disabled() } else Modifier,
    ) {
        CollectionFabLabel(state, collection, subjectType)
    }
}
