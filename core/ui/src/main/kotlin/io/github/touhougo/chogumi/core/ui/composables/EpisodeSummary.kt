package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.EpisodeAiring
import io.github.touhougo.chogumi.core.model.EpisodeSummary
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.formatEpisodeSort
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

/** 顶上那一行：「看到第 209 集」，右边「共 1155 集、已完结」（[EpisodeTracking.episodeSummary]）。 */
@Composable
internal fun EpisodeSummaryRow(summary: EpisodeSummary) {
    val current = summary.current
    val title = if (current == null) {
        stringResource(R.string.episode_summary_none)
    } else {
        stringResource(R.string.episode_summary_current, formatEpisodeSort(current.sort))
    }
    val meta = listOfNotNull(
        pluralStringResource(R.plurals.episode_summary_total, summary.total, summary.total),
        when (val airing = summary.airing) {
            is EpisodeAiring.Ongoing -> stringResource(R.string.episode_summary_aired, airing.aired)
            EpisodeAiring.Finished -> stringResource(R.string.episode_summary_finished)
            EpisodeAiring.Unknown -> null
        },
    )
    EpisodeSummaryFrame(
        title = { titleModifier ->
            Text(title, style = SummaryTitleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = titleModifier)
        },
        meta = { metaModifier ->
            MetaText(
                parts = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = metaModifier,
            )
        },
    )
}

internal val SummaryTitleStyle: TextStyle
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable get() = MaterialTheme.typography.titleMediumEmphasized

/** 摘要行的框架（真实与骨架共用）：左标题占满余下的宽，右附注，两者按基线对齐。 */
@Composable
internal fun EpisodeSummaryFrame(title: @Composable (Modifier) -> Unit, meta: @Composable (Modifier) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ChogumiDimens.ScreenPadding)
            .padding(top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        title(Modifier.weight(1f).alignByBaseline())
        meta(Modifier.alignByBaseline())
    }
}

// ---------------------------------------------------------------------------
// 方格
// ---------------------------------------------------------------------------


/** 骨架摘要行右边那段字条的宽。我的设计决定（not in M3）。 */
internal val SummaryMetaSkeletonWidth = 96.dp
