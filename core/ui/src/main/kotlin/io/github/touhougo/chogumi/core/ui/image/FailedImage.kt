package io.github.touhougo.chogumi.core.ui.image

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.PressArea

/**
 * 一张图加载失败（全 App 一种画法：帖子里的单张图、看图页里的那一页）：裂图图标 +「图片加载失败」的小条，
 * 点开浏览器看原链接（图床失效时网页也打不开，但能看到地址）。
 */
@Composable
fun FailedImage(url: String, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    PressArea(
        onClick = { uriHandler.openUri(url) },
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.broken_image_24), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.image_failed), style = MaterialTheme.typography.labelLarge)
        }
    }
}
