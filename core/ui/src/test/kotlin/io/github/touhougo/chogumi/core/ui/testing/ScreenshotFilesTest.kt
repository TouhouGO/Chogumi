package io.github.touhougo.chogumi.core.ui.testing

import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowLegacyBitmap

/** 截图编码失败必须先让测试失败，不能进入设备的 UiAutomation。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ScreenshotFilesTest {

    @Test
    @Config(shadows = [FailingCompressBitmapShadow::class])
    fun `PNG 压缩失败时在访问 UiAutomation 前报错`() {
        val previous = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val probe = UiAutomationProbe()
        InstrumentationRegistry.registerInstance(probe, Bundle.EMPTY)
        try {
            val failure = assertFailsWith<IllegalStateException> {
                saveScreenshot("review", "compress-failure", Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
            }

            assertEquals("截图 PNG 编码失败", failure.message)
            assertFalse(probe.accessed, "编码失败后仍访问了 UiAutomation")
        } finally {
            InstrumentationRegistry.registerInstance(previous, arguments)
        }
    }
}

@Implements(Bitmap::class)
internal class FailingCompressBitmapShadow : ShadowLegacyBitmap() {
    @Implementation
    override fun compress(format: Bitmap.CompressFormat, quality: Int, stream: OutputStream): Boolean = false
}

private class UiAutomationProbe : Instrumentation() {
    var accessed = false

    override fun getUiAutomation(): UiAutomation {
        accessed = true
        error("PNG 编码失败后不应访问 UiAutomation")
    }
}
