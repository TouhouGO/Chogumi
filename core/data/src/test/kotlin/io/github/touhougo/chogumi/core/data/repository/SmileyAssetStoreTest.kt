package io.github.touhougo.chogumi.core.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 表情资源的按需下载与持久化。
 *
 * ## 为什么用 filesDir 而不是 cacheDir
 *
 * 缓存目录在 Android 语义上是「系统空间紧张时可随时删除」的。
 * 表情要的是**持久** —— 用户遇到一次后就应该一直在，故用 filesDir。
 *
 * ## 为什么需要 single-flight
 *
 * 一屏可能同时出现多个相同表情；无 single-flight 会并发下载同一文件，
 * 而 `.tmp → rename` 不是幂等的（两个协程各自 rename 同一目标会互相覆盖）。
 *
 * ## 为什么「被拒」要断言未发出请求
 *
 * 下载安全防的是**请求本身不该发出去**。
 * 断言"收到 4xx/5xx"是错的标准 —— 那说明请求已经发出、只是被服务端拒绝，
 * 攻击面依然存在。
 */
class SmileyAssetStoreTest {

    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir")!!, "smiley-store-${System.nanoTime()}")
        root.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    // ---- 下载安全（五条逐条映射）----

    @Test
    fun `http协议被拒且不发请求`() = runBlocking {
        val store = store()
        val result = store.ensure("http://lain.bgm.tv/img/smiles/tv/15.gif", "tv/15.gif")
        assertNull(result, "http 应被拒绝")
        assertEquals(emptyList(), requestedUrls, "被拒的请求不得发出")
    }

    @Test
    fun `非白名单host被拒且不发请求`() = runBlocking {
        val store = store()
        listOf(
            "https://evil.example.com/img/smiles/tv/15.gif",
            "https://lain.bgm.tv.evil.com/img/smiles/tv/15.gif",
            "https://cdn.other.tv/img/smiles/tv/15.gif",
        ).forEach { url ->
            assertNull(store.ensure(url, "tv/15.gif"), "$url 应被拒绝")
        }
        assertEquals(emptyList(), requestedUrls, "被拒的请求不得发出")
    }

    @Test
    fun `内网与保留地址被拒且不发请求`() = runBlocking {
        val store = store()
        listOf(
            "https://localhost/img/smiles/tv/15.gif",
            "https://127.0.0.1/img/smiles/tv/15.gif",
            "https://192.168.1.1/img/smiles/tv/15.gif",
            "https://10.0.0.1/img/smiles/tv/15.gif",
            "https://172.16.0.1/img/smiles/tv/15.gif",
            "https://169.254.169.254/img/smiles/tv/15.gif",
            "https://[::1]/img/smiles/tv/15.gif",
        ).forEach { url ->
            assertNull(store.ensure(url, "tv/15.gif"), "$url 应被拒绝")
        }
        assertEquals(emptyList(), requestedUrls, "被拒的请求不得发出")
    }

    /** 白名单 host + https 的正常请求应当发出。 */
    @Test
    fun `合法请求正常发出`() = runBlocking {
        val store = store()
        val result = store.ensure("https://lain.bgm.tv/img/smiles/tv/15.gif", "tv/15.gif")
        assertNotNull(result, "合法请求应成功")
        assertEquals(listOf("https://lain.bgm.tv/img/smiles/tv/15.gif"), requestedUrls)
    }

    // ---- 持久化 ----

    @Test
    fun `下载后落盘到版本目录`() = runBlocking {
        val store = store()
        val result = store.ensure("https://lain.bgm.tv/img/smiles/tv/15.gif", "tv/15.gif")
        assertNotNull(result)
        assertTrue(result.isFile, "应返回真实文件")
        assertTrue(result.path.contains("bangumi_smiley/v1"), "应落在带版本号的目录：${result.path}")
        assertTrue(result.path.endsWith("tv/15.gif"), "应保持 Catalog 给的相对结构：${result.path}")
    }

    /** 已有本地文件时**不重复下载**。 */
    @Test
    fun `命中本地文件时不发请求`() = runBlocking {
        val store = store()
        store.ensure("https://lain.bgm.tv/img/smiles/tv/15.gif", "tv/15.gif")
        val before = requestedUrls.size
        val again = store.ensure("https://lain.bgm.tv/img/smiles/tv/15.gif", "tv/15.gif")
        assertNotNull(again)
        assertEquals(before, requestedUrls.size, "第二次应命中本地，不再下载")
    }

    // ---- atomic rename ----

    @Test
    fun `成功后不留tmp文件`() = runBlocking {
        val store = store()
        store.ensure("https://lain.bgm.tv/img/smiles/tv/15.gif", "tv/15.gif")
        val tmps = File(root, "bangumi_smiley").walkTopDown().filter { it.name.endsWith(".tmp") }.toList()
        assertEquals(emptyList(), tmps, "成功后不得残留 .tmp")
    }

    /** 下载失败要清掉 `.tmp`，否则残留会被误认为有效文件。 */
    @Test
    fun `下载失败清理tmp`() = runBlocking {
        val store = store(failWith = 500)
        val result = store.ensure("https://lain.bgm.tv/img/smiles/tv/15.gif", "tv/15.gif")
        assertNull(result, "失败应返回 null")
        val tmps = File(root, "bangumi_smiley").walkTopDown().filter { it.name.endsWith(".tmp") }.toList()
        assertEquals(emptyList(), tmps, "失败后必须清理 .tmp")
    }

    // ---- single-flight ----

    /**
     * 同一 code 并发请求只下载**一次**。
     *
     * 真实并发（多协程同时取），不是顺序调用 —— 顺序调用测不出竞态。
     */
    @Test
    fun `同一资源的并发请求只下载一次`() = runBlocking {
        val store = store(delayMs = 100)
        val results = (1..8).map {
            async { store.ensure("https://lain.bgm.tv/img/smiles/tv/15.gif", "tv/15.gif") }
        }.awaitAll()

        assertTrue(results.all { it != null }, "全部应成功")
        assertEquals(1, requestedUrls.size, "8 个并发请求只应发出 1 次下载，实际 ${requestedUrls.size} 次")
    }

    /** 不同资源各自下载，不被错误地合并。 */
    @Test
    fun `不同资源各自下载`() = runBlocking {
        val store = store(delayMs = 50)
        val urls = listOf(
            "https://lain.bgm.tv/img/smiles/tv/15.gif" to "tv/15.gif",
            "https://lain.bgm.tv/img/smiles/tv/44.gif" to "tv/44.gif",
        )
        urls.map { (u, p) -> async { store.ensure(u, p) } }.awaitAll()
        assertEquals(2, requestedUrls.size, "不同资源应各下载一次")
    }

    // ---- 残留清理 ----

    /** 上次被杀留下的 `.tmp` 应在初始化时清掉。 */
    @Test
    fun `初始化清理上次残留的tmp`() = runBlocking {
        val stale = File(root, "bangumi_smiley/v1/tv").apply { mkdirs() }
        File(stale, "15.gif.tmp").writeText("half-downloaded")

        val store = store()
        store.ensure("https://lain.bgm.tv/img/smiles/tv/44.gif", "tv/44.gif")

        assertFalse(File(stale, "15.gif.tmp").exists(), "启动时应清理残留 .tmp")
    }

    /** 旧版本目录应被删除，避免永久占盘。 */
    @Test
    fun `初始化清理旧版本目录`() = runBlocking {
        val old = File(root, "bangumi_smiley/v0/tv").apply { mkdirs() }
        File(old, "15.gif").writeText("old")

        val store = store()
        store.ensure("https://lain.bgm.tv/img/smiles/tv/44.gif", "tv/44.gif")

        assertFalse(old.exists(), "旧版本目录 v0 应被清理")
    }

    /** 调用方在主线程（表情组件的 LaunchedEffect）：文件检查、写盘与启动清理都切到注入的调度器上。 */
    @Test
    fun `文件操作跑在注入的调度器上`() = runBlocking {
        val io = CountingDispatcher()
        val store = store(io = io)

        assertNotNull(store.ensure("https://lain.bgm.tv/img/smiles/tv/44.gif", "tv/44.gif"))
        assertNotNull(store.ensure("https://lain.bgm.tv/img/smiles/tv/44.gif", "tv/44.gif"))

        assertTrue(io.dispatches >= 2, "每次取都先切到注入的调度器，实际调度 ${io.dispatches} 次")
    }

    /** 相对路径里的 `..` 不得逃出根目录（防止路径穿越）。 */
    @Test
    fun `拒绝逃出根目录的相对路径`() = runBlocking {
        val store = store()
        assertNull(store.ensure("https://lain.bgm.tv/img/smiles/tv/15.gif", "../../etc/passwd"))
        assertNull(store.ensure("https://lain.bgm.tv/img/smiles/tv/15.gif", "/etc/passwd"))
    }

    // ---- 测试装配 ----

    /** 记录实际发出的请求 URL。安全用例靠它断言"被拒的没发出去"。 */
    private val requestedUrls = mutableListOf<String>()

    private fun store(failWith: Int? = null, delayMs: Long = 0, io: CoroutineContext = Dispatchers.IO) = SmileyAssetStore(
        baseDir = root,
        httpGet = { url ->
            synchronized(requestedUrls) { requestedUrls += url }
            delay(delayMs)
            if (failWith != null) null else GIF_BYTES
        },
        io = io,
    )

    private companion object {
        /** 合法 GIF 头 + 少量数据。 */
        val GIF_BYTES = "GIF89a".toByteArray() + ByteArray(64)
    }
}
