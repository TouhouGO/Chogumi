package io.github.touhougo.chogumi.core.ui.collection

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.CollectionEditor
import io.github.touhougo.chogumi.core.model.CollectionProgress
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.formatEpisodeSort
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.FormTextField
import io.github.touhougo.chogumi.core.ui.composables.GridCell
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.RollingText
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.TileRuler
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import java.time.LocalDate

/* 收藏面板（[CollectionSheet]）的进度区：动画的集数尺、书籍的话卷尺、读数与加减、占位，以及点大数字的输入。 */

/**
 * 进度：和章节方格同一套格子的格子尺。动画一把（看到第几集，格上写集号；没播的灰着拖不过去，连载中最新一集下面标「最新」），
 * 书籍两把（话、卷；总数未知时尺子随值往后长）。章节没到时占位，失败时就地重试。
 */
@Composable
internal fun ProgressSection(
    editor: CollectionEditor,
    progress: CollectionProgress,
    episodes: Load<List<EpisodeBo>>,
    today: LocalDate,
    onRetryEpisodes: () -> Unit,
    onChange: (CollectionProgress) -> Unit,
) {
    Column(Modifier.padding(top = 12.dp)) {
        when (progress) {
            CollectionProgress.None -> Unit
            is CollectionProgress.Episodes -> when (sectionStateOf(episodes)) {
                SectionState.LOADING -> RulerSkeleton()
                SectionState.ERROR -> RetryError(
                    failure = (episodes as? Load.Failed)?.failure ?: Failure.Unknown,
                    onRetry = onRetryEpisodes,
                    compact = true,
                    modifier = Modifier.padding(horizontal = ChogumiDimens.ScreenPadding),
                )
                SectionState.EMPTY -> Unit
                SectionState.CONTENT -> if (editor.mains.isNotEmpty()) EpisodeRuler(editor, progress, today, onChange)
            }
            is CollectionProgress.Book -> {
                CountRuler(
                    value = progress.chapters,
                    total = editor.totalChapters,
                    unit = R.string.unit_chapter,
                    description = R.string.progress_ruler_chapters,
                    hint = R.string.edit_chapters_hint,
                    decrease = R.string.chapter_decrease,
                    increase = R.string.chapter_increase,
                    onChange = { onChange(progress.copy(chapters = it)) },
                )
                CountRuler(
                    value = progress.volumes,
                    total = editor.totalVolumes,
                    unit = R.string.unit_volume,
                    description = R.string.progress_ruler_volumes,
                    hint = R.string.edit_volumes_hint,
                    decrease = R.string.volume_decrease,
                    increase = R.string.volume_increase,
                    onChange = { onChange(progress.copy(volumes = it)) },
                )
            }
        }
    }
}

/** 动画的进度尺：0 格是一集都没看，第 i 格是第 i 集正片。 */
@Composable
private fun EpisodeRuler(editor: CollectionEditor, progress: CollectionProgress.Episodes, today: LocalDate, onChange: (CollectionProgress) -> Unit) {
    val mains = editor.mains
    val reach = editor.airedUpTo(today)
    val labelOf = { index: Int -> if (index == 0) "0" else formatEpisodeSort(mains[index - 1].sort) }
    RulerBlock(
        value = editor.episodeIndexOf(progress),
        last = mains.size,
        limit = reach,
        latest = reach.takeIf { it in 1 until mains.size },
        labelOf = labelOf,
        total = labelOf(mains.size),
        unit = R.string.unit_episode,
        description = R.string.progress_ruler_episodes,
        hint = R.string.edit_episodes_hint,
        decrease = R.string.episode_decrease,
        increase = R.string.episode_increase,
        parse = { parseEpisodeInput(it, mains, reach) },
        onChange = { onChange(editor.episodesAt(it)) },
    )
}

/** 书籍的一把尺：格子就是话数 / 卷数。总数未知时尺子长到当前值之后 [UNKNOWN_TOTAL_TAIL] 格（至少 [UNKNOWN_TOTAL_MIN] 格），随值往后长。 */
@Composable
private fun CountRuler(
    value: Int,
    total: Int,
    @StringRes unit: Int,
    @StringRes description: Int,
    @StringRes hint: Int,
    @StringRes decrease: Int,
    @StringRes increase: Int,
    onChange: (Int) -> Unit,
) {
    RulerBlock(
        value = value,
        last = if (total > 0) total else maxOf(value + UNKNOWN_TOTAL_TAIL, UNKNOWN_TOTAL_MIN),
        limit = null,
        latest = null,
        labelOf = Int::toString,
        total = total.takeIf { it > 0 }?.toString(),
        unit = unit,
        description = description,
        hint = hint,
        decrease = decrease,
        increase = increase,
        parse = { parseCountInput(it, total) },
        onChange = onChange,
    )
}

/**
 * 进度尺的读数：大数字与它后面的 [tail]（「/ 1155 集」）。按基线对齐（任何字号下都在同一条线上）。
 * 一行放不下时去掉 [tail] 里可变的「/ 总数」，换成只有单位的 [shortTail]；数字本身不截。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RulerReadout(number: String, order: Int, animate: Boolean, tail: String, shortTail: String) {
    val numberStyle = MaterialTheme.typography.displaySmallEmphasized
    val tailStyle = MaterialTheme.typography.titleMedium
    val measurer = rememberTextMeasurer()
    val gap = 6.dp
    val gapPx = with(LocalDensity.current) { gap.roundToPx() }
    BoxWithConstraints {
        val maxWidth = constraints.maxWidth
        val shown = remember(number, tail, shortTail, maxWidth, numberStyle, tailStyle, gapPx) {
            fun widthOf(text: String, style: TextStyle) = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width
            if (widthOf(number, numberStyle) + gapPx + widthOf(tail, tailStyle) <= maxWidth) tail else shortTail
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.testTag(RulerReadoutTag)) {
            RollingText(number, order = order, style = numberStyle, animate = animate, modifier = Modifier.alignByBaseline())
            Text(
                text = shown,
                style = tailStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

/**
 * 一把进度尺：上面一行大数字（点了直接输入）与「/ 总数 单位」，右边减一、加一；下面是 [TileRuler]。
 *
 * @param limit 最多到第几格（之后的还没有）；null = 到最后一格。
 * @param total 「/ 1155 集」里的总数（格上的字）；null = 未知，只写单位。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RulerBlock(
    value: Int,
    last: Int,
    limit: Int?,
    latest: Int?,
    labelOf: (Int) -> String,
    total: String?,
    @StringRes unit: Int,
    @StringRes description: Int,
    @StringRes hint: Int,
    @StringRes decrease: Int,
    @StringRes increase: Int,
    parse: (String) -> ProgressInput,
    onChange: (Int) -> Unit,
) {
    val reach = limit ?: last
    val unitText = stringResource(unit)
    val descriptionText = stringResource(description)
    var typing by rememberSaveable { mutableStateOf(false) }
    var followingFinger by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ChogumiDimens.ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 大数字连同「/ 总数 单位」是一整块可点的字：点了直接输入（水波纹只裁成圆角，不裁字）
        PressArea(
            onClick = { typing = true },
            shape = MaterialTheme.shapes.small,
            onClickLabel = stringResource(R.string.progress_type_in),
            role = Role.Button,
            modifier = Modifier.weight(1f),
        ) {
            RulerReadout(
                number = labelOf(value),
                order = value,
                animate = !followingFinger,
                tail = if (total != null) stringResource(R.string.progress_ruler_total, total, unitText) else unitText,
                shortTail = unitText,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = { onChange(value - 1) }, enabled = value > 0) {
                Icon(painterResource(R.drawable.remove_24), contentDescription = stringResource(decrease))
            }
            FilledTonalIconButton(onClick = { onChange(value + 1) }, enabled = value < reach) {
                Icon(painterResource(R.drawable.add_24), contentDescription = stringResource(increase))
            }
        }
    }
    TileRuler(
        value = value,
        last = last,
        labelOf = labelOf,
        onValueChange = onChange,
        description = descriptionText,
        limit = reach,
        latest = latest,
        onFollowingFinger = { followingFinger = it },
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
    )
    if (typing) {
        ProgressInputDialog(
            title = descriptionText,
            hint = stringResource(hint),
            unit = unitText,
            initial = labelOf(value),
            parse = parse,
            onDismiss = { typing = false },
            onConfirm = { index ->
                typing = false
                onChange(index)
            },
        )
    }
}

/**
 * 章节还没到时进度区的占位：一把真的尺子（隐形、读屏看不到）撑出同样的高，上面盖骨架——章节到了原位换上，
 * 不把下面的评分、标签顶下去。盖着的那层同时挡住下面那把隐形尺子的点击。
 */
@Composable
private fun RulerSkeleton() {
    Box {
        Column(Modifier.alpha(0f).clearAndSetSemantics {}) {
            RulerBlock(
                value = 0, last = 0, limit = null, latest = null, labelOf = Int::toString, total = null,
                unit = R.string.unit_episode, description = R.string.progress_ruler_episodes, hint = R.string.edit_episodes_hint,
                decrease = R.string.episode_decrease, increase = R.string.episode_increase,
                parse = { ProgressInput.Empty }, onChange = {},
            )
        }
        Column(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {}
                .padding(horizontal = ChogumiDimens.ScreenPadding),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            SkeletonText(MaterialTheme.typography.displaySmall, widthFraction = RULER_SKELETON_READOUT_FRACTION)
            SkeletonBox(Modifier.fillMaxWidth().height(GridCell.EpisodeTile.minWidth).padding(bottom = 8.dp), shape = MaterialTheme.shapes.medium)
        }
    }
}

/** 点大数字直接输入：动画输集号，书籍输话卷数；不合法时说为什么，原值不变。 */
@Composable
private fun ProgressInputDialog(
    title: String,
    hint: String,
    unit: String,
    initial: String,
    parse: (String) -> ProgressInput,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val text = rememberTextFieldState(initial)
    var problem by remember { mutableStateOf<ProgressInput?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // 改了输入就清掉上一次的错误
    LaunchedEffect(text) { snapshotFlow { text.text.toString() }.collect { problem = null } }
    val submit = {
        when (val input = parse(text.text.toString())) {
            is ProgressInput.Valid -> onConfirm(input.index)
            else -> problem = input
        }
    }
    val error = when (val shown = problem) {
        ProgressInput.Empty -> stringResource(R.string.progress_input_empty)
        ProgressInput.NotANumber -> stringResource(R.string.progress_input_not_a_number)
        ProgressInput.NoSuchEpisode -> stringResource(R.string.progress_input_no_episode, text.text.toString().trim())
        is ProgressInput.OverLimit -> stringResource(R.string.progress_input_over_limit, shown.max, unit)
        is ProgressInput.Valid, null -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            FormTextField(
                state = text,
                label = hint,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                onKeyboardAction = { submit() },
                lineLimits = TextFieldLineLimits.SingleLine,
                inDialog = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = submit) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 书籍总数未知时尺子长到当前值之后多少格、至少多少格（设计稿 `ruler()`，not in M3）。 */
private const val UNKNOWN_TOTAL_TAIL = 30
private const val UNKNOWN_TOTAL_MIN = 50

/** 进度区占位里大数字那条占一行的几成（设计决定，not in M3）。 */
private const val RULER_SKELETON_READOUT_FRACTION = 0.4f
