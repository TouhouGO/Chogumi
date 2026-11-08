package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation3.runtime.NavEntryDecorator
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion

/**
 * 给每个导航项垫一层不透明的页面底色（主题的 background），整张页（页面与它的面板层）都画在这层上。
 *
 * 页面可以只画内容（卡片、列表），空白与左右留白处是透明的。平时底下是外壳的底色，看不出来；
 * 但预测性返回的手势里，上一页（连同它开着的面板）就画在当前页下面，透明处会把上一页透出来。
 * 底色只在这一处给，页面不各自垫。
 *
 * 页面被缩小时（预测性返回的预览）四角随缩小的程度变圆（[previewCorners]），缩小的整页不是一张直角的截图。
 */
@Composable
fun <T : Any> rememberPageSurfaceNavEntryDecorator(): NavEntryDecorator<T> = remember {
    NavEntryDecorator { entry ->
        // 从封面长出来的页，飞行中的四角由容器转换的裁切给（同一个圆角），这里再裁一次会比容器圆得多，底下的封面从角上露出来
        val corners = if (LocalCornersFromContainer.current) Modifier else Modifier.previewCorners()
        Box(Modifier.fillMaxSize().then(corners).background(MaterialTheme.colorScheme.background)) { entry.Content() }
    }
}

/**
 * 缩小的整页（返回预览、容器转换飞行中）的圆角：extra extra large（48dp）。
 * M3 没给整页预览定圆角，取圆角阶梯里最大的一级是我的设计决定（not in M3）。普通页的预览和容器转换的裁切共用这一个。
 */
internal val PagePreviewCorner: CornerBasedShape
    @Composable @ReadOnlyComposable get() = MaterialTheme.shapes.extraExtraLarge

/** 这一页从封面长出来（容器转换），四角由容器的裁切负责（[rememberPageExpandNavEntryDecorator] 提供）。 */
internal val LocalCornersFromContainer = staticCompositionLocalOf { false }

/**
 * 页面画出来比排版小多少（祖先图层的缩放，即预测性返回的 `scaleOut`），四角就圆多少：整页时直角，
 * 缩到预览大小（`PageMotion.previewScale`）时是 [PagePreviewCorner]。按画出来的大小量，所以拖动、松手、取消都跟着走。
 */
@Composable
private fun Modifier.previewCorners(): Modifier {
    val corner = PagePreviewCorner
    val previewScale = LocalAppMotion.current.page.previewScale
    val drawn = remember { DrawnScale() }
    return onGloballyPositioned { coordinates ->
        val width = coordinates.size.width
        if (width > 0) drawn.scale = (coordinates.localToRoot(Offset(width.toFloat(), 0f)).x - coordinates.localToRoot(Offset.Zero).x) / width
    }.graphicsLayer {
        // 0 = 整页，1 = 缩到预览大小
        val shrunk = ((1f - drawn.scale) / (1f - previewScale)).coerceIn(0f, 1f)
        // 圆角量在缩放之前：除以画出来的比例，屏幕上看到的正好是 PagePreviewCorner
        val radius = corner.topStart.toPx(size, this) * shrunk / drawn.scale.coerceAtLeast(previewScale)
        clip = radius > 0f
        shape = RadiusShape(radius)
    }
}

/** 画出来的宽度 / 排版的宽度。 */
private class DrawnScale {
    var scale by mutableFloatStateOf(1f)
}

/** 四角同一个圆角（像素）。按值相等，圆角变了图层才重算轮廓。 */
private data class RadiusShape(val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(size.toRect(), CornerRadius(radius)))
}
