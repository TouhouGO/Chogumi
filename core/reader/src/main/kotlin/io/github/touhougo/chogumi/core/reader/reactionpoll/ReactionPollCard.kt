package io.github.touhougo.chogumi.core.reader.reactionpoll

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeNode
import io.github.touhougo.chogumi.core.model.CommentReactionBo
import io.github.touhougo.chogumi.core.reader.BBCodeBlockContent
import io.github.touhougo.chogumi.core.reader.BBCodeBlockPlugin
import io.github.touhougo.chogumi.core.reader.SmileyImage
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.RollingText
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import kotlin.math.roundToInt

/**
 * 贴贴投票插件：绑着一楼的贴贴（[reactions]、能贴的 [palette]）与贴贴动作（[onVote]，null = 取消；
 * null 时只读），把这楼正文里的投票块画成投票卡。投给谁、几票都从这楼的贴贴来，与下面的贴贴条是同一份数据。
 */
data class ReactionPollPlugin(
    val palette: List<Int>,
    val reactions: List<CommentReactionBo>,
    val onVote: ((Int?) -> Unit)?,
) : BBCodeBlockPlugin {

    override fun claim(list: BBCodeNode.ListBlock): BBCodeBlockContent? =
        reactionPollOf(list, palette)?.let { ReactionPollBlock(it, it.tallyOf(reactions), onVote) }
}

private data class ReactionPollBlock(
    val poll: ReactionPoll,
    val tally: ReactionPollTally,
    val onVote: ((Int?) -> Unit)?,
) : BBCodeBlockContent {

    @Composable
    override fun Content() = ReactionPollCard(poll, tally, onVote)
}

/**
 * 投票卡：问题在上，每个选项一整行可点（左边是它的表情，点了就是贴这个表情）；这里贴不了的选项照常列出、显示票数，只是不能点。
 * 没投过默认只列选项，投过默认是结果：每行右侧百分比、下面一条按票数比例的条；标题行起始的切换按钮随时在两者间切换。
 * 我投的那项底色 `primaryContainer`、表情角上带勾；点别的项是改投，点我投的那项是撤票。
 *
 * **卡片的宽高在任何状态下都不变**，变的只有选项里的条与数字：百分比、比例条、勾的位置一直占着（没有时不画），
 * 切换按钮一直在同一处，总票数一直在底部；选中只换颜色不换字重（加粗会让文字折行变化）。
 * 几何是设计决定（not in M3）：行高至少 48（触控目标），行圆角 medium，卡片 `surfaceContainerLow`、圆角 large、内边距 16。
 */
@Composable
internal fun ReactionPollCard(
    poll: ReactionPoll,
    tally: ReactionPollTally,
    onVote: ((Int?) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val voted = tally.mine != null
    // 用户切过就听用户的；没切过跟着投没投（投了直接看结果，撤票回到选项）。切换连同当时投没投一起记，
    // 投票状态变了那次切换就作废：输入不进 rememberSaveable 的键，离开组合期间变了也不会把旧的恢复回来
    var toggle by rememberSaveable(stateSaver = ResultsToggleSaver) { mutableStateOf<ResultsToggle?>(null) }
    val showResults = toggle?.takeIf { it.whenVoted == voted }?.show ?: voted
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val toggleLabel = stringResource(if (showResults) R.string.reaction_poll_hide_results else R.string.reaction_poll_show_results)
            FilledTonalIconToggleButton(checked = showResults, onCheckedChange = { toggle = ResultsToggle(whenVoted = voted, show = it) }) {
                Icon(painterResource(R.drawable.bar_chart_24), contentDescription = toggleLabel)
            }
            Text(
                text = poll.question ?: stringResource(R.string.reaction_poll_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        poll.options.forEachIndexed { index, option ->
            PollOptionRow(
                option = option,
                count = tally.counts[index],
                total = tally.total,
                selected = tally.isMine(option),
                showResults = showResults,
                onClick = option.reaction?.takeIf { option.votable }?.let { reaction ->
                    onVote?.let { vote -> { vote(voteFor(reaction, tally.mine)) } }
                },
            )
        }
        RollingText(
            text = pluralStringResource(R.plurals.reaction_poll_total, tally.total, tally.total),
            order = tally.total,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 用户切过的「看不看结果」，[whenVoted] 是切的时候投没投。 */
private data class ResultsToggle(val whenVoted: Boolean, val show: Boolean)

private val ResultsToggleSaver = listSaver<ResultsToggle?, Boolean>(
    save = { toggle -> toggle?.let { listOf(it.whenVoted, it.show) }.orEmpty() },
    restore = { saved -> if (saved.size == 2) ResultsToggle(saved[0], saved[1]) else null },
)

/**
 * 一个选项：一整块可点（[onClick] 为 null 时不能点），表情、文字、百分比一行（没写表情时表情格空着，文字仍对齐），下面一条比例条（长度是几何，走空间弹簧）。
 * 百分比按「100%」的宽度占位、比例条一直占着一行，没显示结果时不画：行高与文字折行在各状态下都一样。
 * 我投的那项底色 `primaryContainer`、条用 `primary`、表情角上一个勾；别的项底色 `surfaceContainerHigh`、条用 `outline`
 * （与轨道 `surfaceContainerHighest` 明暗拉开）。条高 6、与文字间隔 4、表情格 24 是设计决定（not in M3）。
 */
@Composable
private fun PollOptionRow(
    option: ReactionPoll.Option,
    count: Int,
    total: Int,
    selected: Boolean,
    showResults: Boolean,
    onClick: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val container by animateColorAsState(if (selected) colors.primaryContainer else colors.surfaceContainerHigh, effects, label = "pollRow")
    val content by animateColorAsState(if (selected) colors.onPrimaryContainer else colors.onSurface, effects, label = "pollText")
    val fill by animateColorAsState(if (selected) colors.primary else colors.outline, effects, label = "pollFill")
    val share by animateFloatAsState(
        targetValue = if (showResults) shareOf(count, total) else 0f,
        animationSpec = LocalAppMotion.current.scheme.defaultSpatialSpec(),
        label = "pollShare",
    )
    val track = colors.surfaceContainerHighest
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .then(
                if (onClick != null) Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick) else Modifier,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(SmileyCell)) {
                option.smiley?.let { SmileyImage(it, size = 20.dp, modifier = Modifier.align(Alignment.Center)) }
                if (selected) {
                    Icon(
                        painter = painterResource(R.drawable.check_circle_filled_24),
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(CheckSize)
                            .background(container, CircleShape),
                    )
                }
            }
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                modifier = Modifier.weight(1f),
            )
            Box(contentAlignment = Alignment.CenterEnd) {
                // 占位：按最宽的「100%」留出宽度，数字出现、变化时文字不重新折行
                Text(
                    text = stringResource(R.string.reaction_poll_percent, 100),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.Unspecified,
                    modifier = Modifier.alpha(0f).clearAndSetSemantics {},
                )
                if (showResults) {
                    val percent = (shareOf(count, total) * 100).roundToInt()
                    RollingText(
                        text = stringResource(R.string.reaction_poll_percent, percent),
                        order = percent,
                        style = MaterialTheme.typography.labelLarge,
                        color = content,
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(ShareBarHeight)
                .drawBehind { if (showResults) drawShare(share, fill, track) },
        )
    }
}

private val SmileyCell = 24.dp
private val CheckSize = 12.dp
private val ShareBarHeight = 6.dp

/** 比例条：先画整条轨道，再从书写方向的起始一侧铺 [share] 的宽度，两端圆头。 */
private fun DrawScope.drawShare(share: Float, color: Color, track: Color) {
    val radius = CornerRadius(size.height / 2)
    drawRoundRect(track, cornerRadius = radius)
    if (share <= 0f) return
    val width = size.width * share
    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
    drawRoundRect(color, topLeft = Offset(x, 0f), size = Size(width, size.height), cornerRadius = radius)
}
