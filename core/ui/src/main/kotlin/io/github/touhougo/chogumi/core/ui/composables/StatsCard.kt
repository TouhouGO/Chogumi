package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 统计卡片的外壳（原型 `.scard`）——条目统计 tab 与生涯统计共用这一份：surfaceContainer 底、
 * [MaterialTheme.shapes] 的 large 圆角（16）、内边距 16、左右外边距 16。卡与卡之间的距离由所在的列给。
 */
@Composable
fun StatsCard(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = verticalArrangement, content = content)
    }
}

/** 图例里的纯色小方块（原型 `.legend i`：10dp、圆角 3dp；设计决定，not in M3）。两处统计图例共用。 */
@Composable
fun ColorSwatch(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(10.dp)) { drawRoundRect(color, cornerRadius = CornerRadius(3.dp.toPx())) }
}
