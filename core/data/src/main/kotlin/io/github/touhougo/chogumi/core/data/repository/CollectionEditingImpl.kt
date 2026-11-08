package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.authenticatedOutcome
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.advance
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.hiddenFailureOf
import io.github.touhougo.chogumi.core.model.CollectionEditing
import io.github.touhougo.chogumi.core.model.CollectionEdits
import io.github.touhougo.chogumi.core.model.CollectionSaveResult
import io.github.touhougo.chogumi.core.model.CollectionWork
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch

/**
 * 收藏面板的数据通路（[CollectionEditing]）：取一部作品（[observeWork]）、存一张表（[save]）。
 */
class CollectionEditingImpl(
    private val subjectRepository: SubjectRepository,
    private val collectionStore: CollectionStore,
    private val collectionObserver: CollectionObserver,
    private val watchStateStore: WatchStateStore,
    private val progressOrchestrator: ProgressOrchestrator,
    private val tokenStore: TokenStore,
) : CollectionEditing {
    override suspend fun remove(subjectId: Int): Outcome<Unit> = tokenStore.authenticatedOutcome { session, _ ->
        progressOrchestrator.withSubjectLock(subjectId) { collectionStore.removeCollection(subjectId, session) }
    }

    /** 列表页长按的那一部：四路数据怎样合成一拍见 [workOf]。订阅时顺带从服务端刷一遍逐集状态（落库后经逐集状态流回来）。 */
    override fun observeWork(subjectId: Int): Flow<Load<CollectionWork>> = workOf(
        subject = flow { emitAll(subjectRepository.getSubject(subjectId)) },
        collection = collectionStore.getCollection(subjectId),
        episodes = flow { emitAll(subjectRepository.getEpisodes(subjectId)) },
        watchStates = collectionObserver.observeEpisodeWatchStates(subjectId),
        refreshWatchStates = { watchStateStore.refreshEpisodeWatchStates(subjectId).failureOrNull() },
    )

    /**
     * 存面板上的修改：发什么由 [io.github.touhougo.chogumi.core.model.CollectionEditor.saveOf] 定——收藏记录只发改了的字段
     * （书籍的话卷数也在里面），动画、三次元改了进度再「看到第 N 集」。先存记录，记录没成就不动进度。
     *
     * 两步一起持着这一部的条目锁（[ProgressOrchestrator.withSubjectLock]，与长按标记的提交、章节写入同一把）：
     * 先到的整笔写完，后到的才开始，不会插进记录与进度之间。进度那一步在锁里重入，不会自己等自己。
     * 进入等待前绑定账号，两步沿同一会话；等待或请求途中换号，保存失败而不写进新账号。
     */
    override suspend fun save(work: CollectionWork, edits: CollectionEdits): CollectionSaveResult {
        var step = CollectionSaveResult.Step.RECORD
        val result = tokenStore.authenticatedOutcome { session, _ ->
            val subject = work.subject
            val save = work.editor.saveOf(edits)
            progressOrchestrator.withSubjectLock(subject.id) {
                tokenStore.requireCurrent(session)
                val record = collectionStore.updateCollection(subject.id, save.modify, expectedSession = session)
                if (record is Outcome.Failed) return@withSubjectLock record
                val target = save.watchUpTo ?: return@withSubjectLock Outcome.Success(Unit)
                step = CollectionSaveResult.Step.PROGRESS
                progressOrchestrator.markWatchedUpTo(subject.id, target, LocalPublish.Immediate, session)
            }
        }
        return when (result) {
            is Outcome.Success -> CollectionSaveResult.Saved
            is Outcome.Failed -> CollectionSaveResult.Failed(step, result.failure)
        }
    }
}

/**
 * 收藏面板要的一部作品（唯一合成）：作品与收藏都到了才就绪，有一样取不到且没有缓存就是失败；章节、逐集状态跟着走，
 * 章节取不到且没有缓存时落在进度区（面板就地重试）。
 *
 * 每一路先回显缓存，之后的网络拍往前走一步（[advance]）：已就绪的值不被刷新失败抹掉，所以组合里各路一直拿着自己的最新内容，
 * 逐集状态之后的每次变化都照常出新的一拍。某一路在已有内容后刷新失败（或 [refreshWatchStates] 失败），这次失败只交出一拍
 * [Load.Failed]，并且等面板内容第一次就绪之后才交：页面的分区收集器把它当作「内容在、刷新失败」保留内容并提示一次，
 * 不会在内容还没到时把它当成整部取不到。
 */
internal fun workOf(
    subject: Flow<Load<SubjectBo>>,
    collection: Flow<Load<UserCollectionBo?>>,
    episodes: Flow<Load<List<EpisodeBo>>>,
    watchStates: Flow<Load<Map<Int, EpisodeStatus>>>,
    refreshWatchStates: suspend () -> Failure?,
): Flow<Load<CollectionWork>> = channelFlow {
    val contentReady = CompletableDeferred<Unit>()
    fun report(failure: Failure) {
        launch {
            contentReady.await()
            send(Load.Failed(failure))
        }
    }
    fun <T> Flow<Load<T>>.shown(): Flow<Load<T>> = scan(Load.Pending as Load<T>) { shown, next ->
        shown.hiddenFailureOf(next)?.let(::report)
        shown.advance(next)
    }
    launch { refreshWatchStates()?.let(::report) }
    combine(subject.shown(), collection.shown(), episodes.shown(), watchStates.shown()) { subject, collection, episodes, states ->
        when {
            subject is Load.Failed -> subject
            collection is Load.Failed -> collection
            states is Load.Failed -> states
            subject is Load.Ready && collection is Load.Ready && states is Load.Ready ->
                Load.Ready(CollectionWork(subject.value, collection.value, episodes, states.value))
            else -> Load.Pending
        }
    }.collect {
        send(it)
        if (it is Load.Ready) contentReady.complete(Unit)
    }
}
