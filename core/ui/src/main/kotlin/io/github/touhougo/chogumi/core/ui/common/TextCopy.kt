package io.github.touhougo.chogumi.core.ui.common

import android.content.ClipData
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import io.github.touhougo.chogumi.core.res.R
import kotlinx.coroutines.launch

/**
 * 把一段文字放进剪贴板，**全 App 唯一的剪贴板写入**：楼层与短信菜单的「复制」、详情页的长按复制（[copyOnLongPress]）都经这里。
 * 复制成功不另外提示：Android 13 起系统自己会弹剪贴板预览。
 */
@Composable
fun rememberTextCopier(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) {
        { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text))) } }
    }
}

/**
 * 长按复制 [text]：作品页、角色 / 人物页的标题与原名，以及简介。长按时震动一下，把**完整**文字放进剪贴板
 * （显示时截成一两行的标题也整段复制）；读屏挂同名的「复制」长按动作。
 *
 * - [onClick] 为 null（标题、原名）：点按什么也不做，不出水波纹，读屏也不念「可点」。
 * - 给了 [onClick]（简介：点正文切换折叠）：点与长按是同一个 `combinedClickable`，点按照常出水波纹。
 *   点按与长按必须在同一个节点上：外层再挂一个长按，按下已被内层的点按消费，长按永远等不到。
 *
 * 文字里的链接在内层、自己消费点按，不会被这里截走。
 */
@Composable
fun Modifier.copyOnLongPress(text: String, onClick: (() -> Unit)? = null): Modifier {
    val copy = rememberTextCopier()
    val haptics = LocalHapticFeedback.current
    val label = stringResource(R.string.copy)
    val currentText by rememberUpdatedState(text)
    if (onClick != null) {
        return combinedClickable(onLongClickLabel = label, onLongClick = { copy(currentText) }, onClick = onClick)
    }
    return this
        .semantics { onLongClick(label) { copy(currentText); true } }
        .pointerInput(copy, haptics) {
            detectTapGestures(
                onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    copy(currentText)
                },
            )
        }
}
