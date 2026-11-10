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
 * [color] 只在卡不在页面上时换：手势引导的演示台本身就是 `surfaceContainer`，卡在那里取 `raisedContainer`。
 */
@Composable
fun StatsCard(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = color,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = verticalArrangement, content = content)
    }
}

/** 统计 tab 上一个模块的卡：标题、读数与图之间隔多少；模块上下各留多少（两块之间是它的两倍）。设计决定，not in M3。 */
val StatsCardGap = 12.dp
val StatsModulePadding = 6.dp

/** 图例里的纯色小方块（原型 `.legend i`：10dp、圆角 3dp；设计决定，not in M3）。两处统计图例共用。 */
@Composable
fun ColorSwatch(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(10.dp)) { drawRoundRect(color, cornerRadius = CornerRadius(3.dp.toPx())) }
}
