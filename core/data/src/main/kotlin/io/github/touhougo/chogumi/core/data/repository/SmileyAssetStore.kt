package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.model.SmileyFiles
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * 表情资源的按需下载与持久化：**取一次，之后永久本地**。
 *
 * ## 为什么不内置全部
 *
 * 六家族共 400+ 个文件、约 48MB，其中 musume/blake 占 48MB（全彩插画）。
 * 全量内置会让 APK 无谓膨胀；而用户实际会遇到的表情远少于全集。
 * 故只内置 12 个核心 reaction，其余按需取。
 *
 * ## 为什么用 `filesDir` 而不是 `cacheDir`
 *
 * `cacheDir` 在 Android 语义上是「系统空间紧张时可随时删除」的。
 * 这里要的是**持久** —— 用户遇到一次后就该一直在，所以用 `filesDir`。
 *
 * ## 版本目录
 *
 * 落在 `bangumi_smiley/v1/` 下。版本号的意义是：同一个 code 在不同版本
 * 可能指向不同文件，若 Bangumi 调整素材，旧缓存不能与新规则混用。
 *
 * ## 不持有任何映射知识
 *
 * 相对路径由调用方从 `SmileyCatalog` 取（如 `tv/15.gif`），本类只负责取。
 * 「绝对 URL 只在一处拼接」也在这里落实：**绝对 URL 由本类校验**，
 * 别处不得自行拼接。
 *
 * @param baseDir 持久化根目录；生产传 `context.filesDir`，测试传临时目录
 * @param httpGet 下载函数；返回字节或 null（失败）。可注入以便测试安全校验
 * @param io 文件检查、写盘与启动清理跑在这里（生产是 `Dispatchers.IO`）：[ensure] 的调用方是表情组件的
 *   `LaunchedEffect`（主线程），本类由 Activity 在 `setContent` 里取出
 */
class SmileyAssetStore(
    private val baseDir: File,
    private val httpGet: suspend (String) -> ByteArray?,
    private val io: CoroutineContext,
) : SmileyFiles {

    /** 当前资源版本。升版本时旧目录会被清理。 */
    private val version = "v1"

    private val root: File get() = File(baseDir, "$DIR_NAME/$version")

    /**
     * single-flight：同一相对路径的并发请求只下载一次。
     *
     * 用「每个路径一把锁」而非全局锁：全局锁会把不同表情的下载串行化，
     * 一屏有 5 种表情就要等 5 倍时间。这里只保证**同一文件**不并发。
     */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * 启动清理：残留 .tmp（上次被杀）与旧版本目录（永久占盘）。第一次 [ensure] 时在 [io] 上做一次，之后的都等它做完，
     * 不会删到正在写的 .tmp。
     */
    private val cleanUp = lazy {
        runCatching {
            root.parentFile?.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name != version) dir.deleteRecursively()
            }
            root.walkTopDown().filter { it.isFile && it.name.endsWith(TMP_SUFFIX) }.forEach { it.delete() }
        }
    }

    /**
     * 确保某个表情可用，返回本地文件；失败返回 null。
     *
     * @param url 绝对 URL。**本方法会校验**：仅 https、host 必须命中白名单、
     *   拒绝内网与保留地址。校验不通过**直接返回 null 且不发出请求**
     * @param remotePath Catalog 给的相对路径（如 `tv/15.gif`）。含 `..` 或
     *   绝对路径时拒绝 —— 防止路径穿越写出根目录
     */
    override suspend fun ensure(url: String, remotePath: String): File? {
        if (!isPathSafe(remotePath)) return null
        return withContext(io) {
            cleanUp.value
            val target = File(root, remotePath)
            if (target.isFile && target.length() > 0) return@withContext target

            // 安全校验在取锁**之前**：被拒的输入不该占用锁，也不该发出请求
            if (!isUrlAllowed(url)) return@withContext null

            val lock = locks.getOrPut(remotePath) { Mutex() }
            lock.withLock {
                // 双重检查：等锁期间可能已被另一个协程写好
                if (target.isFile && target.length() > 0) return@withLock target

                val bytes = httpGet(url) ?: return@withLock null
                if (bytes.isEmpty()) return@withLock null

                writeAtomically(target, bytes)
            }
        }
    }

    /**
     * 临时文件写入 → 原子改名。
     *
     * 直接写目标文件的话，进程在写入中途被杀会留下**半个 GIF**，
     * 而它长度非零，下次会被当成有效缓存 —— 表现为"这个表情永远显示不全"。
     * 先写 `.tmp` 再 rename 可避免：rename 在同一文件系统内是原子的。
     */
    private fun writeAtomically(target: File, bytes: ByteArray): File? = runCatching {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            // rename 失败（极少数文件系统）：退回复制后删源，保证不留 .tmp
            target.writeBytes(bytes)
            tmp.delete()
        }
        target
    }.getOrElse {
        // 任何失败都要清掉 .tmp，否则残留会被误认为有效文件
        runCatching { File(target.parentFile, target.name + TMP_SUFFIX).delete() }
        null
    }

    /**
     * URL 白名单校验。
     *
     * 五条逐条：
     * 1. 仅 https
     * 2. host 必须是 `lain.bgm.tv`（精确匹配，`lain.bgm.tv.evil.com` 不放行）
     * 3. 校验发生在**构造请求之前**（[ensure] 里先校验再调 `httpGet`）
     * 4. 拒绝内网与保留地址（本函数内）
     * 5. 绝对 URL 只在这里被消费 —— 别处不得自行拼接
     */
    private fun isUrlAllowed(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        if (host !in ALLOWED_HOSTS) return false
        // host 已是白名单精确匹配，内网地址不可能等于 lain.bgm.tv；
        // 但仍显式挡一层，防止将来白名单扩容时引入内网条目。
        return !isPrivateHost(host)
    }

    /** 内网、环回与保留地址。 */
    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost")) return true
        // IPv6 环回与链路本地
        if (host == "::1" || host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")) return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        val (a, b) = octets[0] to octets[1]
        return when {
            a == 127 -> true                     // 环回
            a == 10 -> true                      // 私有 A 类
            a == 172 && b in 16..31 -> true      // 私有 B 类
            a == 192 && b == 168 -> true         // 私有 C 类
            a == 169 && b == 254 -> true         // 链路本地（云元数据端点）
            a == 0 -> true                       // 保留
            else -> false
        }
    }

    /**
     * 相对路径不得逃出资源根目录。
     *
     * Catalog 给的路径都是 `tv/15.gif` 这种形态，正常不会触发；
     * 但若将来某个 code 分支被写错把用户可控字符串带进来，这里是最后一道闸。
     */
    private fun isPathSafe(remotePath: String): Boolean {
        if (remotePath.isBlank()) return false
        if (remotePath.startsWith("/") || remotePath.startsWith("\\")) return false
        if (remotePath.contains("..")) return false
        // Windows 盘符形态（跨平台测试时也会走到）
        if (remotePath.contains(":")) return false
        return true
    }

    private companion object {
        const val DIR_NAME = "bangumi_smiley"
        const val TMP_SUFFIX = ".tmp"

        /** 表情 CDN 白名单。与 `SmileyCatalog` 的拼接点保持一致。 */
        val ALLOWED_HOSTS = setOf("lain.bgm.tv")
    }
}
