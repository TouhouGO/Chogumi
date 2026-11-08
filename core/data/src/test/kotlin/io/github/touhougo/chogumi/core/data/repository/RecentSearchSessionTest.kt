package io.github.touhougo.chogumi.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.touhougo.chogumi.core.data.db.ChogumiDatabase
import io.github.touhougo.chogumi.core.data.db.search.RecentSearchDao
import io.github.touhougo.chogumi.core.data.di.createDataStores
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.data.web.WebSessionRepository
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.PrivacyApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.github.touhougo.chogumi.core.network.api.WebSessionApi
import io.github.touhougo.chogumi.core.network.testApiClient
import io.ktor.client.engine.mock.MockEngine
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentSearchSessionTest {
    private class Harness(dir: File) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val stores = createDataStores(File(dir, "preferences.preferences_pb"), File(dir, "tokens.preferences_pb"), scope)
        val tokenStore = TokenStore(stores.tokens)
        private val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), ChogumiDatabase::class.java, File(dir, "search.db").absolutePath)
            .allowMainThreadQueries()
            .build()
        val storedHistory = db.recentSearchDao()
        val failDelete = AtomicBoolean(false)
        val deleteEntered = CompletableDeferred<Unit>()
        val deleteFailed = CompletableDeferred<Unit>()
        @Volatile var releaseDelete: CompletableDeferred<Unit>? = null
        private val history = object : RecentSearchDao by storedHistory {
            override suspend fun deleteAll() {
                if (releaseDelete != null) {
                    deleteEntered.complete(Unit)
                    releaseDelete?.await()
                }
                if (failDelete.get()) {
                    deleteFailed.complete(Unit)
                    throw IOException("存储写入失败")
                }
                storedHistory.deleteAll()
            }
        }
        private val engine = MockEngine { error("搜索历史不应请求网络") }
        private val client = testApiClient(engine)
        private val web = WebSessionRepository(tokenStore, PlainCookieCipher, WebSessionApi(client))
        val repository = SearchRepository(
            SubjectApi(client), NextApi(client), db.subjectDao(), history, db.searchFirstPageDao(),
            NsfwFilter(NsfwPreferenceRepository(stores.preferences, PrivacyApi(client), tokenStore, web)),
            tokenStore, { 1L }, Dispatchers.IO, stores.preferences,
        )

        suspend fun signIn(id: Int) {
            tokenStore.completeSession(tokenStore.beginSession("test-token-$id"), id, "user$id")
        }

        fun maintain() = repository.maintainRecentSearches(scope)

        override fun close() {
            client.close()
            engine.close()
            cancelAndAwait(listOf(client, engine, scope))
            db.close()
        }
    }

    @Test
    fun `换号清理失败后新账号不能读取旧搜索`() = runBlocking {
        val dir = Files.createTempDirectory("recent-search-session").toFile()
        try {
            Harness(dir).use { h ->
                h.signIn(7)
                h.repository.recordSearch("账号甲的搜索")
                h.signIn(8)
                h.failDelete.set(true)
                assertEquals(Outcome.Failed(Failure.Storage), h.repository.clearRecentSearches())

                assertEquals(listOf("账号甲的搜索"), h.storedHistory.latest(10).first(), "删除失败仍有旧行")
                assertEquals(Load.Ready(emptyList()), h.repository.recentSearches.first(), "旧历史不能交给新账号")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `清理在途隐藏历史失败后不恢复且下一次换号仍会维护`() = runBlocking {
        val dir = Files.createTempDirectory("recent-search-maintenance").toFile()
        try {
            Harness(dir).use { h ->
                h.signIn(7)
                assertEquals(Outcome.Success(Unit), h.repository.recordSearch("账号甲的搜索"))
                h.failDelete.set(true)
                h.releaseDelete = CompletableDeferred()
                h.signIn(8)
                h.maintain()
                withTimeout(WAIT_BUDGET_MS) { h.deleteEntered.await() }
                assertEquals(Load.Ready(emptyList()), h.repository.recentSearches.first(), "删除在途不能读旧行")
                h.releaseDelete?.complete(Unit)
                withTimeout(WAIT_BUDGET_MS) { h.deleteFailed.await() }
                assertEquals(Load.Ready(emptyList()), h.repository.recentSearches.first(), "失败不能恢复旧行")

                h.failDelete.set(false)
                h.releaseDelete = null
                val observing = CompletableDeferred<Unit>()
                val cleared = async(Dispatchers.IO) {
                    h.storedHistory.latest(10).onEach { observing.complete(Unit) }.first { it.isEmpty() }
                }
                withTimeout(WAIT_BUDGET_MS) { observing.await() }
                h.signIn(9)
                withTimeout(WAIT_BUDGET_MS) { cleared.await() }
                assertEquals(Outcome.Success(Unit), h.repository.recordSearch("账号丙的搜索"))
                assertEquals(Load.Ready(listOf("账号丙的搜索")), h.repository.recentSearches.first())
                assertEquals(listOf("账号丙的搜索"), h.storedHistory.latest(10).first())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `删除失败后重启仍拒绝上一账号的搜索历史`() = runBlocking {
        val dir = Files.createTempDirectory("recent-search-restart").toFile()
        try {
            Harness(dir).use { h ->
                h.signIn(7)
                h.repository.recordSearch("账号甲的搜索")
                h.signIn(8)
                h.failDelete.set(true)
                assertEquals(Outcome.Failed(Failure.Storage), h.repository.clearRecentSearches())
            }
            Harness(dir).use { restarted ->
                restarted.failDelete.set(true)
                assertEquals(8, restarted.tokenStore.session().userId)
                assertEquals(listOf("账号甲的搜索"), restarted.storedHistory.latest(10).first())
                assertEquals(Load.Ready(emptyList()), restarted.repository.recentSearches.first(), "重启不能绕过持久归属")
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
