package io.github.touhougo.chogumi.feature.explore

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import io.github.touhougo.chogumi.core.ui.composables.CoverWash
import io.github.touhougo.chogumi.core.ui.image.rememberCoverScheme

/**
 * 拿一部作品的封面当底的卡（焦点卡、代表作方块）都经这里画底：这张封面的模糊图铺满，上面压一层罩（[CoverVeil]）。
 * 两种罩按卡的用处分：焦点卡字多（名字、评分、标签），罩是半透明的容器色，深浅跟着 App 主题；代表作方块是入口，
 * 只有一个词，罩是从左往右的深色渐变，永远深底白字，像一张海报。
 */
internal data class CoverCardColors(val container: Color, val content: Color)

/** 卡的容器色与字色：封面取色到了就换成它的 primaryContainer 一对（颜色是效果，走 effects 规格）；没取到用 [fallback]。 */
@Composable
internal fun rememberCoverColors(url: String?, fallback: CoverCardColors): CoverCardColors {
    val cover = rememberCoverScheme(url)
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val container by animateColorAsState(cover?.primaryContainer ?: fallback.container, effects, label = "cover_container")
    val content by animateColorAsState(cover?.onPrimaryContainer ?: fallback.content, effects, label = "cover_content")
    return CoverCardColors(container, content)
}

/** 封面卡底上压的那层罩。 */
internal enum class CoverVeil {
    /** 半透明的容器色：模糊图只透出 [TINT_WASH_ALPHA] × (1 − [TINT_ALPHA])，对比度按容器色与它的 on 色算。 */
    TINT,

    /** 深色渐变：封面原样模糊铺满，左边压到 [DARK_START_ALPHA] 托住白字，右边留 [DARK_END_ALPHA] 透出封面。 */
    DARK,
}

/** 深色罩的卡的容器色与字色：黑底白字，模糊图到边上变淡时不让别的颜色从边上透出来。 */
internal val DarkCoverCardColors = CoverCardColors(Color.Black, Color.White)

/** 卡的底：[washUrl] 的模糊图加 [veil]（[container] 只给 [CoverVeil.TINT] 用）。模糊图放大后会溢出，裁在卡的 [shape] 里。 */
@Composable
internal fun CoverBackdrop(washUrl: String?, veil: CoverVeil, container: Color, shape: Shape, modifier: Modifier = Modifier) {
    Box(modifier.clip(shape)) {
        when (veil) {
            CoverVeil.TINT -> {
                CoverWash(washUrl, alpha = TINT_WASH_ALPHA)
                Box(Modifier.matchParentSize().background(container.copy(alpha = TINT_ALPHA)))
            }
            CoverVeil.DARK -> {
                CoverWash(washUrl)
                Box(Modifier.matchParentSize().background(DarkScrim))
            }
        }
    }
}

/** 容器色罩下模糊封面的不透明度（原型 `.wash` 的 .38）。我的设计决定（not in M3）。 */
private const val TINT_WASH_ALPHA = 0.38f

/** 压在铺底上的容器色的不透明度（原型 `.tint` 的 .82–.86 取中）。我的设计决定（not in M3）。 */
private const val TINT_ALPHA = 0.84f

/** 深色渐变两端的不透明度（代表作方块原型 B）。我的设计决定（not in M3）。 */
private const val DARK_START_ALPHA = 0.62f
private const val DARK_END_ALPHA = 0.15f

private val DarkScrim = Brush.horizontalGradient(listOf(Color.Black.copy(alpha = DARK_START_ALPHA), Color.Black.copy(alpha = DARK_END_ALPHA)))
