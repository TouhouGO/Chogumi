package io.github.touhougo.chogumi.feature.login

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.PageScaffold

/**
 * 未登录时没有游客可看内容的整页（首页、进度、「我的」tab 与「我的帖子」）：顶栏是这一页的标题和设置（未登录也能改设置），
 * 正中一行说明加「登录 Bangumi」按钮，点了打开登录页（[LoginScreen]，可以返回）。
 * 由 `appEntries` 按登录态选页，页面自己不画未登录分支；登录表单全 App 只在登录页上。
 *
 * @param onBack 推进来的页面才有返回；tab 里为 null。
 */
@Composable
fun SignInPrompt(
    @StringRes title: Int,
    @StringRes message: Int,
    onLogin: () -> Unit,
    onSettingsClick: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    PageScaffold(
        title = stringResource(title),
        onBack = onBack,
        actions = {
            IconButton(onClick = onSettingsClick) {
                Icon(painterResource(R.drawable.settings_filled_24), stringResource(R.string.settings))
            }
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyLine(stringResource(message))
            Button(onClick = onLogin) {
                Text(stringResource(R.string.login_bangumi))
            }
        }
    }
}
