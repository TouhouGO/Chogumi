package io.github.touhougo.chogumi.core.ui.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.ui.composables.PageFollow

/**
 * 全 App 的动效取值。只由 [appMotionFor] 造，由 [ChogumiTheme] 提供：[scheme] 交给 Material
 * （`MaterialTheme.motionScheme`，组件和页面默认都用它），整个对象放进 [LocalAppMotion]。动画规格只从这两处取，
 * 不在调用处写 `spring` / `tween` 或直接拿 `MotionScheme.standard()`（门禁 `tools/guards/check_motion.py`）。
 *
 * 动效只有一套（全局是 M3 standard，几何几乎不回弹）。唯一的例外是 [expressive]：只给「选集格子标上时弹一下、看完荡到 1.1、
 * 长按圆环展开、选择 FAB 菜单」这几处，它们按会过冲的 spatial 弹簧设计；别处不用（门禁 `tools/guards/check_motion.py` 列着可用的文件）。
 * 系统关掉了动画（见 [rememberSystemAnimationsOff]）时，一切瞬时完成，没有依次出场的间隔，滚动直接到位。
 */
@Immutable
class AppMotion internal constructor(
    /** 主题的动效方案：M3 standard 的 spatial / effects 弹簧（motion.md），几何变化几乎不回弹；系统关掉动画时全是瞬时的。 */
    val scheme: MotionScheme,
    /**
     * M3 expressive 的 spatial / effects 弹簧（阻尼 0.6–0.8，会过冲），只给 [AppMotion] 的类文档里列的几处；系统关掉动画时全是瞬时的。
     * 其余位置一律用 [scheme]，尺寸动画（`animateContentSize`）更不能用它：过冲会把收小时末尾的内容裁掉。
     */
    val expressive: MotionScheme,
    /** 「不能这样做」的轻抖（章节格）。M3 没有抖动的弹簧，阻尼与刚度是我的设计决定（not in M3）。 */
    val shake: FiniteAnimationSpec<Float>,
    /** 页面转场的时长与缓动（见 [PageMotion]）。 */
    val page: PageMotion,
    private val animates: Boolean,
) {
    /** 依次出场的一级间隔：系统关掉动画时没有间隔，所有元素同时到位。 */
    fun stagger(millis: Long): Long = if (animates) millis else 0L

    /**
     * 程序触发的滚动（回到顶部、跳到某一楼、随选中翻页）：系统关掉动画时直接到位，否则列表用 Compose 自带的滚动动画、
     * 翻页用 [scheme] 的 spatial 弹簧（`animateScrollToItem` 不接受规格，所以滚动只走这三个入口，门禁 `tools/guards/check_motion.py`）。
     * 手指拖动和惯性不经过这里。
     */
    suspend fun scrollToItem(state: LazyListState, index: Int) =
        if (animates) state.animateScrollToItem(index) else state.scrollToItem(index)

    suspend fun scrollToItem(state: LazyGridState, index: Int) =
        if (animates) state.animateScrollToItem(index) else state.scrollToItem(index)

    /** 随选中项翻页：[follow] 记着上一段的速度，打断时接着走（见 [PageFollow]）。 */
    internal suspend fun scrollToPage(state: PagerState, page: Int, follow: PageFollow) =
        if (animates) follow.animateTo(state, page, scheme.defaultSpatialSpec()) else state.scrollToPage(page)
}

/**
 * 把 [content] 里的 M3 组件（它们自己读 `MaterialTheme.motionScheme`）换成 [AppMotion.expressive]，颜色、字体、形状不变。
 * 只用在 [AppMotion] 类文档里列的位置。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveMotion(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = MaterialTheme.colorScheme,
        motionScheme = LocalAppMotion.current.expressive,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
        content = content,
    )
}

/**
 * 页面转场的时长与缓动，取自 `transitions.md` 的 M3 转场表（转场仍用 easing + duration，不用弹簧），
 * 系统关掉动画时全部瞬时。时长按转场面积和进出分：进入长、退出短，淡出先走完再淡入。
 */
@Immutable
class PageMotion internal constructor(
    /** 预测性返回时离开的页向手势方向挪的距离。取自 MDC `MaterialSharedAxis` 的默认位移（not in M3 tokens）。 */
    val shift: Dp,
    /** 切 tab（fade through）时离开的页淡出：先走完，新页才开始出现（`transitions.md`「先完全淡出再淡入」）。 */
    val exitFade: FiniteAnimationSpec<Float>,
    /** 切 tab 时新页淡入：等旧页淡出完才开始。 */
    val enterFade: FiniteAnimationSpec<Float>,
    /**
     * 前进后退（`transitions.md` forward and backward 的视差式）两页的横移：上面那页整屏滑进或滑出，
     * 下面那页同一条曲线只走 [parallax] 那么远。两个方向里都有一页正在进入视野，所以都用进入屏幕的 Emphasized decelerate 400ms；
     * 返回也用它（不用退出的 accelerate 200ms）是我的设计决定（not in M3），为了进出对称、整屏滑得不急。
     */
    val slide: FiniteAnimationSpec<IntOffset>,
    /** 前进后退时下面那页移动的距离占页宽的比例（背景比前景慢，视差）。我的设计决定（not in M3）。 */
    val parallax: Float,
    /**
     * 预测性返回里离开的页：跟手缩小、向手势方向挪一点（[previewShrink] / [previewShift]），到后段才淡出（[previewFade]）。
     * 手势进度直接对应这段时间，所以缩小与位移是线性的，拖到哪就停在哪；松手后剩下的一段接着走完，页面淡出离开，
     * 不会以缩小的整页当帧消失。时长与淡出起点是我的设计决定（not in M3，M3 只说页面级返回用平台默认）。
     */
    val previewShrink: FiniteAnimationSpec<Float>,
    val previewShift: FiniteAnimationSpec<IntOffset>,
    val previewFade: FiniteAnimationSpec<Float>,
    /** 预测性返回时，离开的页跟手缩小到的比例。我的设计决定（not in M3）。 */
    val previewScale: Float,
    /** 容器转换的边界从卡片长到整页（或缩回）：开始与结束都在屏幕上，500ms（Emphasized 的位置，缓动见 [Standard]）。 */
    val container: FiniteAnimationSpec<Rect>,
    /** 容器转换展开时，详情页在容器里淡入：前 25%（MDC `MaterialContainerTransform` 的默认阈值，not in M3 tokens）。 */
    val containerFadeIn: FiniteAnimationSpec<Float>,
    /** 容器转换缩回时，详情页在容器里淡出、露出垫在下面的封面：60% 到 90% 之间（同上，MDC 的默认返回阈值）。 */
    val containerReturnFadeOut: FiniteAnimationSpec<Float>,
    /** 容器转换里离开的列表页和展开时离开的封面：整个转换期间保持不透明，转换结束才消失。 */
    val containerHold: FiniteAnimationSpec<Float>,
    /**
     * 预测性返回从封面长出来的页：手势的前一半只把整页线性缩到 [preview]（缩小到 [previewScale] 的整页，跟普通页的返回预览一样），
     * 后一半（多半在松手之后）再用 [Standard] 缩回封面。手势进度直接对应转换的时间，用 [container] 的话手指一动整页就先缩成一张小卡片。
     * 前后的分界是我的设计决定（not in M3）。
     */
    val containerGesture: (preview: Rect) -> FiniteAnimationSpec<Rect>,
)

/** 动效取值，全 App 唯一的映射：系统关掉动画就瞬时，否则是同一套。 */
fun appMotionFor(systemAnimationsOff: Boolean): AppMotion =
    if (systemAnimationsOff) OffMotion else StandardMotion

/** 当前的动效取值；[ChogumiTheme] 是唯一提供点。 */
val LocalAppMotion = staticCompositionLocalOf { appMotionFor(systemAnimationsOff = false) }

/**
 * 系统是否关掉了动画（开发者选项的「动画时长缩放」为 0，无障碍里的「移除动画」也写这一项）。
 * 设置改了会跟着变（监听这一项），App 在前台时从系统设置切回来就生效。
 */
@Composable
internal fun rememberSystemAnimationsOff(): State<Boolean> {
    val resolver = LocalContext.current.contentResolver
    val off = remember(resolver) { mutableStateOf(resolver.animationsOff()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                off.value = resolver.animationsOff()
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        // 注册之前那一刻改的也要读到
        off.value = resolver.animationsOff()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return off
}

private fun ContentResolver.animationsOff(): Boolean =
    Settings.Global.getFloat(this, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** 系统关掉动画：所有弹簧都折叠成瞬时（`snap`），状态变化在下一帧直接到位。 */
private object NoMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = snap()
}

// 缓动取自 transitions.md 的 Android 列。Emphasized 本身是两段路径缓动（PathEasing 要原生库，单元测试的 JVM 加载不了），
// 文档写明没有路径缓动的平台用 Standard 代替，所以「开始与结束都在屏幕上」的 Emphasized 位置都用 Standard。
private val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

private val Shake = spring<Float>(dampingRatio = 0.25f, stiffness = 1500f)

/** 淡出与淡入的分界：整段 300ms 里先 90ms 淡出，再 210ms 淡入（MDC fade through / shared axis 的 35% / 65%）。 */
private const val FadeOutMillis = 90
private const val FadeInMillis = 210

/** 容器转换：卡片展开到整页，开始与结束都在屏幕上，500ms（transitions.md「Card expanding to full screen」）。 */
private const val ContainerMillis = 500

private const val PredictiveScale = 0.9f

private const val Parallax = 0.25f

/** 预测性返回离开的页：从整页到走完（缩到 [PredictiveScale] 并淡出）。 */
private const val PredictiveMillis = 300

/** 淡出从这段时间的六成处开始：拖动多半停在这之前，预览不透明；松手后才淡出。 */
private const val PredictiveFadeDelay = PredictiveMillis * 6 / 10

private val OffPage = PageMotion(0.dp, snap(), snap(), snap(), Parallax, snap(), snap(), snap(), PredictiveScale, snap(), snap(), snap(), snap()) { snap() }

private val StandardPage = PageMotion(
    shift = 30.dp,
    exitFade = tween(FadeOutMillis, easing = LinearEasing),
    enterFade = tween(FadeInMillis, delayMillis = FadeOutMillis, easing = LinearEasing),
    slide = tween(400, easing = EmphasizedDecelerate),
    parallax = Parallax,
    previewShrink = tween(PredictiveMillis, easing = LinearEasing),
    previewShift = tween(PredictiveMillis, easing = LinearEasing),
    previewFade = tween(PredictiveMillis - PredictiveFadeDelay, delayMillis = PredictiveFadeDelay, easing = LinearEasing),
    previewScale = PredictiveScale,
    container = tween(ContainerMillis, easing = Standard),
    containerFadeIn = tween(ContainerMillis / 4, easing = LinearEasing),
    containerReturnFadeOut = tween(ContainerMillis * 3 / 10, delayMillis = ContainerMillis * 6 / 10, easing = LinearEasing),
    containerHold = snap(delayMillis = ContainerMillis),
    containerGesture = { preview ->
        keyframes {
            durationMillis = ContainerMillis
            preview at ContainerMillis / 2 using Standard
        }
    },
)

private val OffMotion = AppMotion(NoMotionScheme, NoMotionScheme, snap(), OffPage, animates = false)

private val StandardMotion = AppMotion(MotionScheme.standard(), MotionScheme.expressive(), Shake, StandardPage, animates = true)
