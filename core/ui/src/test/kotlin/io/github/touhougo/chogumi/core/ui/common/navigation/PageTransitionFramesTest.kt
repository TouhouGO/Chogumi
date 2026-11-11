package io.github.touhougo.chogumi.core.ui.common.navigation

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.composables.CarouselSlot
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import io.github.touhougo.chogumi.core.ui.theme.PageMotion
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 页面转场一帧一帧推：时钟手动前进，读两页的位置和画面。第一层纯红、更深的页纯蓝、背景纯黑；
 * 淡入淡出的转场里同一点红蓝同时出现、滑动的转场里出现纯红纯蓝以外的像素，都是两页半透明叠在了一起。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalSharedTransitionApi::class)
class PageTransitionFramesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
    private val backStack = mutableStateListOf<NavKey>(Route.Home)
    private val registry = PageExpandRegistry()
    private var kind = PageTransitionKind.Forward
    private var shiftPx = 0
    private lateinit var motionPage: PageMotion
    private val gesture = DirectNavigationEventInput()
    private val contentKeys = HashMap<NavKey, Any>()

    @After
    fun restoreSystemAnimations() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    /** 返回时的种类：和 `AppNavigator.transitionKind(back = true, …)` 一样，压进来的页返回时是 [PageTransitionKind.Back]。 */
    private fun popKind() = if (kind == PageTransitionKind.Forward) PageTransitionKind.Back else kind

    private fun colorOf(key: NavKey) = if (key == Route.Home) Color.Red else Color.Blue

    private fun openDetails() {
        registry.claim(Route.Home, Route.SubjectDetails(1), from = Route.Home)
        backStack.add(Route.SubjectDetails(1))
    }

    /** 官方 multi-browse 轮播：大、中、小项都被遮罩收窄；只有第 [CarouselTarget] 项盖绿色。 */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun carousel() {
        HorizontalMultiBrowseCarousel(
            state = rememberCarouselState { 6 },
            preferredItemWidth = 120.dp,
            itemSpacing = 8.dp,
            modifier = Modifier.padding(top = 120.dp).width(320.dp).height(160.dp),
        ) { index ->
            CarouselSlot(MaterialTheme.shapes.extraLarge, Modifier.fillMaxSize()) {
                SubjectPoster(url = null, modifier = Modifier.testTag("poster$index").fillMaxSize(), onClick = ::openDetails) {
                    if (index == CarouselTarget) Box(Modifier.matchParentSize().background(Color.Green))
                }
            }
        }
    }

    /** 封面加标题、整块可点的卡片（像列表的一行）：标题那一块不是封面。 */
    @Composable
    private fun titledCard() {
        PressArea(onClick = ::openDetails, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(start = 80.dp, top = 120.dp)) {
            Column {
                SubjectPoster(url = null, modifier = Modifier.size(PosterSize)) {
                    Box(Modifier.matchParentSize().background(Color.Green))
                }
                Box(Modifier.testTag("title").size(PosterSize, 40.dp))
            }
        }
    }

    /** 列表的可视区（裁到自身边界）只露出封面的下半截，像滚到顶栏下面一半的那一项。 */
    @Composable
    private fun clippedPoster() {
        Box(Modifier.padding(start = 80.dp, top = 120.dp).size(PosterSize, PosterSize / 2).clipToBounds()) {
            Box(
                Modifier.layout { measurable, _ ->
                    val side = PosterSize.roundToPx()
                    val placeable = measurable.measure(Constraints.fixed(side, side))
                    layout(side, side / 2) { placeable.place(0, -side / 2) }
                },
            ) {
                SubjectPoster(url = null, modifier = Modifier.testTag("poster").fillMaxSize(), onClick = ::openDetails) {
                    Box(Modifier.matchParentSize().background(Color.Green))
                }
            }
        }
    }

    private fun setContent(withShared: Boolean = false, poster: Boolean = false, carousel: Boolean = false, intrinsic: Boolean = false, clipped: Boolean = false, titled: Boolean = false, simpleBack: Boolean = false) {
        composeRule.setContent {
            val dispatcher = LocalNavigationEventDispatcherOwner.current!!.navigationEventDispatcher
            DisposableEffect(dispatcher) {
                dispatcher.addInput(gesture)
                onDispose { dispatcher.removeInput(gesture) }
            }
            ChogumiTheme {
                val page = LocalAppMotion.current.page
                motionPage = page
                shiftPx = with(LocalDensity.current) { page.shift.roundToPx() }
                SharedTransitionLayout(Modifier.fillMaxSize().background(Color.Black)) {
                    CompositionLocalProvider(
                        LocalSharedTransitionScope provides if (withShared) this else null,
                        LocalPageExpandRegistry provides registry,
                    ) {
                        NavDisplay(
                            backStack = backStack,
                            // 与 AppNavigator 同序：共享标记包住页面底色，可保存状态让回来的列表页认得自己的封面
                            entryDecorators = if (withShared) {
                                listOf(
                                    rememberPageExpandNavEntryDecorator(Route.Home),
                                    rememberPageSurfaceNavEntryDecorator(),
                                    rememberEnteringBackNavEntryDecorator(isTopPage = { contentKey -> backStack.size > 1 && contentKey == contentKeys[backStack.last()] }, goBack = { backStack.removeAt(backStack.lastIndex) }),
                                    rememberSaveableStateHolderNavEntryDecorator(),
                                )
                            } else {
                                emptyList()
                            },
                            onBack = { backStack.removeAt(backStack.lastIndex) },
                            transitionSpec = { chogumiTransition(kind, page, 1) },
                            popTransitionSpec = { chogumiTransition(popKind(), page, 1) },
                            predictivePopTransitionSpec = { _ -> chogumiPredictivePopTransition(popKind(), page, 1, shiftPx, simpleBack) },
                            entryProvider = { key ->
                                NavEntry(key) {
                                    Box(Modifier.fillMaxSize().testTag(key.toString()).background(colorOf(key))) {
                                        if (carousel && key == Route.Home) {
                                            carousel()
                                        } else if (clipped && key == Route.Home) {
                                            clippedPoster()
                                        } else if (titled && key == Route.Home) {
                                            titledCard()
                                        } else if (poster && key == Route.Home) {
                                            // intrinsic：像追更时间表那样，外层一行取最高的那格（问固有尺寸）
                                            Row(Modifier.padding(start = 80.dp, top = 120.dp).then(if (intrinsic) Modifier.height(IntrinsicSize.Max) else Modifier)) {
                                                SubjectPoster(
                                                    url = null,
                                                    modifier = Modifier.testTag("poster").then(if (intrinsic) Modifier.width(PosterSize) else Modifier.size(PosterSize)),
                                                    onClick = ::openDetails,
                                                ) {
                                                    Box(Modifier.matchParentSize().background(Color.Green))
                                                }
                                                // 旁边一格定下这一行的高度（封面 3:4 的高）
                                                if (intrinsic) Box(Modifier.width(1.dp).height(PosterSize * 4 / 3))
                                            }
                                        }
                                    }
                                }.also {
                                    registry.register(Route.Home, it.contentKey, key as Route)
                                    contentKeys[key] = it.contentKey
                                }
                            },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun leftOf(route: Route): Float? =
        runCatching { composeRule.onNodeWithTag(route.toString()).fetchSemanticsNode().boundsInRoot.left }.getOrNull()

    /** 画面中心点的颜色。 */
    private fun centre(): Color {
        val image = composeRule.onRoot().captureToImage()
        return image.toPixelMap()[image.width / 2, image.height / 2]
    }

    private fun Color.hasRed() = red > 0.03f
    private fun Color.hasBlue() = blue > 0.03f

    /** 画面中间那一行，每个像素的颜色。 */
    private fun middleRow(): List<Color> {
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        return List(pixels.width) { x -> pixels[x, pixels.height / 2] }
    }

    /** 一帧里两页的位置和中间一行的画面。 */
    private class SlideFrame(val upper: Float?, val lower: Float?, val row: List<Color>)

    /** 推 [count] 帧，每帧记下 [upper]（盖在上面的页）、[lower] 的左边缘和中间一行。 */
    private fun slideFrames(count: Int, upper: Route, lower: Route): List<SlideFrame> = List(count) {
        composeRule.mainClock.advanceTimeByFrame()
        SlideFrame(leftOf(upper), leftOf(lower), middleRow())
    }

    /**
     * 视差式的前进后退：每一帧画面只有纯红纯蓝（两页都不透明，没有半透明混色，也不露黑底），
     * [upper] 盖在 [lower] 上（它左边缘里侧是它的颜色、外侧是下面那页的颜色），下面那页只走页宽的 [PageMotion.parallax]。
     */
    private fun assertParallaxSlide(frames: List<SlideFrame>, upper: Color, lower: Color) {
        val parallax = composeRule.runOnIdle { motionPage.parallax }
        frames.forEachIndexed { index, frame ->
            assertTrue("第 $index 帧有不是纯红纯蓝的像素（半透明或露底）", frame.row.all { it == Color.Red || it == Color.Blue })
            val width = frame.row.size
            val edge = frame.upper?.toInt() ?: return@forEachIndexed
            if (edge in 2 until width - 2) {
                assertEquals("第 $index 帧上面那页的左边缘里侧", upper, frame.row[edge + 1])
                assertEquals("第 $index 帧上面那页的左边缘外侧是下面那页", lower, frame.row[edge - 2])
            }
            frame.lower?.let { assertTrue("第 $index 帧下面那页只走一小段：$it", it <= 0f && it >= -width * parallax - 1f) }
        }
        assertTrue("有两页并排的帧（在滑，不是淡）", frames.any { f -> f.row.contains(Color.Red) && f.row.contains(Color.Blue) })
    }

    @Test
    fun 前进_新页不透明地从右侧整屏滑进来盖住旧页_旧页同时慢慢往左退() {
        setContent()
        composeRule.mainClock.autoAdvance = false
        backStack.add(Route.Explore)
        val frames = slideFrames(30, upper = Route.Explore, lower = Route.Home)
        assertParallaxSlide(frames, upper = Color.Blue, lower = Color.Red)
        val width = frames.first().row.size
        val lefts = frames.mapNotNull { it.upper }
        assertTrue("新页从右侧屏外进来：$lefts", lefts.first() > width / 2f)
        assertEquals("新页只往左走", lefts.sortedDescending(), lefts)
        val behind = frames.mapNotNull { it.lower }
        assertEquals("旧页只往左走", behind.sortedDescending(), behind)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(0f, leftOf(Route.Explore))
        assertEquals("转完旧页离开组合", null, leftOf(Route.Home))
    }

    @Test
    fun 返回_当前页整屏滑回右侧_下面的页从左边慢慢回位() {
        backStack.add(Route.Explore)
        setContent()
        composeRule.mainClock.autoAdvance = false
        backStack.removeAt(backStack.lastIndex)
        val frames = slideFrames(30, upper = Route.Explore, lower = Route.Home)
        assertParallaxSlide(frames, upper = Color.Blue, lower = Color.Red)
        val lefts = frames.mapNotNull { it.upper }
        assertEquals("离开的页只往右走", lefts.sorted(), lefts)
        val behind = frames.mapNotNull { it.lower }
        assertTrue("下面的页从左边一小段外回来：$behind", behind.first() < 0f)
        assertEquals("下面的页只往右走", behind.sorted(), behind)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(Color.Red, centre())
        assertEquals("转完离开的页离开组合", null, leftOf(Route.Explore))
    }

    @Test
    fun 连续返回两层_离开的页始终盖在上面() {
        backStack.add(Route.Explore)
        backStack.add(Route.Settings)
        setContent()
        composeRule.mainClock.autoAdvance = false
        backStack.removeAt(backStack.lastIndex)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        backStack.removeAt(backStack.lastIndex)
        assertParallaxSlide(slideFrames(30, upper = Route.Explore, lower = Route.Home), upper = Color.Blue, lower = Color.Red)
    }

    @Test
    fun 切tab_只淡出再淡入_页面不位移() {
        kind = PageTransitionKind.TopLevel
        setContent()
        composeRule.mainClock.autoAdvance = false
        backStack.add(Route.Explore)
        val lefts = ArrayList<Float?>()
        val seen = List(30) {
            composeRule.mainClock.advanceTimeByFrame()
            lefts += leftOf(Route.Explore)
            centre()
        }
        assertTrue("页面只淡入，每一帧都在位：$lefts", lefts.filterNotNull().all { it == 0f })
        assertTrue("红蓝同时出现 = 两页半透明叠在一起：$seen", seen.none { it.hasRed() && it.hasBlue() })
    }

    @Test
    fun 系统关掉动画_目标出现后的下一帧整页到位() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        setContent()
        composeRule.mainClock.autoAdvance = false
        backStack.add(Route.Explore)
        val destination = composeRule.onAllNodesWithTag(Route.Explore.toString())
        repeat(10) {
            if (destination.fetchSemanticsNodes().isEmpty()) composeRule.mainClock.advanceTimeByFrame()
        }
        assertTrue("目标页已经进入组合", destination.fetchSemanticsNodes().isNotEmpty())
        composeRule.mainClock.advanceTimeByFrame()
        val row = middleRow()
        assertTrue("目标页出现后的下一帧已铺满，没有仍在滑动的旧页：${row.distinct()}", row.all { it == Color.Blue })
        assertEquals("新页在最终位置", 0f, leftOf(Route.Explore))
    }

    private fun Color.isBlack() = red + green + blue < 0.3f

    /** 右下角的颜色：容器长到这里之前是列表页，之后是详情页。 */
    private fun corner(): Color {
        val image = composeRule.onRoot().captureToImage()
        return image.toPixelMap()[image.width - 2, image.height - 2]
    }

    @Test
    fun 容器转换_详情页由封面长成整页_列表页留到转换结束_不露黑底() {
        kind = PageTransitionKind.Container
        setContent(withShared = true, poster = true)
        composeRule.onNodeWithTag("poster").performTouchInput { click() }
        composeRule.mainClock.autoAdvance = false
        // 详情页第一次组合在真机上要卡几帧：卡顿之后转换要接着长，不能当帧跳到终点
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(StallMillis)
        val grown = List(40) {
            composeRule.mainClock.advanceTimeByFrame()
            corner()
        }
        assertTrue("卡顿后容器还在长，右下角仍是列表页：$grown", grown.take(10).all { it == Color.Red })
        assertTrue("展开途中列表页一直在底下（露黑底就是旧页当帧消失了）：$grown", grown.none { it.isBlack() })
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals("展开完详情页铺满", Color.Blue, corner())

        composeRule.mainClock.autoAdvance = false
        backStack.removeAt(backStack.lastIndex)
        val shrunk = List(40) {
            composeRule.mainClock.advanceTimeByFrame()
            corner()
        }
        assertTrue("缩回途中不露黑底：$shrunk", shrunk.none { it.isBlack() })
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("poster").assertExists()
        assertEquals("缩回后列表页铺满", Color.Red, corner())
    }

    /** 画面里纯绿（封面）占的比例。 */
    private fun greenShare(): Float {
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        var green = 0
        var all = 0
        for (y in 0 until pixels.height step 4) for (x in 0 until pixels.width step 4) {
            val c = pixels[x, y]
            all++
            if (c.green > 0.6f && c.red < 0.3f && c.blue < 0.3f) green++
        }
        return green.toFloat() / all
    }

    @Test
    fun 容器转换_展开途中拖动返回_封面不会当帧放大盖住整页() {
        kind = PageTransitionKind.Container
        setContent(withShared = true, poster = true)
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("poster").performTouchInput { click() }
        // 长到一半多：详情页已经淡入、盖在封面上
        repeat(18) { composeRule.mainClock.advanceTimeByFrame() }
        assertTrue("打断前详情页盖在上面", greenShare() < 0.05f)

        gesture.backStarted(NavigationEvent(touchX = 0f, touchY = 300f, progress = 0f, swipeEdge = NavigationEvent.EDGE_LEFT))
        val dragged = List(10) { i ->
            gesture.backProgressed(NavigationEvent(touchX = 10f * i, touchY = 300f, progress = 0.02f * i, swipeEdge = NavigationEvent.EDGE_LEFT))
            composeRule.mainClock.advanceTimeByFrame()
            greenShare()
        }
        gesture.backCompleted()
        val shares = dragged + List(40) {
            composeRule.mainClock.advanceTimeByFrame()
            greenShare()
        }
        assertTrue("缩回途中封面不会撑大到盖住大半个屏幕（逐帧绿色占比 $shares）", shares.all { it < 0.2f })
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals("缩回后列表页铺满", Color.Red, corner())
    }

    /** 画面里纯绿（封面）的像素数，逐个像素数，圆角外露出的一小角也算。 */
    private fun greenPixels(): Int {
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        var green = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val c = pixels[x, y]
            if (c.green > 0.6f && c.red < 0.3f && c.blue < 0.3f) green++
        }
        return green
    }

    @Test
    fun 容器转换_详情页淡入后_容器四角也不露出底下的封面() {
        kind = PageTransitionKind.Container
        setContent(withShared = true, poster = true)
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("poster").performTouchInput { click() }
        // 详情页在前 25% 淡入完，之后容器里（四角在内）只该是详情页
        repeat(10) { composeRule.mainClock.advanceTimeByFrame() }
        val opening = List(14) {
            composeRule.mainClock.advanceTimeByFrame()
            greenPixels()
        }
        assertTrue("展开途中封面从容器四角露出来（逐帧绿色像素数 $opening）", opening.all { it == 0 })
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        composeRule.mainClock.autoAdvance = false
        backStack.removeAt(backStack.lastIndex)
        // 详情页在 60% 才开始淡出：之前四角也只该是详情页
        val returning = List(16) {
            composeRule.mainClock.advanceTimeByFrame()
            greenPixels()
        }
        assertTrue("缩回前段封面从容器四角露出来（逐帧绿色像素数 $returning）", returning.all { it == 0 })
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w900dp-h560dp-land")
    fun 容器转换_横屏宽页面_详情页淡入后整个容器都是详情页_封面不从底下露出来() {
        kind = PageTransitionKind.Container
        // 3:4 的封面长成横着的整页
        setContent(withShared = true, poster = true, intrinsic = true)
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("poster").performTouchInput { click() }
        // 详情页在前 25% 淡入完；之后容器里只该看到详情页
        repeat(8) { composeRule.mainClock.advanceTimeByFrame() }
        val opening = List(20) {
            composeRule.mainClock.advanceTimeByFrame()
            greenShare()
        }
        assertTrue("展开途中容器里露出封面（逐帧绿色占比 $opening）", opening.all { it < 0.005f })
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        composeRule.mainClock.autoAdvance = false
        backStack.removeAt(backStack.lastIndex)
        // 详情页在 60% 才开始淡出：之前容器里也只该是详情页
        val returning = List(16) {
            composeRule.mainClock.advanceTimeByFrame()
            greenShare()
        }
        assertTrue("缩回前段容器里露出封面（逐帧绿色占比 $returning）", returning.all { it < 0.005f })
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    @Test
    fun 容器转换_点卡片的标题而不是封面_进的页也认领这张封面() {
        kind = PageTransitionKind.Container
        setContent(withShared = true, titled = true)
        composeRule.onNodeWithTag("title", useUnmergedTree = true).performTouchInput { click() }
        composeRule.waitForIdle()
        assertTrue("点标题进的页认领了卡片里的封面", registry.expandsPage(Route.Home, contentKeys.getValue(Route.SubjectDetails(1))))
    }

    /** 画面里纯绿（封面）的外框；没有绿色时为 null。 */
    private fun greenBox(): IntRect? {
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        var box: IntRect? = null
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val c = pixels[x, y]
            if (c.green > 0.8f && c.red < 0.2f && c.blue < 0.2f) {
                box = box?.let { IntRect(minOf(it.left, x), minOf(it.top, y), maxOf(it.right, x + 1), maxOf(it.bottom, y + 1)) } ?: IntRect(x, y, x + 1, y + 1)
            }
        }
        return box
    }

    /** 画面里纯蓝（详情页）的外框。 */
    private fun blueBox(): IntRect? {
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        var box: IntRect? = null
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val c = pixels[x, y]
            if (c.blue > 0.8f && c.red < 0.2f && c.green < 0.2f) {
                box = box?.let { IntRect(minOf(it.left, x), minOf(it.top, y), maxOf(it.right, x + 1), maxOf(it.bottom, y + 1)) } ?: IntRect(x, y, x + 1, y + 1)
            }
        }
        return box
    }

    @Test
    fun 容器转换_轮播里被遮罩收窄的封面_从看得见的那一块长出_缩回时落回那一块_不盖住旁边() {
        kind = PageTransitionKind.Container
        setContent(withShared = true, carousel = true)
        val resting = greenBox()!!
        val slot = composeRule.onNodeWithTag("poster$CarouselTarget").fetchSemanticsNode().boundsInRoot
        assertTrue("这一项被遮罩收窄了（看得见 $resting，整项 $slot）", resting.width < slot.width - 8)

        growsFromAndLandsOn(resting)
    }

    @Test
    fun 容器转换_一半滚出列表可视区的封面_从露出的那一截长出_缩回时落回那一截() {
        kind = PageTransitionKind.Container
        setContent(withShared = true, clipped = true)
        val resting = greenBox()!!
        assertEquals("只露出下半截", with(composeRule.density) { (PosterSize / 2).roundToPx() }, resting.height)
        growsFromAndLandsOn(resting)
    }

    /** 点看得见的那一块：第一帧从它长出，缩回时最后两帧已落在它上面。 */
    private fun growsFromAndLandsOn(resting: IntRect) {
        composeRule.mainClock.autoAdvance = false
        composeRule.onRoot().performTouchInput { click(resting.center.toOffset()) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        val start = greenBox()
        assertTrue("长出的第一帧就是看得见的那一块（$start，停着是 $resting）", start != null && start.near(resting, tolerance = 8))
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        composeRule.mainClock.autoAdvance = false
        backStack.removeAt(backStack.lastIndex)
        // 转换 500ms：最后两帧详情页已淡出完、边界几乎到位
        val landing = List(31) {
            composeRule.mainClock.advanceTimeByFrame()
            greenBox()
        }.takeLast(2)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals("转完封面回到原处", resting, greenBox())
        for (box in landing) {
            assertTrue("落地前封面就在看得见的那一块（$box，转完是 $resting）", box != null && box.near(resting))
        }
    }

    @Test
    fun 容器转换_外层问固有尺寸的封面_点进去再回来_不崩() {
        kind = PageTransitionKind.Container
        setContent(withShared = true, poster = true, intrinsic = true)
        composeRule.onNodeWithTag("poster").performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.runOnIdle { backStack.removeAt(backStack.lastIndex) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("poster").assertExists()
        assertEquals("回来后封面仍在原处", greenBox()?.width, with(composeRule.density) { PosterSize.roundToPx() })
    }

    private fun IntRect.near(other: IntRect, tolerance: Int = 4) =
        abs(left - other.left) <= tolerance && abs(top - other.top) <= tolerance &&
            abs(right - other.right) <= tolerance && abs(bottom - other.bottom) <= tolerance

    /** 整屏的蓝（详情页）有多少：每个像素蓝色通道的平均值。淡出时连续变小，整页当帧消失时一下子掉到 0。 */
    private fun blueness(): Float {
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        var sum = 0f
        for (y in 0 until pixels.height step 4) for (x in 0 until pixels.width step 4) sum += pixels[x, y].blue
        return sum / ((pixels.height + 3) / 4 * ((pixels.width + 3) / 4))
    }

    @Test
    fun 前进途中拖动返回_新页从此刻的位置滑回去_不跳到预览起点也不整页消失() {
        setContent(withShared = true)
        composeRule.mainClock.autoAdvance = false
        backStack.add(Route.SubjectDetails(1))
        // 新页第一次组合后滑进来大半
        val entering = List(8) {
            composeRule.mainClock.advanceTimeByFrame()
            blueBox()
        }
        val before = entering.last()!!.left
        assertTrue("打断前新页还在滑进来：$entering", before > 0)

        gesture.backStarted(NavigationEvent(touchX = 0f, touchY = 300f, progress = 0f, swipeEdge = NavigationEvent.EDGE_LEFT))
        val dragged = List(8) { i ->
            gesture.backProgressed(NavigationEvent(touchX = 10f * i, touchY = 300f, progress = 0.03f * i, swipeEdge = NavigationEvent.EDGE_LEFT))
            composeRule.mainClock.advanceTimeByFrame()
            blueBox()
        }
        gesture.backCompleted()
        val released = List(30) {
            composeRule.mainClock.advanceTimeByFrame()
            blueBox()
        }
        val width = composeRule.onRoot().fetchSemanticsNode().size.width
        val frames = dragged + released
        assertTrue("新页一直是贴着右边的整页，不是缩小的预览：$frames", frames.filterNotNull().all { it.right == width && it.top == 0 })
        val lefts = listOf(before) + frames.map { it?.left ?: width }
        assertTrue("没有一帧往左跳出一大截（跳到预览起点）：$lefts", lefts.zipWithNext().all { (a, b) -> a - b < width / 10 })
        val gone = lefts.indexOfFirst { it == width }
        assertTrue("新页滑回右边才离开，不在半屏处整页消失：$lefts", gone > 0 && lefts[gone - 1] > width * 3 / 4)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals("回到上一页", Color.Red, centre())
        assertEquals("只退了一页", listOf<NavKey>(Route.Home), backStack.toList())
    }

    @Test
    fun 进场走完后拖动返回_照常是预测性返回_只退一页() {
        setContent(withShared = true)
        backStack.add(Route.SubjectDetails(1))
        composeRule.waitForIdle()
        composeRule.runOnIdle { gesture.backStarted(NavigationEvent(touchX = 0f, touchY = 300f, progress = 0f, swipeEdge = NavigationEvent.EDGE_LEFT)) }
        composeRule.runOnIdle { gesture.backProgressed(NavigationEvent(touchX = 120f, touchY = 300f, progress = 0.3f, swipeEdge = NavigationEvent.EDGE_LEFT)) }
        composeRule.waitForIdle()
        val screen = composeRule.onRoot().fetchSemanticsNode().size
        val preview = blueBox()
        assertTrue("拖动时是缩小的预览：$preview / $screen", preview != null && preview.width < screen.width)
        composeRule.runOnIdle { gesture.backCompleted() }
        composeRule.waitForIdle()
        assertEquals("只退了一页（下面那页进场时没有再接一次返回）", listOf<NavKey>(Route.Home), backStack.toList())
    }

    @Test
    fun 预测性返回_普通页拖动时缩成圆角预览_松手后淡出_不会当帧消失() {
        setContent(withShared = true)
        backStack.add(Route.SubjectDetails(1))
        composeRule.waitForIdle()
        val screen = composeRule.onRoot().fetchSemanticsNode().size

        composeRule.runOnIdle { gesture.backStarted(NavigationEvent(touchX = 0f, touchY = 300f, progress = 0f, swipeEdge = NavigationEvent.EDGE_LEFT)) }
        composeRule.runOnIdle { gesture.backProgressed(NavigationEvent(touchX = 120f, touchY = 300f, progress = 0.3f, swipeEdge = NavigationEvent.EDGE_LEFT)) }
        composeRule.waitForIdle()
        val preview = blueBox()
        assertTrue("拖动时缩小了：$preview / $screen", preview != null && preview.width < screen.width && preview.width >= screen.width * 0.9f)
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        assertTrue("预览左上角是圆的", pixels[preview!!.left, preview.top].blue < 0.8f)
        assertEquals("预览还是不透明的", 1f, pixels[preview.center.x, preview.center.y].blue, 0.01f)

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { gesture.backCompleted() }
        val seen = mutableListOf(blueness())
        repeat(30) {
            composeRule.mainClock.advanceTimeByFrame()
            seen += blueness()
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        val drops = seen.zipWithNext { a, b -> a - b }
        assertTrue("松手后详情页逐帧淡出，没有一帧里整页消失：$seen", drops.all { it < 0.35f })
        assertEquals("最后回到上一页", Color.Red, centre())
    }

    @Test
    fun 简单返回动画_拖动返回时整页往回滑走_不缩小() {
        setContent(withShared = true, simpleBack = true)
        backStack.add(Route.SubjectDetails(1))
        composeRule.waitForIdle()
        val screen = composeRule.onRoot().fetchSemanticsNode().size

        composeRule.runOnIdle { gesture.backStarted(NavigationEvent(touchX = 0f, touchY = 300f, progress = 0f, swipeEdge = NavigationEvent.EDGE_LEFT)) }
        composeRule.runOnIdle { gesture.backProgressed(NavigationEvent(touchX = 120f, touchY = 300f, progress = 0.3f, swipeEdge = NavigationEvent.EDGE_LEFT)) }
        composeRule.waitForIdle()
        val page = blueBox()
        assertTrue("拖动时离开的页是整页大小、往行尾一侧挪了：$page / $screen", page != null && page.height == screen.height && page.left > 0)

        composeRule.runOnIdle { gesture.backCompleted() }
        composeRule.waitForIdle()
        assertEquals("最后回到上一页", Color.Red, centre())
    }

    @Test
    fun 预测性返回_封面长出来的页_拖动时先缩成带圆角的预览_不是一动就缩成小卡片() {
        kind = PageTransitionKind.Container
        setContent(withShared = true, poster = true)
        composeRule.onNodeWithTag("poster").performTouchInput { click() }
        composeRule.waitForIdle()
        val screen = composeRule.onRoot().fetchSemanticsNode().size

        composeRule.runOnIdle { gesture.backStarted(NavigationEvent(touchX = 0f, touchY = 300f, progress = 0f, swipeEdge = NavigationEvent.EDGE_LEFT)) }
        composeRule.runOnIdle { gesture.backProgressed(NavigationEvent(touchX = 120f, touchY = 300f, progress = 0.3f, swipeEdge = NavigationEvent.EDGE_LEFT)) }
        composeRule.waitForIdle()
        val preview = blueBox()
        assertTrue("拖到 30% 时详情页仍是接近整页的预览：$preview / $screen", preview != null && preview.width >= screen.width * 0.9f)
        assertTrue("预览已经离开整页：$preview / $screen", preview!!.width < screen.width)
        val corner = composeRule.onRoot().captureToImage().toPixelMap()[preview.left, preview.top]
        assertTrue("预览四角是圆的：左上角 $corner", corner.blue < 0.8f)

        composeRule.runOnIdle { gesture.backCompleted() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("poster").assertExists()
        assertEquals("返回后列表页铺满", Color.Red, corner())
    }

    private companion object {
        val PosterSize = 60.dp
        const val CarouselTarget = 2

        /** 详情页第一次组合在真机上卡住的时长量级。 */
        const val StallMillis = 120L
    }
}
