package io.github.touhougo.chogumi.shell

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.text

/**
 * 外壳上的更新提示框，按 [prompt] 画一种：
 * - 有新版：标题带版本，正文说当前版本与怎么装，下面是发布时写的更新内容（没写不画），三个文字按钮「不再提示 / 忽略 / 下载」
 *   （设计决策，not in M3：M3 basic dialog 最多两个动作，所有者要三个并排的选择）。点遮罩或返回是这次先关。
 * - 下载中：确定进度条（不知道总长时不确定）与已下载 / 总大小，只有「取消」；点遮罩、返回不关，免得以为没在下。
 * - 下载失败：失败原因，「浏览器下载 / 取消 / 重试」。
 * - 下好了：不画框，交给系统安装器（[installApk]）；没有安装器时用浏览器打开同一个安装包地址。
 *
 * 带更新内容、进度条，不是问「要不要做」的确认框（tools/guards/check_dialogs.py 的白名单）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppUpdateDialog(prompt: AppUpdatePrompt, installedVersion: String, viewModel: AppUpdateViewModel) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    when (prompt) {
        AppUpdatePrompt.None -> Unit
        is AppUpdatePrompt.Offer -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.update_available_title, prompt.release.versionName)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.update_available_body, installedVersion))
                    prompt.release.notes?.let { ReleaseNotes(it) }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::stopPrompting) { Text(stringResource(R.string.update_stop_prompting)) }
                    TextButton(onClick = { viewModel.ignore(prompt.release) }) { Text(stringResource(R.string.update_ignore)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.download(prompt.release) }) { Text(stringResource(R.string.update_download)) }
            },
        )
        is AppUpdatePrompt.Downloading -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.update_downloading_title, prompt.release.versionName)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val total = prompt.total
                    if (total != null && total > 0) {
                        LinearWavyProgressIndicator(progress = { (prompt.written.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    val written = Formatter.formatShortFileSize(context, prompt.written)
                    Text(
                        text = if (total != null) {
                            stringResource(R.string.update_download_progress, written, Formatter.formatShortFileSize(context, total))
                        } else {
                            written
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::closeDownload) { Text(stringResource(R.string.cancel)) }
            },
        )
        is AppUpdatePrompt.Failed -> AlertDialog(
            onDismissRequest = viewModel::closeDownload,
            title = { Text(stringResource(R.string.update_download_failed_title)) },
            text = { Text(prompt.failure.text() + "\n" + stringResource(R.string.update_download_failed_hint)) },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            viewModel.closeDownload()
                            uriHandler.openUri(prompt.release.apkUrl)
                        },
                    ) { Text(stringResource(R.string.update_open_in_browser)) }
                    TextButton(onClick = viewModel::closeDownload) { Text(stringResource(R.string.cancel)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.download(prompt.release) }) { Text(stringResource(R.string.retry)) }
            },
        )
        is AppUpdatePrompt.Ready -> LaunchedEffect(prompt.file) {
            viewModel.closeDownload()
            if (!context.installApk(prompt.file)) uriHandler.openUri(prompt.release.apkUrl)
        }
    }
}

/**
 * 发布时写的更新内容：框里嵌着的一块，长了在里面滚动，按钮一直在底下。底色 `surfaceContainerHighest`：对话框是
 * `surfaceContainerHigh`，深色下 `raisedContainer` 也是这一档、会看不出边，所以用高一档的容器色（设计决策，not in M3）。
 * 最高 240dp 也是设计决策：380dp 宽、字号 1.1 时正文加这一块，框仍留在屏幕里。
 */
@Composable
private fun ReleaseNotes(notes: String) {
    Text(
        text = notes,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    )
}
