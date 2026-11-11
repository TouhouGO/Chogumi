package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import java.io.IOException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 应用级搜索历史维护的存储故障不能逸出并带崩进程。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchMaintenanceFailureTest {
    @get:Rule
    val resources = TestResources()

    @Test
    fun `首次恢复会话读失败_历史维护不向应用作用域抛异常`() = runTest {
        val escaped = mutableListOf<Throwable>()
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error -> escaped += error })
        resources.own(appScope)
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("令牌存储读失败") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw IOException("令牌存储写失败")
        }
        val client = resources.apiClient { error("历史维护不应请求网络") }
        val repository = testSearchRepository(
            resources, SubjectApi(client), NextApi(client), FakeSubjectDao(), FakeRecentSearchDao(), testNsfwFilter(resources), TokenStore(broken),
        )

        repository.maintainRecentSearches(appScope)
        runCurrent()

        assertEquals("会话存储故障不能逸出应用级维护", emptyList<Throwable>(), escaped)
        assertEquals(Outcome.Failed(Failure.Storage), repository.recordSearch("搜索"))
        // 维护读不出就一直退避重读：不先停下它，runTest 收尾推进虚拟时间永远推不完
        appScope.cancel()
    }
}
