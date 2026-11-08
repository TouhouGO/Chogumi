package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import io.github.touhougo.chogumi.core.res.R

/**
 * 小组图标：方图铺满、裁成圆角方块（小组页头部、「小组」页顶部那一行、全部小组列表、小组话题的眉题共用）。
 * 没有图或加载失败时在 `surfaceContainerHigh` 底上画小组图标。
 */
@Composable
fun GroupIcon(url: String?, size: Dp, shape: Shape, modifier: Modifier = Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        if (url == null || failed) {
            MissingImageIcon(R.drawable.group_24)
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
