package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.data.repository.SyncProgress
import io.github.touhougo.chogumi.core.model.CollectionStatistics
import io.github.touhougo.chogumi.core.model.ContentDomains
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.LocalAppFeatures
import io.github.touhougo.chogumi.core.ui.composables.ConnectedChoiceGroup
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.PageScaffold
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.StatsCard
import io.github.touhougo.chogumi.core.ui.composables.iconRes
import io.github.touhougo.chogumi.core.ui.composables.labelRes
import io.github.touhougo.chogumi.core.ui.composables.relativeTimeText
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf


/** 生涯的标题（完整统计页与生涯卡同一份）：本人「我的生涯」，他人「称呼 的生涯」，资料没到时空着。 */
@Composable
internal fun careerTitle(state: CareerState): String {
    if (state.isSelf) return stringResource(R.string.career_stats_title_self)
    val ownerName = state.ownerName ?: return ""
    return stringResource(R.string.career_stats_title_other, ownerName)
}


/** 文案里怎么称呼这份生涯的主人（唯一取法）：本人「我」，他人用昵称，不写你、TA。 */
@Composable
internal fun careerWho(state: CareerState): String =
    if (state.isSelf) stringResource(R.string.career_who_self) else state.ownerName.orEmpty()


/**
 * 完整统计页（「我的生涯」/「X 的生涯」）：顶上是类型切换，下面一张大数字，再往下一块一块的图从上往下排，
 * 每块一个短标题、一句答案、一张图，不写问句、不写说明。
 *
 * 本人：顶栏右上 ⟳ 与下拉都强制同步；他人没有同步入口（不是你的数据）。
 * 画什么由 [CareerState.content] 一处决定，这里只负责把每种形态摆出来。
 */
@Composable
fun CareerStatsScreen(
    username: String?,
    initialType: SubjectType,
    onBack: () -> Unit,
    onSubjectClick: (Int) -> Unit,
    onPersonClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CareerViewModel = koinViewModel {
        parametersOf(username, initialType, false)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val publicRatings = LocalAppFeatures.current.publicRatings
    val title = careerTitle(state)
    val snackbarHostState = remember { SnackbarHostState() }
    FailureNoticeEffect(viewModel.failures, snackbarHostState)
    PageScaffold(
        title = title,
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        isRefreshing = state.refreshing,
        onRefresh = viewModel::refresh.takeIf { state.isSelf },
        actions = {
            if (state.isSelf) {
                IconButton(onClick = viewModel::refresh, enabled = !state.refreshing) {
                    Icon(painterResource(R.drawable.refresh_24), contentDescription = stringResource(R.string.career_refresh_now))
                }
            }
        },
        modifier = modifier,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            ConnectedChoiceGroup(
                options = ContentDomains,
                selected = state.selectedType,
                label = { stringResource(it.labelRes()) },
                onSelect = viewModel::selectType,
                icon = { type, selected -> type.iconRes(selected) },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            )
            SyncStatus(state)
            Column(Modifier.padding(top = 12.dp)) {
                CareerStatsContent(
                    state,
                    publicRatings,
                    onSummarize = viewModel::summarize,
                    onRetryOwner = viewModel::retryOwner,
                    onSubjectClick = onSubjectClick,
                    onPersonClick = onPersonClick,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}


// ---- 类型组下面的同步条 ----

@Composable
private fun SyncStatus(state: CareerState) {
    if (!state.isSelf) return
    if (state.sync.active) {
        SyncBar(state.sync)
        return
    }
    state.lastSyncedAt?.let { last ->
        Text(
            stringResource(R.string.career_sync_last, relativeTimeText(last)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
        )
    }
}


@Composable
private fun SyncBar(progress: SyncProgress, modifier: Modifier = Modifier) {
    val total = progress.totalPages
    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (total != null) stringResource(R.string.career_sync_running_pages, progress.completedPages, total)
            else stringResource(R.string.career_sync_running),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (total != null && total > 0) {
            LinearWavyProgressIndicator(progress = { progress.completedPages.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}


// ---- 正文：按 CareerContent 的形态摆 ----

@Composable
internal fun CareerStatsContent(
    state: CareerState,
    publicRatings: Boolean,
    onSummarize: () -> Unit,
    onRetryOwner: () -> Unit,
    onSubjectClick: (Int) -> Unit,
    onPersonClick: (Int) -> Unit,
) {
    val type = state.selectedType
    val who = careerWho(state)
    when (val content = state.content()) {
        CareerContent.Loading -> CareerStatsSkeleton(summarizing = null, type = type, isSelf = state.isSelf, who = who, publicRatings = publicRatings)
        is CareerContent.Unavailable -> RetryError(content.failure, onRetryOwner, Modifier.fillMaxWidth())
        CareerContent.Empty -> {
            val typeLabel = stringResource(type.labelRes())
            EmptyLine(
                if (state.isSelf) stringResource(R.string.career_empty_self, typeLabel) else stringResource(R.string.career_empty_other, who, typeLabel),
            )
        }
        is CareerContent.Summarizing -> CareerStatsSkeleton(summarizing = content, type = type, isSelf = state.isSelf, who = who, publicRatings = publicRatings)
        is CareerContent.AwaitingConfirm -> StatsCard {
            LadderSummary(content.ladder, type)
            SummarizeButton(content.count, type, who, onSummarize, Modifier.padding(top = 12.dp))
        }
        is CareerContent.Failed -> StatsCard {
            LadderSummary(content.ladder, type)
            RetryError(content.failure, onSummarize, Modifier.padding(top = 8.dp), compact = true)
        }
        is CareerContent.Ready -> CareerReady(type, state.isSelf, who, content, publicRatings, onSubjectClick, onPersonClick)
    }
}


@Composable
private fun CareerReady(
    type: SubjectType,
    isSelf: Boolean,
    who: String,
    ready: CareerContent.Ready,
    publicRatings: Boolean,
    onSubjectClick: (Int) -> Unit,
    onPersonClick: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CAREER_CARD_SPACING)) {
        StatsCard { CareerKpiRow(careerKpisOf(type, ready.stats, isSelf)) }
        CareerSections(type, who, ready.stats, ready.ladder, publicRatings, onSubjectClick, onPersonClick)
    }
}


/** 完整统计页卡与卡的间距（真内容与骨架同一个）。 */
internal val CAREER_CARD_SPACING = 12.dp


/**
 * 大数字下面的一块块图，从上往下按这个顺序排；哪块画不画由数据决定（各块自己判断）。
 * 骨架（[CareerStatsSkeleton]）用样例数据在 [StatPlaceholders] 里调同一个函数，各块的位置与高度因此与真内容同源。
 */
@Composable
internal fun CareerSections(
    type: SubjectType,
    who: String,
    stats: CollectionStatistics,
    ladder: StatusLadder,
    publicRatings: Boolean,
    onSubjectClick: (Int) -> Unit,
    onPersonClick: (Int) -> Unit,
) {
    val unit = stringResource(careerSpecOf(type).unitRes)
    stats.rhythm?.let { RhythmSections(it) }
    TimeMachineSection(stats.timeMachine, unit)
    ScoreSections(stats, unit, who, publicRatings)
    FavoritesSection(stats, unit, onSubjectClick)
    GenreSection(stats, unit)
    stats.popularity?.let { PopularitySection(it, unit, onSubjectClick) }
    LengthSection(stats, unit)
    StatusSection(ladder, stats, type, unit)
    stats.lineup?.let { LineupSections(it, unit, onPersonClick) }
}
