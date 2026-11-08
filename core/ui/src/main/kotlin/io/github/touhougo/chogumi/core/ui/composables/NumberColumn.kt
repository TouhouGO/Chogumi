package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp

/**
 * 一列序号的宽度：按最长的那个序号有几个字符（[maxLabelLength]）、用 [style] 实测（至少 [minWidth]），
 * 多留 1px 防取整把末位挤到下一行；各行序号列左缘对齐、位数多或字号大时不折行也不把后面的内容挤歪。排名序号与曲目序号共用，不另写第二份。
 */
@Composable
fun numberColumnWidth(maxLabelLength: Int, style: TextStyle, minWidth: Dp): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(maxLabelLength, style, minWidth, density) {
        val digits = "0".repeat(maxLabelLength.coerceAtLeast(1))
        val px = measurer.measure(digits, style, maxLines = 1, softWrap = false).size.width
        with(density) { (px + 1).toDp() }.coerceAtLeast(minWidth)
    }
}
