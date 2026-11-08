package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.res.R

/**
 * contained 样式的搜索输入框（input.md:788「Do use the contained style (recommended); divided is not recommended
 * in M3 Expressive」）。进度页与搜索页共用，外观只在这里定义。
 *
 * From M3（input.md「Search」）：容器高 56dp；前置位放导航图标按钮或不可点的搜索图标（input.md:693）；
 * 后置最多两个图标（input.md:694）。
 * 容器形状沿用探索页入口的 extraLarge、颜色 surfaceContainerHigh，保证三处搜索外观一致
 * （M3 规范为 CornerFull——此处与探索入口保持一致，属 my design decision (not in M3)）。
 *
 * 提示文字只占一行（M3 input.md「Hinted search text」是一句简短说明；窄屏上不能折成两行）：
 * 放不下时只缩短 [placeholderValue] 那一段（如标签名，尾部加「…」），句子的其余部分保持完整，判定见 [fitPlaceholder]。
 *
 * @param placeholderValue [placeholder] 里可缩短的那一段；null 时整句放不下就在句尾省略。
 * @param onClear 点内置清除按钮（输入非空时出现在后置位最前）；默认把输入清空。
 * @param leading 前置位；默认是不可点的搜索图标。
 * @param trailing 后置位图标按钮；加上内置清除按钮不超过两个。
 * @param fieldModifier 作用在输入框本身（testTag、语义动作都挂在这里）。
 */
@Composable
fun ContainedSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    placeholderValue: String? = null,
    fieldModifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    onClear: () -> Unit = { onQueryChange("") },
    leading: @Composable () -> Unit = { DecorativeSearchIcon() },
    trailing: @Composable RowScope.() -> Unit = {},
) {
    SearchContainer(modifier) {
        leading()
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier.weight(1f).padding(end = 8.dp).then(fieldModifier),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    SingleLinePlaceholder(placeholder, placeholderValue)
                }
                inner()
            },
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = onClear, modifier = Modifier.testTag(SEARCH_CLEAR_TAG)) {
                Icon(
                    painter = painterResource(R.drawable.close_24),
                    contentDescription = stringResource(R.string.search_clear_query),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

/** 搜索框内置清除按钮的 testTag。 */
const val SEARCH_CLEAR_TAG = "SearchClearQuery"

/**
 * 点一下去别处搜索的入口：外观与 [ContainedSearchField] 完全一致（同一个容器、同一行提示），只是整块可点、不输入。
 * 提示文字同样只占一行，放不下就在句尾省略。
 */
@Composable
fun ContainedSearchEntry(
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholderValue: String? = null,
) {
    SearchContainer(modifier, onClick) {
        DecorativeSearchIcon()
        Box(Modifier.weight(1f).padding(end = 8.dp)) { SingleLinePlaceholder(placeholder, placeholderValue) }
    }
}

/** 输入框与入口共用的容器：extraLarge 形状、surfaceContainerHigh 底、56dp 高。[onClick] 为 null 时不可点。 */
@Composable
private fun SearchContainer(
    modifier: Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier.height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
    val shape = MaterialTheme.shapes.extraLarge
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    if (onClick == null) {
        Surface(shape = shape, color = color, modifier = modifier.fillMaxWidth(), content = body)
    } else {
        Surface(onClick = onClick, shape = shape, color = color, modifier = modifier.fillMaxWidth(), content = body)
    }
}

/** 不可点的前置搜索图标：与 48dp 图标按钮占同样宽度，前置位换成按钮时文字起点不跳。 */
@Composable
private fun DecorativeSearchIcon() {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.search_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** 一行的提示文字：按可用宽度用 [fitPlaceholder] 缩短 [value] 那一段。 */
@Composable
private fun SingleLinePlaceholder(text: String, value: String?) {
    val style = MaterialTheme.typography.bodyLarge
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
        val maxWidth = constraints.maxWidth
        val fitted = remember(text, value, maxWidth, style) {
            fitPlaceholder(text, value, maxWidth) { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width }
        }
        Text(
            text = fitted,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
