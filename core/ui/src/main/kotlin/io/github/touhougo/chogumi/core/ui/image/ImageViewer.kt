package io.github.touhougo.chogumi.core.ui.image

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.AppDropdownMenu
import io.github.touhougo.chogumi.core.ui.composables.AppMenuItem
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.keyedListSaver
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.launch

// 全屏看图的几何与阈值（设计决定，not in M3：M3 没有看图组件）。

/** 最大放大倍数、双击放大倍数。 */
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f

/** 上下拖动松手时，拖出这么远、或朝拖动方向甩得这么快，就关闭；否则弹回原位。 */
private val DismissDistance = 120.dp
private val DismissVelocityPerSecond = 1200.dp

/** 上下拖动时黑底与顶栏跟着变淡，拖出这么远时全透明。 */
private val DismissFadeDistance = 320.dp

/** 顶栏底托最深处的不透明度。 */
private const val TOP_SCRIM_ALPHA = 0.5f

/** 看图页的测试标签：每一页的手势区。 */
const val ImageViewerPageTag = "image_viewer_page"

/**
 * 上下拖动松手时关不关：拖出 [distance] 以上，或速度超过 [velocity] 且方向与拖动方向一致（往下拖着往上甩是想放回去）。
 * 参数都是像素（速度为每秒像素），往下为正。
 */
internal fun shouldDismiss(dragged: Float, releaseVelocity: Float, distance: Float, velocity: Float): Boolean =
    abs(dragged) >= distance || (abs(releaseVelocity) >= velocity && sign(releaseVelocity) == sign(dragged))

/**
 * 全屏看图（全 App 唯一的看图处）：帖子里的图、作品封面、角色 / 人物立绘、用户头像点开都到这里。
 *
 * - 一律取原图（[originalImageRequestOf]），原图到之前先显示内存里已有的那张小图；
 * - 左右翻页，[urls] 多于一张时顶上显示「当前 / 总数」；双指、双击缩放，放大后拖动不出图的边界；
 * - 单击、返回、上下拖动关闭（拖动时黑底与顶栏跟着变淡）；
 * - 长按弹出菜单：保存到相册、分享。不放常驻按钮，顶上只有关闭和页码。成功不提示，失败在看图页底部提示。
 *
 * 全屏图层不是面板，也不会跳页，所以用 Dialog（docs/code-standards.md 的面板规则针对的是能跳页的面板）。
 */
@Composable
fun ImageViewer(urls: List<String>, startIndex: Int, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val pagerState = rememberPagerState(initialPage = startIndex) { urls.size }
        val density = LocalDensity.current
        val spatial = LocalAppMotion.current.scheme.defaultSpatialSpec<Float>()
        val scope = rememberCoroutineScope()
        val drag = remember { Animatable(0f) }
        val failures = remember { FailureNotices() }
        val snackbar = remember { SnackbarHostState() }
        FailureNoticeEffect(failures, snackbar)
        val actions = rememberImageActions(failures)
        var menu by remember { mutableStateOf<ViewerMenu?>(null) }
        val fade = with(density) { DismissFadeDistance.toPx() }
        val shown = 1f - (abs(drag.value) / fade).coerceIn(0f, 1f)

        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = shown))) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().graphicsLayer { translationY = drag.value },
            ) { page ->
                ViewerPage(
                    url = urls[page],
                    onTap = onDismiss,
                    onLongPress = { at -> menu = ViewerMenu(urls[page], at) },
                    onDismissDrag = { dy -> scope.launch { drag.snapTo(drag.value + dy) } },
                    onDismissEnd = { velocity ->
                        val dismiss = with(density) {
                            shouldDismiss(drag.value, velocity, DismissDistance.toPx(), DismissVelocityPerSecond.toPx())
                        }
                        if (dismiss) onDismiss() else scope.launch { drag.animateTo(0f, spatial) }
                    },
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = shown }
                    // 白底的图铺到顶时，白色的关闭与页码要一层由黑到透明的底托着才看得见（设计决定，not in M3）
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = TOP_SCRIM_ALPHA), Color.Black.copy(alpha = 0f))))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(painterResource(R.drawable.close_24), contentDescription = stringResource(R.string.close), tint = Color.White)
                }
                if (urls.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${urls.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                }
            }
            menu?.let { shownMenu ->
                // 菜单从长按的那一点弹出：锚点是那一点上一个不占地方的框
                Box(Modifier.offset { IntOffset(shownMenu.at.x.roundToInt(), shownMenu.at.y.roundToInt()) }) {
                    AppDropdownMenu(expanded = true, onDismissRequest = { menu = null }) {
                        AppMenuItem(stringResource(R.string.image_save), onDismiss = { menu = null }, icon = R.drawable.download_24) {
                            actions.save(shownMenu.url)
                        }
                        AppMenuItem(stringResource(R.string.image_share), onDismiss = { menu = null }, icon = R.drawable.share_24) {
                            actions.share(shownMenu.url)
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

/** 长按弹出的菜单：针对哪张图、从哪一点弹出（看图页坐标）。 */
private class ViewerMenu(val url: String, val at: Offset)

/** 看图页的两个动作；失败经 [FailureNotices] 提示。 */
private class ImageActions(val save: (String) -> Unit, val share: (String) -> Unit)

@Composable
private fun rememberImageActions(failures: FailureNotices): ImageActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun saveNow(url: String) {
        scope.launch { if (!saveImageToGallery(context, url)) failures.post(R.string.image_save_failed) }
    }
    // API 28 及以下保存前要存储权限：申请时记下是哪张图，授权回来接着存；拒绝算保存失败
    var waitingForPermission by rememberSaveable { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val url = waitingForPermission ?: return@rememberLauncherForActivityResult
        waitingForPermission = null
        if (granted) saveNow(url) else failures.post(R.string.image_save_failed)
    }
    return ImageActions(
        save = { url ->
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            if (saveNeedsStoragePermission && !granted) {
                waitingForPermission = url
                permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                saveNow(url)
            }
        },
        share = { url -> scope.launch { if (!shareImage(context, url)) failures.post(R.string.image_share_failed) } },
    )
}

/**
 * 一页图已知的：原图的像素尺寸 [pixels]（宽, 高；加载成功前为 null）、加载失败了没有。
 * 放大后能拖多远由图实际画出的大小决定，所以跟着这一页存档：翻走再翻回来、Activity 重建后第一帧就是对的。
 */
private data class PageFacts(val pixels: IntSize? = null, val failed: Boolean = false)

private fun pageFactsSaver(url: String) = keyedListSaver<PageFacts>(
    key = url,
    save = { facts -> listOf(facts.failed) + facts.pixels?.let { listOf(it.width, it.height) }.orEmpty() },
    restore = { saved -> PageFacts(if (saved.size == 3) IntSize(saved[1] as Int, saved[2] as Int) else null, saved[0] as Boolean) },
)

/** 一页手势在按下后由第一段位移定下来：双指或已放大是缩放平移，单指竖着拖是关闭，单指横着拖交给外层翻页。 */
private enum class PageGesture { Undecided, Zoom, Dismiss, Pager }

/** 原图按 Fit 铺进 [box] 后画出来的大小；尺寸未知时当作铺满。 */
private fun fittedSize(pixels: IntSize?, box: IntSize): Size {
    if (pixels == null || pixels.width <= 0 || pixels.height <= 0) return Size(box.width.toFloat(), box.height.toFloat())
    val ratio = min(box.width.toFloat() / pixels.width, box.height.toFloat() / pixels.height)
    return Size(pixels.width * ratio, pixels.height * ratio)
}

/**
 * 看图的一页。单指在未放大时不消费横向位移，交给外层翻页；竖着拖交给 [onDismissDrag]（每次的位移，像素），松手时 [onDismissEnd]
 * 收到竖直速度。缩放围绕双指中点、双击围绕点按处，平移限制在图实际画出的范围内。
 */
@Composable
private fun ViewerPage(
    url: String,
    onTap: () -> Unit,
    onLongPress: (Offset) -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissEnd: (Float) -> Unit,
) {
    var facts by rememberSaveable(url, stateSaver = pageFactsSaver(url)) { mutableStateOf(PageFacts()) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(raw: Offset, atScale: Float): Offset {
        val content = fittedSize(facts.pixels, size)
        val maxX = max(0f, (content.width * atScale - size.width) / 2f)
        val maxY = max(0f, (content.height * atScale - size.height) / 2f)
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }
    /** 缩放到 [target] 倍，[focus]（页内坐标）下的那一点留在原处。 */
    fun zoomTo(target: Float, focus: Offset, pan: Offset = Offset.Zero) {
        val next = target.coerceIn(1f, MAX_SCALE)
        if (next <= 1f) {
            scale = 1f
            offset = Offset.Zero
            return
        }
        val center = Offset(size.width / 2f, size.height / 2f)
        val raw = focus + pan - center - (focus - center - offset) * (next / scale)
        scale = next
        offset = clamp(raw, next)
    }
    val actionsLabel = stringResource(R.string.image_actions)
    val context = LocalContext.current
    Box(
        Modifier
            .fillMaxSize()
            .testTag(ImageViewerPageTag)
            .onSizeChanged { size = it }
            .semantics {
                onClick { onTap(); true }
                onLongClick(actionsLabel) {
                    if (!facts.failed) onLongPress(Offset(size.width / 2f, size.height / 2f))
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { at -> if (!facts.failed) zoomTo(if (scale > 1f) 1f else DOUBLE_TAP_SCALE, at) },
                    onLongPress = { at -> if (!facts.failed) onLongPress(at) },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var gesture = PageGesture.Undecided
                    var travel = Offset.Zero
                    // 速度按累计的拖动量记：页面跟着手指移动，手指在页内的坐标几乎不变，按它算不出速度
                    var dragged = 0f
                    val tracker = VelocityTracker()
                    do {
                        val event = awaitPointerEvent()
                        val pan = event.calculatePan()
                        if (gesture == PageGesture.Undecided) {
                            if (event.changes.size > 1 || scale > 1f) {
                                gesture = PageGesture.Zoom
                            } else {
                                travel += pan
                                if (travel.getDistance() > viewConfiguration.touchSlop) {
                                    gesture = if (abs(travel.y) > abs(travel.x)) PageGesture.Dismiss else PageGesture.Pager
                                }
                            }
                        }
                        when (gesture) {
                            PageGesture.Zoom -> {
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (centroid != Offset.Unspecified) zoomTo(scale * event.calculateZoom(), centroid - pan, pan)
                                event.changes.fastForEach { if (it.positionChanged()) it.consume() }
                            }
                            PageGesture.Dismiss -> {
                                dragged += pan.y
                                tracker.addPosition(event.changes.first().uptimeMillis, Offset(0f, dragged))
                                onDismissDrag(pan.y)
                                event.changes.fastForEach { if (it.positionChanged()) it.consume() }
                            }
                            PageGesture.Undecided, PageGesture.Pager -> Unit
                        }
                    } while (event.changes.any { it.pressed })
                    if (gesture == PageGesture.Dismiss) onDismissEnd(tracker.calculateVelocity().y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (facts.failed) {
            FailedImage(url)
        } else {
            AsyncImage(
                model = remember(url, context) { originalImageRequestOf(context, url, placeholder = true) },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { facts = PageFacts(IntSize(it.result.image.width, it.result.image.height)) },
                onError = { facts = facts.copy(failed = true) },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}
