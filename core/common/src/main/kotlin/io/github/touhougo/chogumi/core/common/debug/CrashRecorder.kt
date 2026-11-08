package io.github.touhougo.chogumi.core.common.debug

import android.content.Context
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.displayZone
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

/**
 * 调试包的崩溃记录器：未捕获异常发生时，先把异常类型与逐帧调用栈写进 App 自己的文件，再交还系统原有处理。
 * 设置页调试区的「上次崩溃」读它。只在可调试构建安装（App.onCreate）。
 *
 * 为什么要自己记（2026-10-09 实测）：有一次闪退系统什么都没留下——主线程「Shutting down VM」后 5ms 进程自杀，
 * 日志各缓冲区没有 FATAL EXCEPTION、DropBox 没有崩溃报告、退出原因是 SIGNALED 而不是 CRASH。
 * 当时内存充裕，最可能是异常在被转成文字时自己又出错（描述里带的对象 toString 递归之类），
 * 系统写报告失败就直接杀进程。系统的日志预处理器抛错会被吞掉、照样调用默认处理器，所以这里一定轮得到。
 *
 * 因此写法刻意「不碰异常的文字描述」：
 * - 不调用 Throwable.toString / printStackTrace；类名直接取，message 单独 try，取不到就写取不到的原因；
 * - 原因链按身份去重、有上限，帧数有上限；StackTraceElement 的文字只含类名 / 方法 / 文件 / 行号，安全；
 * - 安装时预留一块内存，崩溃时先释放再格式化，内存吃紧时也写得出；
 * - 先写临时文件再改名，写到一半失败不会把上一次的好记录覆盖成半截。
 */
object CrashRecorder {

    private const val DIR = "crash"
    private const val FILE = "last_crash.txt"
    private const val MAX_CAUSES = 8
    private const val MAX_FRAMES = 120
    private const val MAX_MESSAGE = 2_000
    private const val RESERVE_BYTES = 256 * 1024
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    @Volatile
    private var reserve: ByteArray? = null

    /** 装在默认未捕获异常处理器前面；重复调用无副作用。 */
    fun install(context: Context) {
        if (reserve != null) return
        reserve = ByteArray(RESERVE_BYTES)
        val file = File(context.filesDir, "$DIR/$FILE")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            reserve = null
            try {
                write(file, format(thread.name, error, AppClock.SYSTEM.nowMillis()))
            } catch (_: Throwable) {
                // 记录失败也不能挡住系统原有的崩溃处理
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 上次崩溃的完整记录；没有为 null。 */
    fun read(context: Context): String? =
        File(context.filesDir, "$DIR/$FILE").takeIf { it.isFile }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, "$DIR/$FILE").delete()
    }

    /** 纯函数：把未捕获异常排成记录正文（首行时间与线程，其后每层「类名: 描述」+ 调用栈）。 */
    internal fun format(threadName: String, error: Throwable, timeMillis: Long): String = buildString {
        append(TIME_FORMAT.format(Instant.ofEpochMilli(timeMillis).atZone(displayZone())))
        append("  线程 ").append(threadName).append('\n')
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSES && seen.add(current)) {
            if (depth > 0) append("Caused by: ")
            append(current.javaClass.name).append(": ").append(safeMessage(current)).append('\n')
            val frames = current.stackTrace
            frames.take(MAX_FRAMES).forEach { append("\tat ").append(it).append('\n') }
            if (frames.size > MAX_FRAMES) append("\t… 另有 ").append(frames.size - MAX_FRAMES).append(" 帧\n")
            current = current.cause
            depth++
        }
        if (current != null) append(if (current in seen) "（原因链成环，到此为止）\n" else "（原因链过长，到此为止）\n")
    }

    private fun safeMessage(error: Throwable): String = try {
        error.message?.take(MAX_MESSAGE) ?: "（无描述）"
    } catch (failure: Throwable) {
        "（描述取不到：${failure.javaClass.name}）"
    }

    private fun write(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        tmp.renameTo(target)
    }
}
