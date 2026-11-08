package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.hct.Hct

/**
 * 有序档位（篇幅、热度、排名档……）的 [count] 档颜色，统计图里的唯一出处：同一色相，从 primaryContainer 一侧走向
 * onPrimaryContainer 一侧，档越高越靠近后者（亮色主题里越深，暗色主题里越亮）。最低一档取 35% 处，免得和卡片底色混在一起。
 * 档位与比例是我的设计决定（not in M3）。收藏状态不是有序档位，颜色只取 CollectionTypeColors。
 */
@Composable
fun ordinalTones(count: Int): List<Color> = ordinalTones(MaterialTheme.colorScheme, count)

/** [ordinalTones] 的取色本身：给手上只有配色的地方（对比度测试、桌面小组件的热力图）用。 */
fun ordinalTones(scheme: ColorScheme, count: Int): List<Color> {
    val steps = (count - 1).coerceAtLeast(1)
    return List(count) { i ->
        lerp(scheme.primaryContainer, scheme.onPrimaryContainer, LOWEST_TONE + (1 - LOWEST_TONE) * i / steps)
    }
}

private const val LOWEST_TONE = 0.35f

/**
 * 写在档色块（[ordinalTones]）上的字用什么颜色，统计图里的唯一出处：深字还是浅字按对比度选（M3 取前景色调的同一算法
 * [DynamicColor.foregroundTone]），色相跟着色块走。目标对比度 7，中间档位哪边都够不到 7 时取对比度更高的一端（纯黑或纯白），
 * 任何底色上都不低于 4.5（M3 小字最低对比度）。档位块之间的中间色调不是配色角色，没有现成的 on 色可配，所以在这里算。
 */
fun inkOn(fill: Color): Color {
    val hct = Hct.fromInt(fill.toArgb())
    val tone = DynamicColor.foregroundTone(hct.tone, INK_CONTRAST)
    return Color(Hct.from(hct.hue, hct.chroma, tone).toInt())
}

private const val INK_CONTRAST = 7.0
