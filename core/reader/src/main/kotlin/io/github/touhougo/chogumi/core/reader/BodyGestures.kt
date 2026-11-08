package io.github.touhougo.chogumi.core.reader

import android.content.ClipData
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import io.github.touhougo.chogumi.core.common.bbcode.floorCopyTextOf
import kotlinx.coroutines.launch

// 一段正文（楼层、短信气泡）上的两个动作，楼层与短信共用这一份：点、长按开菜单，以及菜单里的「复制」。

/**
 * 正文手势：点 = [onTap]，长按 = [onLongPress]（参数是按下位置）；两者都为 null 时不挂手势、不出涟漪。
 * 按压涟漪经 [interaction] 自己发（[detectTapGestures] 不像 `clickable` 自带 indication）；
 * 读屏另挂同名无障碍动作（[tapLabel] / [longPressLabel]，长按从左上角弹出）。
 * 正文里的链接、图片、引用条自己消费点击，不会误触这里的点按。
 */
@Composable
fun Modifier.bodyGestures(
    interaction: MutableInteractionSource,
    onTap: (() -> Unit)?,
    onLongPress: ((Offset) -> Unit)?,
    tapLabel: String?,
    longPressLabel: String,
): Modifier {
    if (onTap == null && onLongPress == null) return this
    val currentTap by rememberUpdatedState(onTap)
    val currentLongPress by rememberUpdatedState(onLongPress)
    return this
        .semantics {
            onTap?.let { onClick(label = tapLabel) { currentTap?.invoke(); true } }
            onLongPress?.let { onLongClick(label = longPressLabel) { currentLongPress?.invoke(Offset.Zero); true } }
        }
        .indication(interaction, ripple())
        .pointerInput(onTap != null, onLongPress != null) {
            detectTapGestures(
                onPress = { position ->
                    val press = PressInteraction.Press(position)
                    interaction.emit(press)
                    val released = tryAwaitRelease()
                    interaction.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                },
                onTap = onTap?.let { { currentTap?.invoke() } },
                onLongPress = onLongPress?.let { { position -> currentLongPress?.invoke(position) } },
            )
        }
}

/**
 * 菜单里的「复制」：把一段 BBCode 正文按读到的样子（[floorCopyTextOf]：去掉标记与开头的引用）放进剪贴板。
 * 复制成功不另外提示：Android 13 起系统自己会弹剪贴板预览。
 */
@Composable
fun rememberBodyCopier(): (content: String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) {
        { content -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, floorCopyTextOf(content)))) } }
    }
}
