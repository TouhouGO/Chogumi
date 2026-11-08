package io.github.touhougo.chogumi.core.ui.image

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 封面缩图、量化与三段平均色沿调用方注入的计算上下文执行，颜色口径不变。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CoverComputationTest {
    @Test
    fun `封面像素读取与取色使用注入计算调度器`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bitmap = Bitmap.createBitmap(12, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.Red.toArgb()) }
        try {
            val colors = coverColorsOf(dispatcher) {
                val actual = currentCoroutineContext()[ContinuationInterceptor]
                assertEquals(dispatcher, actual)
                bitmap
            }
            assertEquals(Color.Red, colors.bands.top)
            assertEquals(Color.Red, colors.bands.middle)
            assertEquals(Color.Red, colors.bands.bottom)
        } finally {
            bitmap.recycle()
        }
    }
}
