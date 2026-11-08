package io.github.touhougo.chogumi.core.reader

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 非有限声明、普通单图、长图与宫格的设备画面；图片来自内存，不读取网络或真实 UGC。 */
@OptIn(DelicateCoilApi::class)
@VisualCapture
@RunWith(AndroidJUnit4::class)
class BBCodeImageFiniteCapture {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun fakeImages() {
        SingletonImageLoader.setUnsafe { context ->
            ImageLoader.Builder(context)
                .components {
                    add(Interceptor { chain ->
                        val url = chain.request.data.toString()
                        val (width, height) = if (url.endsWith("long.png")) 180 to 720 else 250 to 180
                        SuccessResult(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).asImage(), chain.request)
                    })
                }
                .build()
        }
    }

    @After
    fun resetImages() {
        SingletonImageLoader.reset()
    }

    @Test
    fun BBCode图片尺寸浅深色画面() {
        val dark = mutableStateOf(false)
        val ast = BBCodeParser.parse(
            """
            无效声明仍有可见占位
            [img=1e39,1]https://example.invalid/finite.png[/img]
            普通图片和长图
            [img=250,180]https://example.invalid/normal.png[/img]
            [img]https://example.invalid/long.png[/img]
            两张图片仍是宫格
            [img]https://example.invalid/grid-1.png[/img][img]https://example.invalid/grid-2.png[/img]
            """.trimIndent(),
        )
        compose.setContent {
            ChogumiTheme(darkTheme = dark.value) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(
                        Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BBCodeText(ast)
                    }
                }
            }
        }

        listOf(false, true).forEach { night ->
            compose.runOnIdle { dark.value = night }
            compose.waitForIdle()
            val width = with(compose.density) { compose.onRoot().captureToImage().width.toDp().value.toInt() }
            saveScreenshot(
                "bbcode-finite-images",
                "$width-${if (night) "dark" else "light"}",
                compose.onRoot().captureToImage().asAndroidBitmap(),
            )
        }
    }
}
