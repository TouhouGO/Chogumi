package io.github.touhougo.chogumi.core.ui.testing

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream

/** 设备上放截图的目录：各处只给子目录名，`adb pull` 这一个目录就取走全部。 */
const val CAPTURE_ROOT = "/data/local/tmp/chogumi-captures"

/**
 * 把截图写到设备的 [CAPTURE_ROOT]/[folder]/[name].png。经 UiAutomation 的 shell 写：库测试 APK 跑完会被卸载，
 * 写在测试包自己的目录里会跟着删掉。目录不存在则先建。
 *
 * 截图落盘只有这一份实现（tools/guards/check_test_assertions.py 拦测试源码里自己压 PNG 的）。
 *
 * 往 shell 的 stdin 写字节要 `executeShellCommandRwe`（API 34+）；minSdk 低于此，低版本设备上直接报错而不是静默丢图。
 */
fun saveScreenshot(folder: String, name: String, bitmap: Bitmap) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        throw IllegalStateException("截图落盘需要 API 34+（当前 ${Build.VERSION.SDK_INT}）")
    }
    // 先在宿主内存里完成编码，压缩失败时不碰设备上的 UiAutomation，也不会留下空文件。
    val png = ByteArrayOutputStream().also {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "截图 PNG 编码失败" }
    }.toByteArray()
    check(png.isNotEmpty()) { "截图 PNG 编码结果为空" }
    val dir = "$CAPTURE_ROOT/$folder"
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("mkdir -p $dir")).use { it.readBytes() }
    val (stdout, stdin, stderr) = automation.executeShellCommandRwe("sh -c cat>$dir/$name.png")
    ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(png) }
    ParcelFileDescriptor.AutoCloseInputStream(stdout).use { it.readBytes() }
    val err = ParcelFileDescriptor.AutoCloseInputStream(stderr).use { it.readBytes() }
    check(err.isEmpty()) { "写截图失败：${String(err)}" }
}
