package io.github.touhougo.chogumi.core.reader

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * 楼层里的单张图划出列表再回来，首帧就是加载后的尺寸（原图尺寸或「加载失败」小条），不再先按 16:9 占位、下一帧再缩。
 *
 * 只记在组合里时，往回滑到有图的那一层：它从顶上进来按占位排，下一帧缩成真实尺寸、整项缩出屏幕被丢掉，
 * 再往回滑又按占位进来——列表卡在这一段来回跳，回不到更早的楼。这里用与楼层相同的 [FoldableText] 画那一层，
 * 图片由假的图片加载器立即给出结果（Robolectric 没有网络）。
 */
@OptIn(DelicateCoilApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w360dp-h640dp")
class BBCodeImageReentryTest {

    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun resetImageLoader() {
        SingletonImageLoader.reset()
    }

    @Test
    fun 加载出的图往回滑重新进来时首帧就是原图尺寸() {
        // 120×60 像素：按 1px≈1dp 画成 120×60dp，比 16:9 占位（360 栏宽 → 约 200dp 高）矮得多
        assertReentersAtSettledSize { request -> SuccessResult(Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888).asImage(), request) }
    }

    @Test
    fun 加载失败的图往回滑重新进来时首帧就是失败小条() {
        assertReentersAtSettledSize { request -> ErrorResult(null, request, IllegalStateException("图床已失效")) }
    }

    private fun assertReentersAtSettledSize(result: (ImageRequest) -> ImageResult) {
        SingletonImageLoader.setUnsafe { context ->
            ImageLoader.Builder(context).components { add(Interceptor { chain -> result(chain.request) }) }.build()
        }
        val ast = BBCodeParser.parse("开头一行\n[img]https://example.invalid/picture.png[/img]")
        val list = LazyListState()
        val heights = mutableListOf<Int>()
        composeRule.setContent {
            ChogumiTheme {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                    item(key = "floor-with-image") {
                        var expanded by rememberSaveable { mutableStateOf(false) }
                        FoldableText(
                            ast = ast,
                            expanded = expanded,
                            onToggle = { expanded = !expanded },
                            collapsedLines = 6,
                            expandLabel = R.string.comment_expand_full,
                            collapseLabel = R.string.comment_collapse,
                            modifier = Modifier.onSizeChanged { heights += it.height },
                        )
                    }
                    items(FILLER_COUNT, key = { it }) { Spacer(Modifier.fillMaxWidth().height(200.dp)) }
                }
            }
        }
        composeRule.waitForIdle()
        val settled = heights.last()

        // 读到后面，有图的那一层离开组合
        composeRule.runOnIdle {
            list.requestScrollToItem(FILLER_JUMP)
            heights.clear()
        }
        composeRule.waitForIdle()

        // 手指一点点往回拖（每帧往顶上滚一小段），直到回到最顶上
        var steps = 0
        while (steps < MAX_STEPS && (list.firstVisibleItemIndex != 0 || list.firstVisibleItemScrollOffset != 0)) {
            composeRule.runOnIdle { list.dispatchRawDelta(-STEP_PX) }
            composeRule.waitForIdle()
            steps++
        }

        assertEquals(0 to 0, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset, "往回滑能越过有图的那一层回到最顶上（滑了 $steps 步）")
        assertEquals(listOf(settled), heights.distinct(), "重新进来的那一层从第一帧起就是加载后的高度")
    }

    private companion object {
        const val FILLER_COUNT = 20
        const val FILLER_JUMP = 4
        const val STEP_PX = 24f
        const val MAX_STEPS = 200
    }
}
