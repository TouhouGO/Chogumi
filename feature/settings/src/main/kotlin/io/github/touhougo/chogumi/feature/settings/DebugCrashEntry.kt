package io.github.touhougo.chogumi.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.common.debug.CrashRecorder
import io.github.touhougo.chogumi.core.res.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 调试区「上次崩溃」：读 [CrashRecorder] 写下的记录。默认只露前两行（时间与线程、异常类名与描述），
 * 展开看全文（可选中复制，等宽字便于对齐调用栈），可清除。没有记录时写「没有崩溃记录」。
 */
@Composable
fun DebugCrashEntry(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 记录读文件，不在组合里同步读：读完之前只有标题
    var report by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        report = withContext(Dispatchers.IO) { CrashRecorder.read(context) }
        loaded = true
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.debug_crash_title), style = MaterialTheme.typography.titleMedium)
        if (!loaded) return@Column
        val text = report
        if (text == null) {
            Text(
                stringResource(R.string.debug_crash_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        SelectionContainer {
            Text(
                text = if (expanded) text else text.lines().take(SUMMARY_LINES).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.detail_collapse else R.string.debug_crash_show_full))
            }
            TextButton(onClick = {
                CrashRecorder.clear(context)
                report = null
                expanded = false
            }) {
                Text(stringResource(R.string.debug_crash_clear))
            }
        }
    }
}

/** 收起时露几行：时间与线程、异常类名与描述（设计决定，not in M3）。 */
private const val SUMMARY_LINES = 2
