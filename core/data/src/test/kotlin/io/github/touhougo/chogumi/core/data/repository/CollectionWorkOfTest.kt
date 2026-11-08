package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.CollectionWork
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** 收藏面板的四路合成：缓存后刷新失败不关面板、不抹内容、不卡住后续逐集变化，每次失败只交一拍。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectionWorkOfTest {
    private val subjectBo = SubjectBo(SUBJECT, SubjectType.ANIME, "作品", "")
    private val collectionBo = UserCollectionBo(SUBJECT, SubjectType.ANIME, CollectionType.DOING)
    private val episodeList = listOf(EpisodeBo(101, SUBJECT, 0, "第一集", "", 1.0))

    private class Sources(scope: TestScope) {
        val subject = Channel<Load<SubjectBo>>(Channel.UNLIMITED)
        val collection = Channel<Load<UserCollectionBo?>>(Channel.UNLIMITED)
        val episodes = Channel<Load<List<EpisodeBo>>>(Channel.UNLIMITED)
        val watchStates = MutableStateFlow<Load<Map<Int, EpisodeStatus>>>(Load.Ready(emptyMap()))
        val refresh = CompletableDeferred<Failure?>()
        val out = mutableListOf<Load<CollectionWork>>()

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                workOf(
                    subject.consumeAsFlow(), collection.consumeAsFlow(), episodes.consumeAsFlow(), watchStates,
                    refreshWatchStates = { refresh.await() },
                ).toList(out)
            }
        }

        val failures get() = out.filterIsInstance<Load.Failed>()
        val lastWork get() = (out.last { it is Load.Ready } as Load.Ready).value
    }

    @Test
    fun `作品缓存后刷新失败而收藏还在路上 不提前交出失败 收藏到了才就绪并提示一次`() = runTest {
        val s = Sources(this)
        s.refresh.complete(null)
        s.subject.send(Load.Ready(subjectBo, fromCache = true))
        s.episodes.send(Load.Ready(episodeList))
        s.collection.send(Load.Pending)
        s.subject.send(Load.Failed(Failure.Offline))
        advanceUntilIdle()
        assertTrue(s.failures.isEmpty(), "面板内容还没到：作品的刷新失败不能当成整部取不到，交出的是 ${s.out}")

        s.collection.send(Load.Ready(collectionBo))
        advanceUntilIdle()
        assertEquals(CollectionWork(subjectBo, collectionBo, Load.Ready(episodeList), emptyMap()), s.lastWork)
        assertEquals(listOf(Load.Failed(Failure.Offline)), s.failures)
        assertTrue(s.out.last() is Load.Failed, "失败在内容就绪之后交出")
    }

    @Test
    fun `章节缓存后刷新失败 已显示的章节留着 失败另交一拍`() = runTest {
        val s = Sources(this)
        s.refresh.complete(null)
        s.subject.send(Load.Ready(subjectBo))
        s.collection.send(Load.Ready(collectionBo))
        s.episodes.send(Load.Ready(episodeList, fromCache = true))
        advanceUntilIdle()
        s.episodes.send(Load.Failed(Failure.Timeout))
        advanceUntilIdle()

        assertEquals(Load.Ready(episodeList, fromCache = true), s.lastWork.episodes, "章节缓存不能被刷新失败抹成错误")
        assertEquals(listOf(Load.Failed(Failure.Timeout)), s.failures)
    }

    @Test
    fun `收藏缓存后刷新失败 之后的逐集变化照常出拍 失败只交一次`() = runTest {
        val s = Sources(this)
        s.refresh.complete(null)
        s.subject.send(Load.Ready(subjectBo))
        s.collection.send(Load.Ready(collectionBo, fromCache = true))
        s.episodes.send(Load.Ready(episodeList))
        advanceUntilIdle()
        s.collection.send(Load.Failed(Failure.Offline))
        advanceUntilIdle()
        s.watchStates.value = Load.Ready(mapOf(101 to EpisodeStatus.DONE))
        advanceUntilIdle()
        s.watchStates.value = Load.Ready(mapOf(101 to EpisodeStatus.WISH))
        advanceUntilIdle()

        assertEquals(mapOf(101 to EpisodeStatus.WISH), s.lastWork.watchStates, "逐集变化不能被卡在失败上的那一路吞掉")
        assertEquals(collectionBo, s.lastWork.collection)
        assertEquals(listOf(Load.Failed(Failure.Offline)), s.failures, "同一次失败不随逐集变化反复提示")
    }

    @Test
    fun `逐集状态刷新失败 等内容就绪后才交出`() = runTest {
        val s = Sources(this)
        s.refresh.complete(Failure.Offline)
        s.subject.send(Load.Ready(subjectBo))
        s.episodes.send(Load.Ready(episodeList))
        advanceUntilIdle()
        assertTrue(s.failures.isEmpty(), "收藏还没到，交出的是 ${s.out}")

        s.collection.send(Load.Ready(collectionBo))
        advanceUntilIdle()
        assertEquals(listOf(Load.Failed(Failure.Offline)), s.failures)
        assertTrue(s.out.last() is Load.Failed)
    }

    @Test
    fun `逐集状态已有内容后读取失败 保留最后状态并只提示一次`() = runTest {
        val s = Sources(this)
        s.refresh.complete(null)
        s.subject.send(Load.Ready(subjectBo))
        s.collection.send(Load.Ready(collectionBo))
        s.episodes.send(Load.Ready(episodeList))
        advanceUntilIdle()
        s.watchStates.value = Load.Ready(mapOf(101 to EpisodeStatus.DONE))
        advanceUntilIdle()

        s.watchStates.value = Load.Failed(Failure.Storage)
        advanceUntilIdle()

        assertEquals(mapOf(101 to EpisodeStatus.DONE), s.lastWork.watchStates)
        assertEquals(listOf(Load.Failed(Failure.Storage)), s.failures)
    }

    private companion object {
        const val SUBJECT = 7
    }
}
