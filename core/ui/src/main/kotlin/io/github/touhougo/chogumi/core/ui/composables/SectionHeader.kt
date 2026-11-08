package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.res.R

/**
 * 区块标题行的**框架**——[SectionHeader] 与它的骨架 [SectionHeaderSkeleton] 共用的唯一出处：
 * 内边距（左右 16dp、上下 12dp）、垂直居中、标题与副标题的字阶都只写在这里。
 * [title] / [subtitle] 收到框架定的字阶；[trailing] 为 null 则行尾不占位。
 */
@Composable
private fun SectionHeaderFrame(
    title: @Composable (TextStyle) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: (@Composable (TextStyle) -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            title(MaterialTheme.typography.titleMedium)
            subtitle?.invoke(MaterialTheme.typography.bodySmall)
        }
        trailing?.invoke()
    }
}

/**
 * 区块标题。不显示无法执行的尾部动作。
 * 尾部三选一：文字按钮（[trailingText] + [onTrailingClick]）、进入完整列表页的箭头（[onArrowClick]），
 * 或调用方自带的控件 [trailing]（如吐槽箱的状态筛选 [MenuChip]）。
 * [count] 跟在标题后面，用 `onSurfaceVariant`（原型 `.sech` 的「关联作品 19」）。
 * [subtitle] 是副标题的各段，多段时用 [MetaText] 连成一行。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: List<String>? = null,
    count: Int? = null,
    trailingText: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    onArrowClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val countColor = MaterialTheme.colorScheme.onSurfaceVariant
    SectionHeaderFrame(
        modifier = modifier,
        title = { style ->
            Text(
                text = buildAnnotatedString {
                    append(title)
                    if (count != null) {
                        append(' ')
                        withStyle(SpanStyle(color = countColor)) { append(count.toString()) }
                    }
                },
                style = style,
            )
        },
        subtitle = subtitle?.let { parts ->
            { style -> MetaText(parts = parts, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        trailing = when {
            trailing != null -> trailing
            trailingText != null && onTrailingClick != null -> {
                {
                    TextButton(onClick = onTrailingClick, modifier = Modifier.testTag("section_header_trailing")) {
                        Text(trailingText)
                    }
                }
            }
            onArrowClick != null -> {
                { SeeAllArrowButton(onClick = onArrowClick) }
            }
            else -> null
        },
    )
}

/**
 * 区块标题的骨架：与 [SectionHeader] 同一个 [SectionHeaderFrame]，所以行高、内边距、字阶行高都一样。
 *
 * 真实标题有的东西骨架要如实声明，才能把布局空间留出来：
 * - [hasTrailingAction]：真实标题行尾有文字按钮（如「全部」）。按钮的布局高度含 48dp 触控区，
 *   比标题文字高，不留的话内容一出现整个区块就被撑高、下面的东西全部下移。这里放一个透明、禁用的真
 *   [TextButton] 占位（走与真按钮相同的布局路径，随字号缩放），不画任何东西；
 */
@Composable
fun SectionHeaderSkeleton(
    modifier: Modifier = Modifier,
    hasTrailingAction: Boolean = false,
) {
    SectionHeaderFrame(
        modifier = modifier,
        title = { style -> SkeletonText(style, widthFraction = 0.25f) },
        trailing = if (hasTrailingAction) {
            {
                TextButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.alpha(0f).clearAndSetSemantics {},
                ) { Text(SkeletonTextSample) }
            }
        } else {
            null
        },
    )
}

/**
 * 区块标题行尾的「查看全部」箭头：进入该区块的完整纵向列表页。
 * From M3：横向滚动内容带标题时，在标题同一行放箭头图标按钮打开完整列表页（containment.md:201、231）。
 * My design decision (not in M3)：用默认 IconButton（Small 档 40dp 容器、24dp 图标，component-tokens.md:314-323），
 * 不取 containment.md:201 的「48dp 图标」——该值与图标按钮尺寸体系不自洽。
 */
@Composable
private fun SeeAllArrowButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier.testTag("section_header_arrow")) {
        Icon(
            painter = painterResource(R.drawable.arrow_forward_24),
            contentDescription = stringResource(R.string.view_all),
        )
    }
}
