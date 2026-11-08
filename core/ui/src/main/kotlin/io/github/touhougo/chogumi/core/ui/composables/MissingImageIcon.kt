package io.github.touhougo.chogumi.core.ui.composables

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource

/**
 * 没有图、或图加载失败时的占位：铺满所在的图片框，居中一个 `onSurfaceVariant` 图标，边长取框的一半。
 * 底色由图片框自己给（各处本来就有 `surfaceContainerHigh` 底）。人物 / 用户头像用 `person_24`，正文图片用 `broken_image_24`。
 * 图标占框一半是设计决定（not in M3）。
 */
@Composable
fun MissingImageIcon(@DrawableRes icon: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(MISSING_ICON_FRACTION),
        )
    }
}

private const val MISSING_ICON_FRACTION = 0.5f
