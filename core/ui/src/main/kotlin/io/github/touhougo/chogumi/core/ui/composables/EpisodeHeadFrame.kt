package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

/**
 * 单集面板头部的**框架**（单集面板与它在手势引导里的示意共用）：左边集号 → 标题 → 原名（给了才有）→ 播出日，
 * 字阶、颜色（集号、原名、播出日 `onSurfaceVariant`）与间距只写在这里；给了 [onReply] 时右上角是「回复」。
 * 各行收到框架定的字阶，画字或骨架由调用方定。
 */
@Composable
fun EpisodeHeadFrame(
    number: @Composable (TextStyle) -> Unit,
    title: @Composable (TextStyle) -> Unit,
    airdate: @Composable (TextStyle) -> Unit,
    onReply: (() -> Unit)?,
    name: (@Composable (TextStyle) -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ChogumiDimens.ScreenPadding).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            val secondary = MaterialTheme.colorScheme.onSurfaceVariant
            CompositionLocalProvider(LocalContentColor provides secondary) { number(MaterialTheme.typography.labelMedium) }
            title(MaterialTheme.typography.headlineSmall)
            if (name != null) {
                CompositionLocalProvider(LocalContentColor provides secondary) { name(MaterialTheme.typography.bodyMedium) }
            }
            Box(Modifier.padding(top = 4.dp)) {
                CompositionLocalProvider(LocalContentColor provides secondary) { airdate(MaterialTheme.typography.bodyMedium) }
            }
        }
        if (onReply != null) {
            FilledTonalButton(onClick = onReply) {
                Icon(painterResource(R.drawable.reply_24), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.reply))
            }
        }
    }
}
