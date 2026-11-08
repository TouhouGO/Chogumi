package io.github.touhougo.chogumi.feature.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.DisplayScale
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.theme.systemSmallestWidthDp
import kotlin.math.roundToInt

/** 界面大小滑块在测试里的定位标记。 */
const val DisplayScaleSliderTag = "settings_display_scale_slider"

/**
 * 界面大小（[DisplayScale]）：标题行末是这一档下屏幕的最小宽度（跟随系统时写「跟随系统」），下面一条带档位点的滑块
 * （input.md §Sliders 的 stops 配置；读数已在标题行，不画数值气泡）。可选档位按这块屏的系统最小宽度收敛。
 *
 * 拖动时读数跟着手，松手才写入：写入让 Activity 重建，每过一档重建一次用不了（设计决策，not in M3：M3 要求滑块即时生效）。
 * 拖动中的档位只在滑块自己的状态里，不跟传回来的值（晚一帧，跟着它手柄会被往回拽）；存值落地、这块屏的档位变了就换一份新状态。
 */
@Composable
internal fun DisplayScaleRow(index: Int, count: Int, stored: Int, onSelect: (Int) -> Unit) {
    val systemWidth = LocalContext.current.systemSmallestWidthDp()
    val options = remember(systemWidth) { DisplayScale.optionsFor(systemWidth) }
    val current = DisplayScale.effective(stored, systemWidth)
    val slider = remember(current, options) {
        SliderState(
            value = options.indexOf(current).toFloat(),
            steps = options.size - 2,
            trackRange = 0f..options.lastIndex.toFloat(),
        )
    }
    val shown = options[slider.value.roundToInt()]
    val title = stringResource(R.string.settings_display_scale)
    val readout = if (shown == DisplayScale.SYSTEM) {
        stringResource(R.string.theme_follow_system)
    } else {
        stringResource(R.string.settings_display_scale_width, DisplayScale.smallestWidthDpOf(systemWidth, shown))
    }
    SegmentedItem(
        index = index,
        count = count,
        onClick = null,
        modifier = Modifier.fillMaxWidth(),
        details = {
            Slider(
                state = slider,
                onValueChangeFinished = { onSelect(options[slider.value.roundToInt()]) },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = title
                        stateDescription = readout
                    }
                    .testTag(DisplayScaleSliderTag),
            )
        },
    ) {
        Row {
            Text(title, Modifier.weight(1f))
            Text(readout, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
