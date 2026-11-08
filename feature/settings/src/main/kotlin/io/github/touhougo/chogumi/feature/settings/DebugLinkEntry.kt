package io.github.touhougo.chogumi.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.LocalInAppLinkHandler
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import io.github.touhougo.chogumi.core.ui.common.navigation.inAppRoute
import io.github.touhougo.chogumi.core.ui.composables.FormTextField

/**
 * 调试用的按链接跳转：界面上没有入口能进到任意用户、任意条目，验证分页与他人空间时靠它。
 * 手输任意 bgm.tv 链接，覆盖真实使用中冒出来的链接形态。
 */
@Composable
fun DebugLinkEntry(modifier: Modifier = Modifier) {
    val navigate = LocalInAppLinkHandler.current
    var input by rememberSaveable { mutableStateOf("") }
    val route = remember(input) { debugRoute(input) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.debug_link_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.debug_link_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FormTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = BgmWeb.user("demo_user"),
            label = stringResource(R.string.debug_link_field),
        )
        if (input.isNotBlank() && route == null) {
            Text(
                text = stringResource(R.string.debug_link_unrecognized),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = { route?.let { navigate?.invoke(it) } },
            enabled = route != null && navigate != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.debug_link_go))
        }
    }
}

/**
 * 调试输入 → 路由。裸数字当条目 ID、其余不带斜杠的当用户名（两者的链接由 [BgmWeb] 拼）、不带协议的补上 https，
 * 都转成链接后交给 [inAppRoute]——链接去哪一页仍只由它判定。
 */
internal fun debugRoute(input: String): Route? {
    val text = input.trim()
    return when {
        text.isEmpty() -> null
        '/' !in text -> inAppRoute(text.takeIf { it.all(Char::isDigit) }?.toIntOrNull()?.let(BgmWeb::subject) ?: BgmWeb.user(text))
        "://" in text || text.startsWith("/") -> inAppRoute(text)
        else -> inAppRoute("https://$text")
    }
}
