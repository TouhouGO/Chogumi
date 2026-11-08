package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.transformations
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.image.LogoTrim

/**
 * 标志图——全仓唯一画法：公司（Bangumi 人物条目）的标志图地址，或随包的平台标志（drawable 资源 id）。
 * 整张放进框里，居中、不裁切，四边留白（原型 `.tlg .lg`）。公司标志图自己带的大片白边先裁掉（[LogoTrim]），标志不缩成一小块。
 * 标志多是画在白底上的，有图时框底恒为白色，深色主题也一样（设计决定，not in M3）；没有图或取不到时铺
 * `surfaceContainerHighest`，中间一个图片图标（[MissingImageIcon]）。框的尺寸由调用方的 [modifier] 给，形状是 [shape]。
 *
 * @param image 图片地址（空白当作没有图）或 `@DrawableRes` 资源 id；null 是没有图
 */
@Composable
fun LogoImage(image: Any?, description: String?, shape: Shape, modifier: Modifier = Modifier) {
    var failed by remember(image) { mutableStateOf(false) }
    val context = LocalPlatformContext.current
    val model = remember(image, context) {
        if (image is String) ImageRequest.Builder(context).data(image).transformations(LogoTrim).build() else image
    }
    if (image == null || (image is String && image.isBlank()) || failed) {
        MissingImageIcon(R.drawable.image_24, modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, shape))
    } else {
        AsyncImage(
            model = model,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            onError = { failed = true },
            modifier = modifier
                .background(LogoPlate, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

/** 标志的底：白色（标志图本来画在白底上）。 */
private val LogoPlate = Color.White
