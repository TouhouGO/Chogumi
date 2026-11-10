package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TimePickerDialogDefaults
import androidx.compose.material3.TimePickerDisplayMode
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.res.R

/**
 * 全 App 唯一的选时间对话框：M3 时间选择器（feedback.md §Time pickers）放在官方 [TimePickerDialog] 里，标题由组件按方式给
 * （「选择时间」「输入时间」），左下角的图标在表盘与键盘输入之间切，右下「取消」「确定」。12 / 24 小时制跟随系统设置
 * （M3：在组件之外、通常由系统设置决定）。窗口矮到放不下表盘（[TimePickerDialogDefaults.MinHeightForTimePicker]）时一打开就是键盘输入
 * （M3：放不下时退回输入式，不滚动）。
 *
 * [minuteOfDay] 是从 0 点起的分钟数；确定时先 [onDismiss] 收起，再把选好的分钟数交给 [onConfirm]（同 [ConfirmDialog]）。
 */
@Composable
fun TimeSelectDialog(
    minuteOfDay: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(initialHour = minuteOfDay / 60, initialMinute = minuteOfDay % 60)
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    // 存的是「键盘输入」这一位：显示方式是没有公开构造的值类，放不进存档
    var typing by rememberSaveable { mutableStateOf(windowHeight < TimePickerDialogDefaults.MinHeightForTimePicker) }
    val mode = if (typing) TimePickerDisplayMode.Input else TimePickerDisplayMode.Picker
    TimePickerDialog(
        onDismissRequest = onDismiss,
        title = { TimePickerDialogDefaults.Title(displayMode = mode) },
        modeToggleButton = { TimePickerDialogDefaults.DisplayModeToggle(onDisplayModeChange = { typing = !typing }, displayMode = mode) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm(state.hour * 60 + state.minute)
                },
            ) { Text(stringResource(R.string.confirm)) }
        },
    ) {
        if (typing) TimeInput(state) else TimePicker(state)
    }
}
