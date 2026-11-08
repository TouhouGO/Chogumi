package io.github.touhougo.chogumi.core.reader

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import kotlinx.coroutines.awaitCancellation
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 外部 BBCode 声明的坏尺寸不能把真实的图片节点排成零高。 */
@OptIn(DelicateCoilApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w320dp-h640dp-mdpi")
class BBCodeImageFiniteRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val imageOpener = SemanticsMatcher("查看图片点击区") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "查看图片"
    }

    @After
    fun resetImageLoader() {
        SingletonImageLoader.reset()
    }

    @Test
    fun `BBCodeText 把溢出到 Float 正无穷的图片声明当未知尺寸`() {
        keepImageLoading()
        val ast = BBCodeParser.parse("[img=1e39,1]https://example.invalid/picture.png[/img]")

        compose.setContent {
            ChogumiTheme {
                Box(Modifier.width(320.dp)) { BBCodeText(ast) }
            }
        }

        val bounds = compose.onNode(imageOpener).getUnclippedBoundsInRoot()
        assertEquals(320.dp, bounds.width, "仍渲染真实图片节点，并占满正文栏宽")
        assertEquals(180.dp, bounds.height, "无效声明沿用现有 16:9 未知尺寸占位")
    }

    @Test
    fun `加载到普通像素并从存档恢复后仍使用真实比例`() {
        SingletonImageLoader.setUnsafe { context ->
            ImageLoader.Builder(context)
                .components {
                    add(Interceptor { chain ->
                        SuccessResult(Bitmap.createBitmap(250, 180, Bitmap.Config.ARGB_8888).asImage(), chain.request)
                    })
                }
                .build()
        }
        val ast = BBCodeParser.parse("[img]https://example.invalid/picture.png[/img]")
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ChogumiTheme {
                Box(Modifier.width(320.dp)) { BBCodeText(ast) }
            }
        }
        compose.waitForIdle()
        assertEquals(250.dp, imageBounds().width, "加载的真实宽度不被拉伸")
        assertEquals(180.dp, imageBounds().height, "加载的真实比例生效")

        restoration.emulateSavedInstanceStateRestore()

        assertEquals(250.dp, imageBounds().width, "存档恢复保留真实宽度")
        assertEquals(180.dp, imageBounds().height, "存档恢复保留真实比例")
    }

    private fun imageBounds() = compose.onNode(imageOpener).getUnclippedBoundsInRoot()

    private fun keepImageLoading() {
        SingletonImageLoader.setUnsafe { context ->
            ImageLoader.Builder(context)
                .components { add(Interceptor { awaitCancellation() }) }
                .build()
        }
    }
}
