package io.github.touhougo.chogumi.feature.home.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeDao
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.repository.FakeHomeProgressDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectSectionDao
import io.github.touhougo.chogumi.core.data.repository.FakeUserCollectionDao
import io.github.touhougo.chogumi.core.data.repository.FaultablePreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.testAuthRepository
import io.github.touhougo.chogumi.core.data.repository.testCollections
import io.github.touhougo.chogumi.core.data.repository.testHomeRepository
import io.github.touhougo.chogumi.core.data.repository.testNsfwFilter
import io.github.touhougo.chogumi.core.data.repository.testSubjectRepository
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.foundation.testing.TestClock
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetReliabilityTest {
    @get:org.junit.Rule val resources = TestResources()

    @Test
    fun `真实小组件刷新订阅偏好读失败_不能漏到应用级异常处理器`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val escaped = CompletableDeferred<Throwable>()
        val applicationScope = resources.own(CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> escaped.complete(error) }))
        val fault = FaultablePreferencesRepository(resources)
        fault.failReads.set(true)
        val updates = realUpdates(context, fault.repository, applicationScope)
        updates.ensureRunning()
        awaitUntil("偏好读取失败") { fault.failedReads.get() > 0 }
        assertNull(if (escaped.isCompleted) escaped.await() else null, "偏好读取异常不能漏出")
        fault.failReads.set(false)
        awaitUntil("存储恢复后实际重画") { updates.revision.value > 0 }
        assertNull(if (escaped.isCompleted) escaped.await() else null, "重画订阅必须恢复")
    }

    @Test
    fun `配置页的小组件已被移走_选择作品不应抛未处理异常`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = FaultablePreferencesRepository(resources)
        val updates = realUpdates(context, preferences.repository, resources.scope)
        // 打开配置页后，桌面移走了这块；id不等于INVALID_APPWIDGET_ID，但系统已查不到provider。
        val result = chooseSubject(context, 723167, 101, updates)
        kotlin.test.assertEquals(io.github.touhougo.chogumi.core.foundation.Outcome.Success(false), result)
    }

    private suspend fun realUpdates(context: Context, preferences: io.github.touhougo.chogumi.core.data.repository.PreferencesRepository, applicationScope: CoroutineScope): WidgetUpdates {
        val tokenStore = testTokenStore(resources, 7)
        val client = resources.apiClient { respond("", HttpStatusCode.NotFound) }
        val clock = TestClock(0)
        val subjectDao = FakeSubjectDao()
        val episodeDao = FakeEpisodeDao()
        val collections = testCollections(resources, CollectionApi(client), subjectDao, FakeUserCollectionDao(), tokenStore, FakeEpisodeWatchStateDao(), episodeDao, clock = clock)
        val home = testHomeRepository(NextApi(client), FakeHomeProgressDao(), subjectDao, tokenStore, collections, episodeDao, clock)
        val subjects = testSubjectRepository(resources, SubjectApi(client), NextApi(client), subjectDao, episodeDao, FakeSubjectSectionDao(), testNsfwFilter(resources), tokenStore, clock)
        val coordinator = collections.epTapCoordinator(collections.progressOrchestrator(subjects), resources.scope, { 0L }, { })
        val auth = testAuthRepository(resources, client, tokenStore, AuthConfig("fixture", "fixture", "chogumi://oauth/callback"), resources.scope)
        auth.ensureInitialized()
        val actions = WidgetProgressActions(WidgetWorksSource(home, collections.observer, auth, clock), coordinator)
        return WidgetUpdates(context, applicationScope, collections.observer, preferences, auth, actions)
    }

}
