package io.github.touhougo.chogumi.core.data.repository

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class TestResourcesTest {
    @Test
    fun `收尾等待业务与磁盘作用域结束_关闭客户端引擎后删除文件`() = runBlocking {
        val resources = TestResources()
        val client = resources.apiClient { respond("", HttpStatusCode.NoContent) }
        val engine = client.engine
        val directory = resources.directory("owner-test")
        File(directory, "owned.txt").writeText("fixture")
        val store = testTokenStore(resources, userId = 7)
        assertTrue(store.session().authenticated)
        assertFalse(testNsfwFilter(resources).showNsfw())
        val finished = CompletableDeferred<Unit>()
        val work = resources.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                finished.complete(Unit)
            }
        }

        resources.close()

        assertTrue(work.isCompleted && finished.isCompleted, "业务取消收尾已执行")
        assertTrue(resources.scope.coroutineContext.job.isCompleted, "DataStore 作用域已结束")
        assertTrue(client.coroutineContext.job.isCompleted, "客户端未留活跃任务")
        assertTrue(engine.coroutineContext.job.isCompleted, "手工创建的引擎也已关闭")
        assertFalse(directory.exists(), "文件只在任务结束后删除")
    }
}
