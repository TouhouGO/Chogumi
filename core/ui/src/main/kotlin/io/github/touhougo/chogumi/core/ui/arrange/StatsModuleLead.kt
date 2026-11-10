package io.github.touhougo.chogumi.core.ui.arrange

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.StatsData
import io.github.touhougo.chogumi.core.res.R

/**
 * 统计模块在整理列表与「添加模块」里打头的那一枚：`secondaryContainer` 的圆，里面按数据画一个图标
 * （成块的数据画格子，其余画柱）。作品页的整理与手势引导里统计整理的示意都画它。
 */
@Composable
fun StatsModuleLead(data: StatsData) {
    val icon = when (data) {
        StatsData.COLLECTION, StatsData.TAGS, StatsData.SERIES, StatsData.RECOMMENDATIONS -> R.drawable.grid_view_24
        else -> R.drawable.bar_chart_24
    }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), null) }
    }
}
