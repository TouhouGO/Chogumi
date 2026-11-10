package io.github.touhougo.chogumi.core.ui.arrange

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.PageModalSheet
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.composables.SheetSectionTitle
import io.github.touhougo.chogumi.core.ui.composables.SheetTitleRow
import io.github.touhougo.chogumi.core.ui.composables.SkeletonSketch

/** 编辑面板标题行下面的内容：可滚（面板顶边固定，内容比面板高就在里面滚）。 */
@Composable
fun EditorBody(content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
    ) { content() }
}

@Composable
fun EditorSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetSectionTitle(title)
        content()
    }
}

/** 「移除货架」（= 隐藏，「添加货架」里还能加回来）：error 色的文字按钮，放在面板最下面。 */
@Composable
fun RemoveEntryButton(label: String, onClick: () -> Unit, enabled: Boolean, tag: String) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.testTag(tag),
    ) {
        Icon(painterResource(R.drawable.delete_24), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Text(label, modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
    }
}

/**
 * 一排可横滑的样式（或画法）选项：每格是那种画法本身缩小的样子（[StylePreview]）加名字，选中的描主色边。
 * 横滑而不是网格：八种样式在任何宽度下都不会在末行落单。格宽、缩略图高是我的设计决定（not in M3）。
 */
@Composable
fun <T> StyleOptions(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    tagPrefix: String = "shelf_style",
    sketch: Boolean = true,
    preview: @Composable (T) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items(options, key = { it.toString() }) { option ->
            val on = option == selected
            val shape = MaterialTheme.shapes.large
            PressArea(
                onClick = { onSelect(option) },
                shape = shape,
                role = Role.RadioButton,
                modifier = Modifier
                    .width(StyleOptionWidth)
                    .semantics { this.selected = on }
                    .testTag("${tagPrefix}_$option"),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer, shape)
                        .border(2.dp, if (on) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
                        .padding(8.dp),
                ) {
                    StylePreview(Modifier.fillMaxWidth().height(StylePreviewHeight), sketch) { preview(option) }
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * 一种画法的缩略图：把真的画法（调用方的作图组件，样式与画法的唯一分派）按 [PreviewVirtualWidth] 的宽、
 * 与页面上一样竖着排好（封面流的说明行在封面下面），
 * 整体缩到这一格的宽，顶对齐、超出的高度裁掉。作品还没有时画的是骨架，[SkeletonSketch] 把它画成主题色的色块与字条，
 * 缩小了也看得出每种版式。只是看的：上面盖一层接住触摸的透明层，里面的横滑与点击都收不到；不进读屏。
 */
@Composable
private fun StylePreview(modifier: Modifier, sketch: Boolean, content: @Composable () -> Unit) {
    Box(modifier.clipToBounds()) {
        Layout(content = { Column { if (sketch) SkeletonSketch(content) else content() } }, modifier = Modifier.clearAndSetSemantics {}) { measurables, constraints ->
            val virtual = PreviewVirtualWidth.roundToPx()
            val placeable = measurables.first().measure(Constraints(minWidth = virtual, maxWidth = virtual))
            val scale = constraints.maxWidth.toFloat() / virtual
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.placeWithLayer(0, 0) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }
        Box(Modifier.matchParentSize().pointerInput(Unit) { awaitEachGesture { while (true) awaitPointerEvent() } })
    }
}

/** 缩略图按多宽的屏排：一部手机的宽，缩下来正好看得出一行几张、什么形状。 */
private val PreviewVirtualWidth = 360.dp
private val StyleOptionWidth = 112.dp
private val StylePreviewHeight = 64.dp

/** 编辑面板的统一标题与可滚动正文，保存按钮和领域字段经槽位提供。 */
@Composable
fun ArrangeEditorSheet(title: String, hideRequested: Boolean, onDismiss: () -> Unit,
    action: @Composable () -> Unit, snackbar: SnackbarHostState? = null, content: @Composable () -> Unit) {
    PageModalSheet(onDismiss = onDismiss, fullHeight = false, hideRequested = hideRequested, snackbar = snackbar) {
        SheetTitleRow(title) { action() }
        EditorBody(content)
    }
}

/** 添加面板的列表骨架，两类调用方提供逐行领域选项。 */
@Composable
fun ArrangeAddSheet(title: String, hideRequested: Boolean, onDismiss: () -> Unit,
    action: @Composable () -> Unit, tag: String, snackbar: SnackbarHostState? = null, content: LazyListScope.() -> Unit) {
    PageModalSheet(onDismiss = onDismiss, fullHeight = true, hideRequested = hideRequested, snackbar = snackbar) {
        SheetTitleRow(title) { action() }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxWidth().testTag(tag), content = content)
    }
}

/** 添加目录的一行，不再在调用方拼第二种列表行。 */
@Composable
fun ArrangeChoiceRow(index: Int, count: Int, title: String, supporting: String?, onClick: () -> Unit,
    modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    SegmentedItem(index = index, count = count, onClick = onClick, leadingContent = leading,
        trailingContent = trailing, supportingContent = supporting?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        modifier = modifier) { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}
