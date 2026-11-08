package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.network.testApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockRequestHandler
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.rules.ExternalResource

/** 每条用例的资源所有者：先结束业务与 DataStore，再关闭客户端、引擎，最后删除临时文件。 */
class TestResources : ExternalResource(), AutoCloseable {
    private val scopes = mutableSetOf<CoroutineScope>()
    private val clients = mutableSetOf<HttpClient>()
    private val directories = mutableListOf<File>()
    private var closed = false

    /** 需要真实磁盘读写的夹具使用它；不占用 runTest 的虚拟调度器。 */
    val scope: CoroutineScope by lazy { own(CoroutineScope(Dispatchers.IO + SupervisorJob())) }

    fun own(scope: CoroutineScope): CoroutineScope = scope.also {
        check(!closed)
        scopes += it
    }

    fun own(client: HttpClient): HttpClient = client.also {
        check(!closed)
        clients += it
    }

    fun apiClient(engine: HttpClientEngine): HttpClient = own(testApiClient(engine))

    fun apiClient(handler: MockRequestHandler): HttpClient = own(testApiClient(handler))

    /**
     * 一条用例自己的临时目录。机器有内存盘（Linux 的 `/dev/shm`）时放在那里：DataStore 每次写都要落盘同步，
     * 构建同时在大量写盘时一次同步能卡好几秒，等写入的用例就超出等待预算；内存盘上同步不碰磁盘。
     * 没有内存盘（Mac）时用系统临时目录。
     */
    fun directory(prefix: String): File {
        check(!closed)
        return File(temporaryRoot, "$prefix-${System.nanoTime()}")
            .apply { check(mkdirs()); directories += this }
    }

    override fun after() = close()

    override fun close() {
        if (closed) return
        cancelAndAwait(scopes)
        clients.forEach(HttpClient::close)
        cancelAndAwait(clients)
        val engines = clients.map { it.engine }.distinct()
        engines.forEach { it.close() }
        cancelAndAwait(engines)
        directories.forEach { check(it.deleteRecursively()) }
        closed = true
    }
}

private val temporaryRoot: File =
    File("/dev/shm").takeIf { it.isDirectory && it.canWrite() } ?: File(System.getProperty("java.io.tmpdir")!!)
