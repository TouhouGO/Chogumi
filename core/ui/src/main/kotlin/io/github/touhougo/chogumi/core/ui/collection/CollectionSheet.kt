package io.github.touhougo.chogumi.core.ui.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.BookUnit
import io.github.touhougo.chogumi.core.model.CollectionEditor
import io.github.touhougo.chogumi.core.model.CollectionEdits
import io.github.touhougo.chogumi.core.model.CollectionProgress
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.CollectionWork
import io.github.touhougo.chogumi.core.model.EpisodeProgressTarget
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.formatEpisodeSort
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.BusyButton
import io.github.touhougo.chogumi.core.ui.composables.ExpandReveal
import io.github.touhougo.chogumi.core.ui.composables.MetaText
import io.github.touhougo.chogumi.core.ui.composables.PrivateRow
import io.github.touhougo.chogumi.core.ui.composables.PageModalSheet
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.composables.bookProgressLabel
import io.github.touhougo.chogumi.core.ui.composables.labelResFor
import io.github.touhougo.chogumi.core.ui.composables.markDoneLabel
import io.github.touhougo.chogumi.core.ui.composables.relativeTimeText
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import java.time.LocalDate

/**
 * 收藏面板在页面上的落点：开着且作品到齐了才画（[CollectionSheetState.isShown]、[work]），详情页、进度页、首页同一份。
 * 状态与回调都来自页面 ViewModel 里的 [CollectionSheetController]。
 *
 * @param snackbar 页面的提示条状态：面板开着时提示条画在面板上（页面的在面板下面，看不见），页面那一处这时不画。
 */
@Composable
fun CollectionSheetHost(
    state: CollectionSheetState,
    work: CollectionWork?,
    today: LocalDate,
    snackbar: SnackbarHostState,
    onRetryEpisodes: () -> Unit,
    onSave: (CollectionEdits) -> Unit,
    onClose: (CollectionEdits) -> Unit,
    onRemove: () -> Unit,
) {
    if (!state.isShown || work == null) return
    key(work.subject.id) {
        CollectionSheet(
            work = work,
            today = today,
            restored = state.draft,
            saving = state.saving,
            saved = state.phase == CollectionSheetPhase.SAVED || state.phase == CollectionSheetPhase.REMOVED,
            removing = state.removing,
            snackbar = snackbar,
            onRetryEpisodes = onRetryEpisodes,
            onSave = onSave,
            onClose = onClose,
            onRemove = onRemove,
        )
    }
}

/**
 * 收藏面板，画在页面面板层（[PageModalSheet]）：
 * 首开半屏，放抬头、状态、进度、评分，日常改动这一屏就够；往上拖或点拖动条展开，才是标签、吐槽、可见性、移除。
 * 展开也留着顶上一截遮罩，返回或点空白处就关，所以不放关闭按钮。
 *
 * - 判定全经 [CollectionWork.editor]（core:model）：面板只记没保存的修改（[CollectionEdits]），看到的是叠在已保存收藏上的样子。
 * - 保存：按钮里转圈（[saving]）；成功由页面让面板退场（[saved]），失败留在面板里，提示条画在面板上（[snackbar]）。
 * - 没保存就关掉：修改交给页面暂存（[onClose]），再打开时带回来（[restored]），顶上一行「上次没保存的修改还在」加「放弃」。
 *
 * @param today 业务日：进度尺上哪些集还没播。
 * @param onRetryEpisodes 动画的章节取不到时，进度区里的就地重试。
 */
@Composable
internal fun CollectionSheet(
    work: CollectionWork,
    today: LocalDate,
    restored: CollectionEdits?,
    saving: Boolean,
    saved: Boolean,
    snackbar: SnackbarHostState,
    onRetryEpisodes: () -> Unit,
    onSave: (CollectionEdits) -> Unit,
    onClose: (CollectionEdits) -> Unit,
    onRemove: () -> Unit,
    removing: Boolean = false,
) {
    val subject = work.subject
    val editor = work.editor
    val form = rememberSaveable(saver = CollectionFormState.Saver) {
        CollectionFormState(restored ?: CollectionEdits(), savedComment = editor.saved.comment)
    }
    var showRestored by rememberSaveable { mutableStateOf(restored != null) }
    PageModalSheet(onDismiss = { onClose(form.snapshot()) }, fullHeight = true, hideRequested = saved, snackbar = snackbar) {
        val edits = form.snapshot()
        val current = editor.formOf(edits)
        SheetHeader(
            subject = subject,
            editor = editor,
            canSave = editor.isDirty(edits) && !saving,
            saving = saving,
            removing = removing,
            onSave = { onSave(form.snapshot()) },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = BottomRoom),
        ) {
            ExpandReveal(showRestored) {
                RestoredBanner(onDiscard = {
                    form.discard()
                    showRestored = false
                })
            }
            ExpandReveal(editor.suggestsDone(current)) {
                NoteRow(text = stringResource(R.string.collection_progress_at_end), modifier = Modifier.padding(bottom = 12.dp)) {
                    FilledTonalButton(onClick = { form.edit { copy(type = CollectionType.DONE) } }) {
                        Text(markDoneLabel(subject.type))
                    }
                }
            }
            StatusChoice(selected = current.type, subject = subject, onSelect = { type -> form.edit { copy(type = type) } })
            ExpandReveal(editor.offersFinishAll(current)) {
                FinishAllRow(
                    progress = current.progress,
                    checked = edits.finishAll,
                    onCheckedChange = { checked -> form.edit { copy(finishAll = checked) } },
                )
            }
            ExpandReveal(editor.showsProgress(current)) {
                ProgressSection(
                    editor = editor,
                    progress = current.progress,
                    episodes = work.episodes,
                    today = today,
                    onRetryEpisodes = onRetryEpisodes,
                    onChange = { progress -> form.edit { copy(progress = progress) } },
                )
            }
            RatingSection(rate = current.rate, onChange = { rate -> form.edit { copy(rate = rate) } })
            SectionDivider()
            TagsSection(
                tags = current.tags,
                suggestions = subject.tags.map { it.name to it.count },
                input = form.tagInput,
                onChange = { tags -> form.edit { copy(tags = tags) } },
            )
            CommentField(state = form.comment)
            PrivateRow(checked = current.isPrivate, onCheckedChange = { checked -> form.edit { copy(isPrivate = checked) } })
            if (editor.collection != null) {
                SectionDivider()
                RemoveCollectionRow(subject = subject, enabled = !saving, onRemove = onRemove)
            }
        }
    }
}

/** 加标签的输入框、吐槽框、进度尺上方的读数、评分的读数（测试用）。 */
internal const val CollectionTagFieldTag = "collection_tag_field"
internal const val CollectionCommentTag = "collection_comment"
internal const val RulerReadoutTag = "ruler_readout"
internal const val RatingReadoutTag = "rating_readout"

/** 抬头：小封面、作品名、已保存的收藏（状态、进度、更新时间），右边「保存」——以后从别的页面打开也看得出在改哪一部。 */
@Composable
private fun SheetHeader(subject: SubjectBo, editor: CollectionEditor, canSave: Boolean, saving: Boolean, removing: Boolean, onSave: () -> Unit) {
    val collection = editor.collection
    val meta = buildList {
        add(stringResource(collection?.type?.labelResFor(subject.type) ?: R.string.collection_none))
        if (collection != null) {
            when (val progress = editor.saved.progress) {
                CollectionProgress.None -> Unit
                is CollectionProgress.Episodes -> add(
                    when (val upTo = progress.upTo) {
                        EpisodeProgressTarget.Empty -> stringResource(R.string.episode_summary_none)
                        is EpisodeProgressTarget.Episode -> stringResource(R.string.episode_summary_current, formatEpisodeSort(upTo.sort))
                    },
                )
                is CollectionProgress.Book -> {
                    if (progress.chapters > 0) add(bookProgressLabel(BookUnit.EP, progress.chapters))
                    if (progress.volumes > 0) add(bookProgressLabel(BookUnit.VOL, progress.volumes))
                }
            }
        }
    }
    val updated = collection?.updatedAt?.takeIf { it > 0 }?.let { stringResource(R.string.index_updated, relativeTimeText(it)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ChogumiDimens.ScreenPadding, end = ChogumiDimens.ScreenPadding, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SubjectPoster(url = subject.coverUrl, modifier = Modifier.width(HeaderCoverWidth))
        Column(Modifier.weight(1f)) {
            Text(displayNameOf(subject), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // 一行放不下时整段去掉更新时间（截成半截读不出来），状态与进度留全
            MetaText(
                parts = meta + listOfNotNull(updated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                droppableFrom = if (updated != null) meta.size else null,
            )
        }
        BusyButton(onClick = onSave, enabled = canSave, busy = saving, busyLabel = stringResource(if (removing) R.string.collection_removing else R.string.collection_saving)) {
            Text(stringResource(R.string.save))
        }
    }
}

/** 抬头小封面的宽（设计稿 `.cover`，3:4，not in M3）。 */
private val HeaderCoverWidth = 40.dp

/** 面板内容最底下留的空：最后一行不贴着导航栏。 */
private val BottomRoom = 32.dp
