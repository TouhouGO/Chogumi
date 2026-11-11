package io.github.touhougo.chogumi.feature.calendar

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.data.db.subject.EpisodeDao
import io.github.touhougo.chogumi.core.data.mark.StillCollectionEditing
import io.github.touhougo.chogumi.core.data.mark.StillCollectionMarker
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeDao
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.testCollections
import io.github.touhougo.chogumi.core.data.repository.FakeUserCollectionDao
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.repository.testSubjectRepository
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectSectionDao
import io.github.touhougo.chogumi.core.data.repository.testNsfwFilter
import io.github.touhougo.chogumi.core.data.repository.testCalendarRepository
import io.github.touhougo.chogumi.core.data.repository.FakeCalendarDao
import io.github.touhougo.chogumi.core.data.repository.testHomeRepository
import io.github.touhougo.chogumi.core.data.repository.FakeHomeProgressDao
import io.github.touhougo.chogumi.core.data.repository.testAiringTimeRepository
import io.github.touhougo.chogumi.core.data.repository.FakeAiringTimeDao
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.testing.testAppTime
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.BangumiDataApi
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.Assert.assertNull

class CalendarStorageRecoveryTest {
    @Test fun `章节观察读失败保留内容_重试重新订阅`() = runBlocking {
        val resources = TestResources()
        val main = TestMain()
        main.install()
        val escaped = AtomicReference<Throwable?>()
        var vm: CalendarViewModel? = null
        val handler = withContext(main.dispatcher) {
            Thread.currentThread().uncaughtExceptionHandler.also {
                Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> escaped.set(e) }
            }
        }
        try {
            val fail = CompletableDeferred<Unit>()
            val subscriptions = java.util.concurrent.atomic.AtomicInteger()
            val broken = java.util.concurrent.atomic.AtomicBoolean(true)
            val episodes = object : EpisodeDao by FakeEpisodeDao() {
                override fun observeLastWrite() = flow<Long?> {
                    subscriptions.incrementAndGet()
                    emit(0L)
                    if (broken.get()) { fail.await(); throw IOException("章节观察读失败") }
                    kotlinx.coroutines.awaitCancellation()
                }
            }
            val token = testTokenStore(resources, 7)
            val client = resources.apiClient { request ->
                respond(if (request.url.encodedPath == "/p1/calendar") "{}" else "{\"items\":[]}", headers = headersOf("Content-Type", "application/json"))
            }
            val subjects = FakeSubjectDao()
            val collections = testCollections(resources, CollectionApi(client), subjects, FakeUserCollectionDao(), token, FakeEpisodeWatchStateDao(), episodeDao = episodes)
            val subjectRepo = testSubjectRepository(resources, SubjectApi(client), NextApi(client), subjects, episodes, FakeSubjectSectionDao(), testNsfwFilter(resources), token)
            val calendarRepo = testCalendarRepository(resources, NextApi(client), FakeCalendarDao(), subjects, testNsfwFilter(resources), tokenStore = token)
            val homeRepo = testHomeRepository(NextApi(client), FakeHomeProgressDao(), subjects, token, collections, episodeDao = FakeEpisodeDao())
            vm = withContext(main.dispatcher) { CalendarViewModel(calendarRepo, testAiringTimeRepository(BangumiDataApi(client), FakeAiringTimeDao()), collections.observer, subjectRepo, homeRepo, testAppTime(1791763200000L), StillCollectionMarker(), StillCollectionEditing(), Dispatchers.Default) }
            val page = requireNotNull(vm)
            awaitUntil("放送表已就绪") { page.uiState.value.calendar is Load.Ready }
            val before = page.uiState.value.calendar
            fail.complete(Unit)
            awaitUntil("章节错误已处理或逸出") { escaped.get() != null || page.failures.current.value != null }
            withContext(main.dispatcher) { }
            assertNull("章节观察失败不能逸出页面", escaped.get())
            org.junit.Assert.assertEquals(before, page.uiState.value.calendar)
            broken.set(false)
            val count = subscriptions.get()
            withContext(main.dispatcher) { requireNotNull(page.failures.current.value?.retry).invoke() }
            awaitUntil("章节观察重新订阅") { subscriptions.get() > count }
        } finally {
            withContext(main.dispatcher) { Thread.currentThread().uncaughtExceptionHandler = handler }
            main.uninstall(listOfNotNull(vm?.viewModelScope))
            resources.close()
        }
    }
}
