package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp

/**
 * 带「进行中」状态的主按钮：面板标题行右侧的确认按钮（保存、发送、提交）、整页的登录按钮。不能提交时不可点；
 * 进行中按钮里转圈，原内容留位不画，所以宽度和高度不变；进行中按钮保持原色、只是再点不会再提交，读屏念 [busyLabel]。
 * [height] 是按钮的容器高度档（M3 Expressive：小号 40dp、中号 56dp……），形状、内边距、字阶、图标尺寸都随它。
 */
@Composable
fun BusyButton(
    onClick: () -> Unit,
    enabled: Boolean,
    busy: Boolean,
    busyLabel: String,
    modifier: Modifier = Modifier,
    height: Dp = ButtonDefaults.MinHeight,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = { if (!busy) onClick() },
        enabled = enabled || busy,
        shapes = ButtonDefaults.shapesFor(height),
        modifier = modifier.heightIn(min = height).semantics { if (busy) stateDescription = busyLabel },
        contentPadding = ButtonDefaults.contentPaddingFor(height),
    ) {
        Box(contentAlignment = Alignment.Center) {
            ProvideTextStyle(ButtonDefaults.textStyleFor(height)) {
                Row(Modifier.alpha(if (busy) 0f else 1f), verticalAlignment = Alignment.CenterVertically, content = content)
            }
            if (busy) ContentLoadingIndicator(Modifier.size(ButtonDefaults.iconSizeFor(height)), color = LocalContentColor.current)
        }
    }
}
