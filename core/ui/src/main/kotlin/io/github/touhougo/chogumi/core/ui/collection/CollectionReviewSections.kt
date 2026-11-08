package io.github.touhougo.chogumi.core.ui.collection

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.CollectionInput
import io.github.touhougo.chogumi.core.model.formatAbbreviatedCount
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.ExpandReveal
import io.github.touhougo.chogumi.core.ui.composables.FormTextField
import io.github.touhougo.chogumi.core.ui.composables.RatingTiles
import io.github.touhougo.chogumi.core.ui.composables.RollingText
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

/* 收藏面板（[CollectionSheet]）里对作品的评价：评分条、标签（含就地校验的输入框）、吐槽。 */

/** 评分：一行「评分」与大字分数和 Bangumi 的评语（8 力荐），下面十格评分条。不显示大众均分，免得影响打分。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RatingSection(rate: Int, onChange: (Int) -> Unit) {
    val levels = stringArrayResource(R.array.rating_level_names)
    val word = levels.getOrNull(rate - 1)
    SectionLabel(R.string.collection_rating) {
        if (word != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(RatingReadoutTag)) {
                RollingText(rate.toString(), order = rate, style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.alignByBaseline())
                Text(word, style = MaterialTheme.typography.titleSmall, modifier = Modifier.alignByBaseline())
            }
        } else {
            Text(stringResource(R.string.profile_stats_unrated), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    RatingTiles(
        value = rate,
        onValueChange = onChange,
        description = stringResource(R.string.collection_rating),
        stateDescription = if (word != null) stringResource(R.string.collection_rating_state, rate, word) else stringResource(R.string.profile_stats_unrated),
        modifier = Modifier.padding(horizontal = ChogumiDimens.ScreenPadding),
    )
}

/**
 * 标签：我的标签是输入 chip（整颗点了就删，chips：只有删除一个动作时整颗是一个可点元素）；最后一颗是输入框，
 * 打空格或回车就成一个。下面一排「大家常用」是建议 chip（带人数），点了加进来。规矩照服务端（[CollectionInput]）：
 * 最多 10 个、每个至少两个字，不合规矩的就地说明，不等保存时被服务端拒。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TagsSection(tags: List<String>, suggestions: List<Pair<String, Int>>, input: TextFieldState, onChange: (List<String>) -> Unit) {
    var problem by remember { mutableStateOf<CollectionInput.TagProblem?>(null) }
    val currentTags by rememberUpdatedState(tags)
    val change by rememberUpdatedState(onChange)
    // 加标签只经这里：拆分、归一、查重、限数都在 CollectionInput。加不上的（太短、已满）留下来，还给输入框接着改
    fun add(raw: String): String {
        var next = currentTags
        val rejected = mutableListOf<String>()
        for (tag in CollectionInput.tagsIn(raw)) {
            when (val found = CollectionInput.tagProblem(next, tag)) {
                null -> next = next + tag
                CollectionInput.TagProblem.DUPLICATE -> Unit
                else -> {
                    problem = found
                    rejected += tag
                }
            }
        }
        if (next != currentTags) change(next)
        return rejected.joinToString(" ")
    }
    // 这里替用户改写的输入框内容：改写引起的那次变化不算用户在打字，不清掉刚说的问题
    val rewrite = remember { arrayOfNulls<String>(1) }
    fun leave(text: String) {
        rewrite[0] = text
        input.setTextAndPlaceCursorAtEnd(text)
    }
    // 打了空格：空格前的成标签，后面的留着继续打
    LaunchedEffect(input) {
        snapshotFlow { input.text.toString() }.collect { typed ->
            if (typed == rewrite[0]) {
                rewrite[0] = null
                return@collect
            }
            problem = null
            val cut = typed.indexOfLast { it.isWhitespace() }
            if (cut >= 0) leave(add(typed.substring(0, cut)) + typed.substring(cut + 1))
        }
    }
    val full = tags.size >= CollectionInput.MAX_TAGS
    SectionLabel(R.string.tags) {
        Text(
            stringResource(R.string.collection_tags_count, tags.size, CollectionInput.MAX_TAGS),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val removeLabel = stringResource(R.string.delete)
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ChogumiDimens.ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(ChogumiDimens.ChipSpacing),
        verticalArrangement = Arrangement.spacedBy(ChogumiDimens.ChipSpacing),
    ) {
        tags.forEach { tag ->
            // 整颗是一个按钮（chips：只有删除一个动作时整颗与删除图标是一个可聚焦元素），读屏念标签名，动作是「删除」
            InputChip(
                selected = true,
                onClick = { onChange(tags - tag) },
                label = { Text(tag) },
                trailingIcon = { Icon(painterResource(R.drawable.close_24), contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize)) },
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = tag
                    role = Role.Button
                    onClick(label = removeLabel) {
                        onChange(tags - tag)
                        true
                    }
                },
            )
        }
        if (!full) AddTagField(state = input, onDone = { leave(add(input.text.toString())) })
    }
    val hint = when (problem) {
        CollectionInput.TagProblem.TOO_SHORT -> stringResource(R.string.collection_tag_too_short, CollectionInput.MIN_TAG_LENGTH)
        CollectionInput.TagProblem.FULL -> stringResource(R.string.collection_tags_full, CollectionInput.MAX_TAGS)
        CollectionInput.TagProblem.DUPLICATE, null -> null
    }
    ExpandReveal(hint != null) {
        Text(
            text = hint.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = ChogumiDimens.ScreenPadding, vertical = 4.dp),
        )
    }
    if (suggestions.isNotEmpty()) {
        Text(
            stringResource(R.string.collection_tags_suggested),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = ChogumiDimens.ScreenPadding, end = ChogumiDimens.ScreenPadding, top = 12.dp, bottom = 4.dp),
        )
        val locale = LocalConfiguration.current.locales[0]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = ChogumiDimens.ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(ChogumiDimens.ChipSpacing),
        ) {
            suggestions.forEach { (tag, count) ->
                SuggestionChip(
                    onClick = { add(tag) },
                    enabled = tag !in tags && !full,
                    label = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(tag, Modifier.alignByBaseline())
                            Text(formatAbbreviatedCount(count, locale), Modifier.alignByBaseline(), style = MaterialTheme.typography.labelSmall)
                        }
                    },
                )
            }
        }
    }
}

/**
 * 加标签的输入框，样子是一颗 chip（高 32dp、small 圆角、outline variant 描边，chips 的尺寸）：前面一个加号，
 * 空着时写「添加标签」。回车加上；打空格加上空格前的那段（见 [TagsSection]）。
 * 和 [InputChip] 一样占 48dp 的最小触控高度、可见的 chip 居中其中，排在同一行里才与标签芯片上下对齐。
 *
 * 整颗 chip 就是输入框本身：描边、加号与占位字都画在输入框的装饰（`decorator`）里，点加号也是点输入框，
 * 读屏只看到一个编辑框，占位字「添加标签」并在它的语义里当标签；外面没有第二个可点、可聚焦的元素。
 */
@Composable
private fun AddTagField(state: TextFieldState, onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        state = state,
        textStyle = MaterialTheme.typography.labelLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        onKeyboardAction = { onDone() },
        decorator = { innerTextField ->
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(painterResource(R.drawable.add_24), contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize), tint = colors.onSurfaceVariant)
                Box(modifier = Modifier.widthIn(min = TagFieldMinWidth), contentAlignment = Alignment.CenterStart) {
                    if (state.text.isEmpty()) {
                        Text(stringResource(R.string.collection_tag_add), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    }
                    innerTextField()
                }
            }
        },
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = InputChipDefaults.Height)
            .border(ChipBorder, colors.outlineVariant, MaterialTheme.shapes.small)
            .testTag(CollectionTagFieldTag),
    )
}

/** 吐槽：多行输入框，下面「n / 380」（有上限就显示计数）。字数按字符算，与服务端一致，到 380 再打不进去。 */
@Composable
internal fun CommentField(state: TextFieldState) {
    val length = CollectionInput.length(state.text)
    FormTextField(
        state = state,
        label = stringResource(R.string.comment),
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = COMMENT_MIN_LINES),
        inputTransformation = CommentLimit,
        supportingText = {
            Text(
                stringResource(R.string.text_length_count, length, CollectionInput.MAX_COMMENT),
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ChogumiDimens.ScreenPadding, end = ChogumiDimens.ScreenPadding, top = 24.dp)
            .testTag(CollectionCommentTag),
    )
}

/** 区块的小标题（「评分」「标签」），右边可带一段读数。 */
@Composable
private fun SectionLabel(@StringRes title: Int, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ChogumiDimens.ScreenPadding, end = ChogumiDimens.ScreenPadding, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** 吐槽超过 [CollectionInput.MAX_COMMENT] 个字的这次改动不收（按字符数，不按 UTF-16 长度）。 */
private val CommentLimit = InputTransformation {
    if (CollectionInput.length(asCharSequence()) > CollectionInput.MAX_COMMENT) revertAllChanges()
}

/** chip 的描边（component-tokens.md InputChip：UnselectedOutlineWidth 1dp）。 */
private val ChipBorder = 1.dp

/** 加标签输入框至少多宽：放得下「添加标签」（设计稿 `.chip input{width:96px}`，not in M3）。 */
private val TagFieldMinWidth = 96.dp

/** 吐槽框至少几行高（设计稿 `textarea{min-height:88px}`，not in M3）。 */
private const val COMMENT_MIN_LINES = 3
