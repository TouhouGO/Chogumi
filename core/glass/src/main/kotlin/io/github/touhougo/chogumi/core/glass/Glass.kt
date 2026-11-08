package io.github.touhougo.chogumi.core.glass

import android.os.Build
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import io.github.touhougo.chogumi.core.ui.theme.isDark

/**
 * 液态玻璃的唯一实现处（设计决策，not in M3；规则在 docs/design/liquid-glass.md）。玻璃的代码只在 core:glass 里，
 * core:ui 与页面原来的组件不知道它；页面在入口按 [rememberGlassLayer] 是不是 null 分岔。
 *
 * 玻璃只给浮在内容上、有内容从下面滚过去的控件：底栏、顶边与顶上的圆钮、钉住的 tab、主操作。内容本身永远是实心的。
 * 一块玻璃要知道它下面画的是什么：页面把会从下面滚过的内容包进 [glassSource]，浮在上面的控件用同一个 [GlassLayer] 画 [glass]。
 * 玻璃不能画进自己采样的那一层（会采到自己），所以浮在上面的控件永远是被采样内容的兄弟，不是它的子孙。
 *
 * 开关是设置里的「玻璃效果」，由 [GlassTheme] 按系统版本判一次：Android 12 起才有模糊，Android 13 起才有边缘折射与高光
 * （折射由库按版本自己跳过）。关着时 [rememberGlassLayer] 是 null，调用处画原来的 M3 组件，代码路径与没有玻璃时一样。
 */
internal val LocalGlass = staticCompositionLocalOf { false }

/** 系统能不能画玻璃：模糊要 Android 12（RenderEffect）。设置页据此决定显不显示开关，[GlassTheme] 据此决定开不开。 */
fun glassSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** 包在 `ChogumiTheme` 里面：[enabled] 是设置里的「玻璃效果」，系统不支持时当作关着。 */
@Composable
fun GlassTheme(enabled: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlass provides (enabled && glassSupported()), content = content)
}

/** 一页里被玻璃采样的那层内容。只由 [rememberGlassLayer] 造。 */
@Stable
class GlassLayer internal constructor(internal val backdrop: LayerBackdrop)

/** 玻璃开着时给一层采样（页面一份，浮在上面的玻璃共用），关着时 null：调用处据此选玻璃版或原来的组件。 */
@Composable
fun rememberGlassLayer(): GlassLayer? {
    if (!LocalGlass.current) return null
    val backdrop = rememberLayerBackdrop()
    return remember(backdrop) { GlassLayer(backdrop) }
}

/** 把这块内容录进 [layer]，浮在它上面的玻璃从这里采样。[layer] 为 null（玻璃关着）时什么也不做。 */
fun Modifier.glassSource(layer: GlassLayer?): Modifier = if (layer == null) this else layerBackdrop(layer.backdrop)

/**
 * 三种玻璃（规则板「三种玻璃」）。底色是叠在模糊上面的那层颜色，决定玻璃上的东西看不看得清。
 * - [Clear]：只放图标的圆钮，最透（约 40%）；
 * - [Labeled]：带字的胶囊（底栏、tab），底色约 65%，背后的字不和胶囊上的字抢；
 * - [Tinted]：一屏唯一的主操作，主色容器叠在玻璃上，字用主色容器上的颜色。
 */
enum class GlassMaterial { Clear, Labeled, Tinted }

/**
 * 把这个节点画成一块玻璃：采样 [layer] 里它正下方的内容，模糊、调色、边缘折射（Android 13 起），叠上 [material] 的底色、
 * 一圈高光与淡阴影。给了 [touch] 时玻璃跟着手指浮起、被拉动（[GlassTouch]，浮起时短边多出 [liftBy]），玻璃上的内容用
 * [glassContent] 跟着一起动；只在绘制阶段读，按下不重组。
 *
 * 形状只收圆角矩形（含胶囊、圆）：折射按圆角算。
 */
@Composable
fun Modifier.glass(
    layer: GlassLayer,
    shape: CornerBasedShape,
    material: GlassMaterial,
    touch: GlassTouch? = null,
    liftBy: Dp = GlassLift,
): Modifier {
    val colors = MaterialTheme.colorScheme
    val dark = colors.isDark()
    val surface = when (material) {
        GlassMaterial.Clear -> colors.surface.copy(alpha = if (dark) 0.42f else 0.38f)
        GlassMaterial.Labeled -> colors.surface.copy(alpha = if (dark) 0.62f else 0.66f)
        GlassMaterial.Tinted -> colors.primaryContainer.copy(alpha = if (dark) 0.70f else 0.66f)
    }
    val hue = if (material == GlassMaterial.Tinted) colors.primary else Color.Unspecified
    val highlight = if (dark) DarkHighlight else LightHighlight
    val shadow = if (dark) DarkShadow else LightShadow
    return drawBackdrop(
        backdrop = layer.backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(GlassBlur.toPx())
            lens(refractionHeight = GlassRefractionHeight.toPx(), refractionAmount = GlassRefractionAmount.toPx())
        },
        highlight = { highlight },
        shadow = { shadow },
        layerBlock = { if (touch != null) with(touch) { transform(liftBy) } },
        onDrawSurface = {
            if (hue.isSpecified) drawRect(hue, blendMode = BlendMode.Hue)
            drawRect(surface)
        },
    )
}

/** 模糊半径：背后的字糊开读不出，封面的颜色与大块轮廓还认得出。 */
private val GlassBlur = 10.dp

/** 边缘折射的带宽与强度（Android 13 起）：边上一圈把背后的内容往里弯，中间保持平。 */
private val GlassRefractionHeight = 12.dp
private val GlassRefractionAmount = 20.dp

private val LightHighlight = Highlight(alpha = 0.9f)
private val DarkHighlight = Highlight(alpha = 0.5f)
private val LightShadow = Shadow(radius = 16.dp, color = Color.Black.copy(alpha = 0.10f))
private val DarkShadow = Shadow(radius = 16.dp, color = Color.Black.copy(alpha = 0.32f))
