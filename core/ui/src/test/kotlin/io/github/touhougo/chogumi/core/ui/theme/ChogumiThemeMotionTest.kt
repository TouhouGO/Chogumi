package io.github.touhougo.chogumi.core.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [ChogumiTheme] 给的动效只有一套：M3 standard 方案（spatial 几乎不回弹、effects 不回弹）；
 * 系统关掉动画时一切瞬时，页面转场也是。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChogumiThemeMotionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver

    @After
    fun restoreSystemAnimations() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    /** 在主题里取出 Material 拿到的方案和 [LocalAppMotion]。 */
    private fun motionUnderTheme(): Pair<MotionScheme, AppMotion> {
        var scheme: MotionScheme? = null
        var motion: AppMotion? = null
        composeRule.setContent {
            ChogumiTheme {
                scheme = MaterialTheme.motionScheme
                motion = LocalAppMotion.current
            }
        }
        composeRule.waitForIdle()
        return scheme!! to motion!!
    }

    @Test
    fun `平时是 Standard 方案且 effects 不回弹`() {
        val (scheme, motion) = motionUnderTheme()
        val standard = MotionScheme.standard()
        assertEquals(standard.defaultSpatialSpec<Float>(), scheme.defaultSpatialSpec<Float>())
        assertEquals(standard.defaultEffectsSpec<Float>(), scheme.defaultEffectsSpec<Float>())
        assertEquals(standard.fastSpatialSpec<Float>(), motion.scheme.fastSpatialSpec<Float>())
        assertEquals("Effects 动效必须无回弹（damping = 1.0）", 1.0f, (scheme.fastEffectsSpec<Float>() as SpringSpec<Float>).dampingRatio)
        assertEquals(40L, motion.stagger(40L))
    }

    @Test
    fun `系统关掉动画时所有规格都是瞬时的`() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val (scheme, motion) = motionUnderTheme()
        val page = motion.page
        val specs = listOf(
            scheme.defaultSpatialSpec<Float>(), scheme.fastSpatialSpec(), scheme.slowSpatialSpec(),
            scheme.defaultEffectsSpec(), scheme.fastEffectsSpec(), scheme.slowEffectsSpec(),
            motion.shake, page.exitFade, page.enterFade, page.slide, page.previewShrink, page.previewShift, page.previewFade, page.container,
            page.containerFadeIn, page.containerReturnFadeOut, page.containerHold, page.containerGesture(Rect.Zero),
        )
        specs.forEach { assertTrue("系统关掉动画时规格必须瞬时：$it", it is SnapSpec<*> && it.delay == 0) }
        assertEquals("没有依次出场的间隔", 0L, motion.stagger(40L))
    }

    @Test
    fun `页面转场的时长与缓动取自 M3 转场表_淡出先于淡入`() {
        val page = motionUnderTheme().second.page
        val exit = page.exitFade as TweenSpec<Float>
        val enter = page.enterFade as TweenSpec<Float>
        assertEquals("淡出 90ms", 90, exit.durationMillis)
        assertEquals("淡入等淡出走完才开始", exit.durationMillis, enter.delay)
        assertEquals("淡入 210ms", 210, enter.durationMillis)
        assertEquals("前进后退整屏滑：进入屏幕 400ms", 400, (page.slide as TweenSpec).durationMillis)
        assertEquals("容器转换 500ms", 500, (page.container as TweenSpec).durationMillis)
    }
}
