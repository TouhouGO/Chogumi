package io.github.touhougo.chogumi.core.reader.reactionpoll

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.reader.ReactionGlyph
import io.github.touhougo.chogumi.core.reader.ReactionGrid
import io.github.touhougo.chogumi.core.reader.reactionDisplayOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.AppDropdownMenu
import io.github.touhougo.chogumi.core.ui.composables.FormTextField

/**
 * 编辑器里的投票卡（正文框下面）：问题框与右上角的「移除投票」，下面每个选项一行——左边是这个选项的贴贴表情（点开贴贴格子换一个，
 * 别的选项占着的变淡不能点），中间是选项文字，右边删掉这一项（少于 [REACTION_POLL_MIN_OPTIONS] 个时不能删）；最后是「添加选项」，
 * [palette] 里的表情用完就不能再加。改动经 [onChange] 交出新的投票，移除交出 null。
 * [enabled] 为 false（正在发送）时整张卡不接受改动，开着的贴贴格子也收起。
 */
@Composable
internal fun ReactionPollEditor(
    draft: ReactionPollDraft,
    palette: List<Int>,
    onChange: (ReactionPollDraft?) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            // 面板本身是 surfaceContainerLow（M3 底部面板），卡高一档才看得出是一张卡；里面的输入框再高一档
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .size(PollMarkSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.bar_chart_24), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            FormTextField(
                value = draft.question,
                onValueChange = { onChange(draft.copy(question = it)) },
                label = stringResource(R.string.reaction_poll_question),
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onChange(null) }, enabled = enabled) {
                Icon(painterResource(R.drawable.close_24), contentDescription = stringResource(R.string.reaction_poll_remove))
            }
        }
        draft.options.forEachIndexed { index, option ->
            // 一个选项占一个贴贴，贴贴就是它的身份：删掉前面的一项，后面各项的状态（贴贴格子开没开）跟着自己走
            key(option.reaction) {
                PollOptionEditor(
                    number = index + 1,
                    option = option,
                    palette = palette,
                    taken = draft.options.map { it.reaction }.toSet() - option.reaction,
                    canRemove = draft.options.size > REACTION_POLL_MIN_OPTIONS,
                    enabled = enabled,
                    onChange = { changed -> onChange(draft.copy(options = draft.options.mapIndexed { i, it -> if (i == index) changed else it })) },
                    onRemove = { onChange(draft.copy(options = draft.options.filterIndexed { i, _ -> i != index })) },
                )
            }
        }
        TextButton(onClick = { onChange(draft.withNewOption(palette)) }, enabled = enabled && draft.canAdd(palette)) {
            Icon(painterResource(R.drawable.add_24), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.reaction_poll_add_option))
        }
    }
}

/** 一个选项一行：表情按钮（点开贴贴格子换表情）、文字框、删除。 */
@Composable
private fun PollOptionEditor(
    number: Int,
    option: ReactionPollDraft.Option,
    palette: List<Int>,
    taken: Set<Int>,
    canRemove: Boolean,
    enabled: Boolean,
    onChange: (ReactionPollDraft.Option) -> Unit,
    onRemove: () -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            val smileyLabel = stringResource(R.string.reaction_poll_option_smiley, number)
            FilledTonalIconToggleButton(
                checked = picking,
                onCheckedChange = { picking = it },
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = smileyLabel },
            ) {
                Box(Modifier.size(PollSmileySize), contentAlignment = Alignment.Center) {
                    ReactionGlyph(reactionDisplayOf(option.reaction), PollSmileySize)
                }
            }
            AppDropdownMenu(expanded = picking && enabled, onDismissRequest = { picking = false }) {
                ReactionGrid(
                    palette = palette,
                    selected = option.reaction,
                    taken = taken,
                    onPick = { value ->
                        picking = false
                        value?.let { onChange(option.copy(reaction = it)) }
                    },
                )
            }
        }
        FormTextField(
            value = option.label,
            onValueChange = { onChange(option.copy(label = it)) },
            label = stringResource(R.string.reaction_poll_option, number),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, enabled = enabled && canRemove) {
            Icon(painterResource(R.drawable.remove_24), contentDescription = stringResource(R.string.reaction_poll_remove_option, number))
        }
    }
}

/** 编辑器里的投票随面板保存（转屏、进子页再回来都还在）：问题，然后每个选项的贴贴与文字；没有投票存空表。 */
internal val ReactionPollDraftSaver: Saver<ReactionPollDraft?, Any> = listSaver(
    save = { draft -> draft?.let { listOf<Any>(it.question) + it.options.flatMap { option -> listOf(option.reaction, option.label) } }.orEmpty() },
    restore = { saved ->
        saved.firstOrNull()?.let { question ->
            val options = (1 until saved.size step 2).map { i -> ReactionPollDraft.Option(saved[i] as Int, saved[i + 1] as String) }
            ReactionPollDraft(question as String, options)
        }
    },
)

// 投票卡的几何是原型值，我的设计决定（not in M3）：卡头的投票标记 40（同阅读端投票卡的图标按钮），选项表情 20（同阅读端选项）
private val PollMarkSize = 40.dp
private val PollSmileySize = 20.dp
