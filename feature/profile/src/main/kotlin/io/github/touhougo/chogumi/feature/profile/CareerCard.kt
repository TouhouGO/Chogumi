package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.LocalAppFeatures
import io.github.touhougo.chogumi.core.ui.composables.CoverWash
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.SkeletonCrossfade
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.composables.formatScore
import io.github.touhougo.chogumi.core.ui.composables.labelRes
import io.github.touhougo.chogumi.core.ui.theme.isDarkThemeActive
import kotlin.math.abs
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** 生涯卡外框的 testTag：骨架与真实卡挂同一个，几何测试据此比对。 */
internal const val CareerCardTag = "career_card"

/**
 * 「概览」里的生涯卡（本人与他人同一个组件）：一张杂志封面。封面拼图铺底，压着这个人的几行字——
 * 完成多少、正在追多少、均分与大众比，各类各完成多少；底部是称号与最爱的题材。点整张卡进完整统计。
 *
 * 画什么由 [CareerCardContent]（[careerCardContentOf]）一处决定；没有任何收藏时整张卡不画，资料取不到时卡的位置给重试（[onRetry]）。
 * 圆角是 M3 的 extra-large（`shape.md`）；版面、字距与蒙层出自原型 v12（设计决定，not in M3）。
 */
@Composable
internal fun CareerCard(
    content: CareerCardContent,
    title: String,
    onOpenFull: (SubjectType) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (content == CareerCardContent.Empty) return
    if (content is CareerCardContent.Unavailable) {
        RetryError(content.failure, onRetry, modifier.fillMaxWidth(), compact = true)
        return
    }
    SkeletonCrossfade(loading = content == CareerCardContent.Loading, modifier = modifier, skeleton = { CareerCardSkeleton(title) }) {
        if (content is CareerCardContent.Ready) {
            PressArea(
                onClick = { onOpenFull(content.openType) },
                shape = MaterialTheme.shapes.extraLarge,
                onClickLabel = stringResource(R.string.career_full_stats),
                role = Role.Button,
            ) {
                CareerCardFrame(
                    backdrop = { width, height -> CoverCollage(content.portrait.covers, width, height) },
                    text = { minHeight -> CareerCardText(content.portrait, title, Modifier.heightIn(min = minHeight)) },
                )
            }
        }
    }
}

/**
 * 别处（用户卡片）里的生涯卡：自己取数，画法与「概览」的 [CareerCard] 是同一个。
 * 只取服务端摘要，不汇总对方的收藏（见 [CareerViewModel] 的 `summaryOnly`）：卡上有各类计数与封面（只取最近一页收藏挑），没有均分、称号与题材。
 *
 * [username] 为 null 是自己；每个用户各自一个 [CareerViewModel]（按用户名分开），活到所在页离开。
 * [onOpenCareer] 带着 [username] 与卡上选中的类型去完整统计。
 */
@Composable
fun UserCareerCard(
    username: String?,
    onOpenCareer: (String?, SubjectType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CareerViewModel = koinViewModel(key = "user-career-${username.orEmpty()}") {
        parametersOf(username, SubjectType.ANIME, true)
    }
    val career by viewModel.uiState.collectAsStateWithLifecycle()
    CareerCard(
        content = career.cardContent(LocalAppFeatures.current.publicRatings),
        title = careerTitle(career),
        onOpenFull = { type -> onOpenCareer(username, type) },
        onRetry = viewModel::retryOwner,
        modifier = modifier,
    )
}

/**
 * 卡片外框：宽随父级，高至少 [cardMinHeight]，文字放不下（放大字号）时随文字长高；[backdrop] 铺满整张卡，
 * 拿到的是卡片最终的宽高。卡片把里面的一切裁进自己的圆角——封面在卡里是直角的拼块，四角就是卡的四角。
 */
@Composable
private fun CareerCardFrame(
    backdrop: @Composable (width: Dp, height: Dp) -> Unit,
    text: @Composable (minHeight: Dp) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (isDarkThemeActive()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().testTag(CareerCardTag),
    ) {
        BoxWithConstraints {
            val minHeight = cardMinHeight(maxWidth)
            Box(Modifier.fillMaxWidth()) {
                BoxWithConstraints(Modifier.matchParentSize()) { backdrop(maxWidth, maxHeight) }
                text(minHeight)
            }
        }
    }
}

/** 卡高：宽的 1.2 倍，最高 440dp（原型 v12；设计决定，not in M3）。 */
private fun cardMinHeight(width: Dp): Dp = min(width * CARD_HEIGHT_RATIO, CARD_MAX_HEIGHT)

private const val CARD_HEIGHT_RATIO = 1.2f
private val CARD_MAX_HEIGHT = 440.dp

/**
 * 骨架：同一个外框，文字按一份样例画出来但不可见（只为让骨架与真实卡同高，放大字号时一起长高），底下铺闪烁块。
 */
@Composable
private fun CareerCardSkeleton(title: String) {
    CareerCardFrame(
        backdrop = { _, _ -> SkeletonBox(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.extraLarge) },
        text = { minHeight ->
            CareerCardText(SkeletonPortrait, title, Modifier.heightIn(min = minHeight).alpha(0f).clearAndSetSemantics {})
        },
    )
}

/** 骨架的样例：完成、在追、均分三行与称号、最爱各一行都在，是真实卡常见的形态。 */
private val SkeletonPortrait = CareerPortrait(
    done = 0,
    doing = 1,
    doneByType = listOf(SubjectType.ANIME to 0),
    average = 0.0,
    publicGap = null,
    persona = Persona(SubjectType.ANIME, genre = null, trait = null),
    likes = listOf(""),
    covers = emptyList(),
)

/**
 * 封面拼图（[collageOf]）铺在卡底：封面不够铺满时，第一张封面模糊放大垫底，拼块带阴影浮在上面。
 * 最上面压两层蒙层（上下、左右各一层渐变），托住白字。
 */
@Composable
private fun CoverCollage(covers: List<SubjectBo>, width: Dp, height: Dp) {
    val shown = covers.take(collageCapacity(width.value, height.value, COLLAGE_GAP.value))
    val collage = remember(shown.size, width, height) { collageOf(shown.size, width.value, height.value, COLLAGE_GAP.value) }
    if (!collage.full) CoverWash(shown.firstOrNull()?.coverUrl)
    collage.cells.forEachIndexed { index, cell ->
        SubjectPoster(
            url = shown[index].coverUrl,
            modifier = Modifier.offset(cell.x.dp, cell.y.dp).size(cell.width.dp, cell.height.dp),
            shape = RectangleShape,
            shadowElevation = if (collage.full) 0.dp else LIFTED_COVER_ELEVATION,
        )
    }
    val scrim = MaterialTheme.colorScheme.scrim
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to scrim.copy(alpha = 0.55f),
                    0.24f to scrim.copy(alpha = 0.12f),
                    0.5f to scrim.copy(alpha = 0.12f),
                    1f to scrim.copy(alpha = 0.82f),
                ),
            )
            .background(
                // 左边压大数字，右边压各类计数，中间留给封面
                Brush.horizontalGradient(
                    0f to scrim.copy(alpha = 0.72f),
                    0.22f to scrim.copy(alpha = 0.55f),
                    0.45f to scrim.copy(alpha = 0f),
                    0.55f to scrim.copy(alpha = 0f),
                    0.78f to scrim.copy(alpha = 0.55f),
                    1f to scrim.copy(alpha = 0.72f),
                ),
            ),
    )
}

/** 拼块间距与不铺满时拼块的阴影（原型 v12；设计决定，not in M3）。 */
private val COLLAGE_GAP = 3.dp
private val LIFTED_COVER_ELEVATION = 8.dp

/**
 * 卡上的字（恒为白：底下是封面加蒙层，不随主题变）。上半是刊头与几行数，下半是称号与最爱；
 * 两半之间的空白随卡高伸缩（[Arrangement.SpaceBetween]）。
 */
@Composable
private fun CareerCardText(portrait: CareerPortrait, title: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = onImage(MaterialTheme.typography.headlineSmallEmphasized),
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                Icon(painterResource(R.drawable.chevron_right_24), contentDescription = null, tint = Color.White)
            }
            Row(Modifier.padding(top = 16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CardLine(formatCount(portrait.done), stringResource(R.string.career_card_done))
                    if (portrait.doing > 0) CardLine(formatCount(portrait.doing), stringResource(R.string.career_card_doing))
                    portrait.average?.let { CardLine(formatScore(it), averageLabel(portrait.publicGap)) }
                }
                if (portrait.doneByType.size > 1) {
                    Column(
                        Modifier.padding(start = 16.dp, top = 4.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        portrait.doneByType.forEach { (type, done) -> TypeLine(stringResource(type.labelRes()), formatCount(done)) }
                    }
                }
            }
        }
        Column(Modifier.padding(top = 24.dp)) {
            Text(
                personaText(portrait.persona),
                style = onImage(MaterialTheme.typography.headlineLargeEmphasized),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (portrait.likes.isNotEmpty()) {
                Text(
                    stringResource(R.string.career_card_likes, portrait.likes.joinToString(stringResource(R.string.list_separator))),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnImageMuted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 左边一行：大数字，下面一行说它是什么。 */
@Composable
private fun CardLine(value: String, label: String) {
    Column {
        Text(value, style = onImage(MaterialTheme.typography.headlineMediumEmphasized), color = Color.White, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = OnImageMuted)
    }
}

/** 右边一行：类型名与它完成的部数，两种字号对齐基线。 */
@Composable
private fun TypeLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = onImage(MaterialTheme.typography.bodySmall), color = OnImageMuted, modifier = Modifier.alignByBaseline())
        Text(value, style = onImage(MaterialTheme.typography.titleMediumEmphasized), color = Color.White, modifier = Modifier.alignByBaseline())
    }
}

/** 均分那一行的说明：比大众严 / 一致 / 松多少（[tasteLeanOf] 一处分类）；没有可比的只写「均分」。 */
@Composable
private fun averageLabel(publicGap: Double?): String = when {
    publicGap == null -> stringResource(R.string.career_card_average)
    else -> when (tasteLeanOf(publicGap)) {
        TasteLean.STRICTER -> stringResource(R.string.career_card_average_stricter, formatScore(abs(publicGap)))
        TasteLean.SAME -> stringResource(R.string.career_card_average_same)
        TasteLean.LENIENT -> stringResource(R.string.career_card_average_lenient, formatScore(publicGap))
    }
}

/** 称号的字：类型给名词（[careerSpecOf]），有题材时题材在前，有形容时形容在前。 */
@Composable
private fun personaText(persona: Persona): String {
    val spec = careerSpecOf(persona.type)
    val noun = persona.genre?.let { stringResource(spec.personaRes, it) } ?: stringResource(spec.personaPlainRes)
    return persona.trait?.let { stringResource(it.res, noun) } ?: noun
}

/** 图上的字加一层投影：封面亮的地方蒙层薄，白字靠它站住（同货架名次大字的做法，设计决定，not in M3）。 */
private fun onImage(style: TextStyle): TextStyle = style.copy(shadow = OnImageShadow)

private val OnImageShadow = Shadow(Color.Black.copy(alpha = 0.4f), Offset(0f, 2f), blurRadius = 8f)

/** 图上次一级的字：白 90%（原型 v12）。 */
private val OnImageMuted = Color.White.copy(alpha = 0.9f)
