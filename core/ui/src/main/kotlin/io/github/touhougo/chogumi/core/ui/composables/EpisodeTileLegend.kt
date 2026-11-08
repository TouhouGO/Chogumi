package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.EpisodeCellKind
import io.github.touhougo.chogumi.core.model.EpisodeGrid
import io.github.touhougo.chogumi.core.res.R

/** 这一页里有网页上单独标的想看、抛弃时，方格下面一行图例（只列出现了的）。 */
@Composable
internal fun EpisodeTileLegend(grid: EpisodeGrid) {
    val kinds = grid.rows.flatMap { it.cells }.mapTo(HashSet()) { it.kind }
    val wish = EpisodeCellKind.WISH in kinds
    val dropped = EpisodeCellKind.DROPPED in kinds
    if (!wish && !dropped) return
    val swatchShape = MaterialTheme.shapes.extraSmall
    FlowRow(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (wish) LegendItem(stringResource(R.string.episode_legend_wish), Modifier.tileFace(EpisodeCellKind.WISH, swatchShape))
        if (dropped) LegendItem(stringResource(R.string.episode_legend_dropped), Modifier.tileFace(EpisodeCellKind.DROPPED, swatchShape))
    }
}

@Composable
private fun LegendItem(label: String, swatch: Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(LegendSwatchSize).then(swatch))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


/** 图例色块边长（设计稿，not in M3）。 */
private val LegendSwatchSize = 14.dp
