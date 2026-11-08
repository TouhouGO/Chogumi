package io.github.touhougo.chogumi.feature.calendar

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity
import io.github.touhougo.chogumi.core.data.db.home.HomeProgressEntity
import io.github.touhougo.chogumi.core.data.db.subject.EpisodeEntity
import io.github.touhougo.chogumi.core.data.db.subject.SubjectEntity
import io.github.touhougo.chogumi.core.data.mark.StillCollectionEditing
import io.github.touhougo.chogumi.core.data.mark.StillCollectionMarker
import io.github.touhougo.chogumi.core.data.repository.CountingDispatcher
import io.github.touhougo.chogumi.core.data.repository.FakeAiringTimeDao
import io.github.touhougo.chogumi.core.data.repository.FakeCalendarDao
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeDao
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.repository.FakeHomeProgressDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectSectionDao
import io.github.touhougo.chogumi.core.data.repository.FakeUserCollectionDao
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testAiringTimeRepository
import io.github.touhougo.chogumi.core.data.repository.testCalendarRepository
import io.github.touhougo.chogumi.core.data.repository.testCollections
import io.github.touhougo.chogumi.core.data.repository.testHomeRepository
import io.github.touhougo.chogumi.core.data.repository.testNsfwFilter
import io.github.touhougo.chogumi.core.data.repository.testSubjectRepository
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.BANGUMI_ZONE
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.data.db.airing.AiringTimeEntity
import io.github.touhougo.chogumi.core.model.AiringMinute
import io.github.touhougo.chogumi.core.model.AiringNow
import io.github.touhougo.chogumi.core.model.CalendarDaySections
import io.github.touhougo.chogumi.core.foundation.testing.testAppTime
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.BANGUMI_DATA_URL
import io.github.touhougo.chogumi.core.network.api.BangumiDataApi
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CalendarViewModelTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()


    private val main = TestMain()
    private val scope = CoroutineScope(main.dispatcher)
    private val viewModels = mutableListOf<CalendarViewModel>()
    private val computationSchedulers = mutableListOf<TestCoroutineScheduler>()
    /** 发出去的每个请求的路径，按顺序。 */
    private val requestedPaths = CopyOnWriteArrayList<String>()

    private lateinit var calendarDao: FakeCalendarDao
    private lateinit var airingTimeDao: FakeAiringTimeDao
    private lateinit var userCollectionDao: FakeUserCollectionDao
    private lateinit var subjectDao: FakeSubjectDao
    private lateinit var watchDao: FakeEpisodeWatchStateDao
    private lateinit var episodeDao: FakeEpisodeDao
    private lateinit var homeProgressDao: FakeHomeProgressDao
    private lateinit var tokenStore: TokenStore
    private lateinit var httpClient: HttpClient

    private companion object {
        /** 北京时间 2026-10-15（周四）正午：「今天」固定下来，断言不随跑测试的日子变。 */
        val THURSDAY = java.time.ZonedDateTime.of(2026, 10, 15, 12, 0, 0, 0, BANGUMI_ZONE).toInstant().toEpochMilli()

        /** 北京时间 2026-10-16（周五）凌晨 3 点：放送日仍是周四（4 点才翻页）。 */
        val FRIDAY_SMALL_HOURS = java.time.ZonedDateTime.of(2026, 10, 16, 3, 0, 0, 0, BANGUMI_ZONE).toInstant().toEpochMilli()

        /** 条目 10 的每周首播：周一 20:00 北京时间。 */
        val MONDAY_2000 = java.time.ZonedDateTime.of(2026, 10, 12, 20, 0, 0, 0, BANGUMI_ZONE).toInstant().toEpochMilli()

        /** 放送时刻表的请求路径（[BANGUMI_DATA_URL] 的路径部分）。 */
        val BANGUMI_DATA_PATH = io.ktor.http.Url(BANGUMI_DATA_URL).encodedPath
        const val ACCOUNT_ID = 123
    }

    private val calendarJson = """
        {
          "1": [
            {
              "subject": {
                "id": 10, "name": "Anime 10", "nameCN": "动画10", "type": 2,
                "images": {"large": "l10.jpg", "common": "c10.jpg", "medium": "m10.jpg", "small": "s10.jpg", "grid": "g10.jpg"},
                "info": "", "metaTags": [], "rating": {"rank": 1, "count": [], "score": 8.0, "total": 10},
                "locked": false, "nsfw": false
              },
              "watchers": 100
            },
            {
              "subject": {
                "id": 20, "name": "Anime 20", "nameCN": "动画20", "type": 2,
                "images": {"large": "l20.jpg", "common": "c20.jpg", "medium": "m20.jpg", "small": "s20.jpg", "grid": "g20.jpg"},
                "info": "", "metaTags": [], "rating": {"rank": 2, "count": [], "score": 7.5, "total": 20},
                "locked": false, "nsfw": false
              },
              "watchers": 500
            }
          ]
        }
    """.trimIndent()

    /** 放送表接口这一次回的内容。 */
    @Volatile
    private var calendarBody = calendarJson

    @BeforeTest
    fun setUp() {
        main.install()
        calendarDao = FakeCalendarDao()
        airingTimeDao = FakeAiringTimeDao()
        userCollectionDao = FakeUserCollectionDao()
        subjectDao = FakeSubjectDao()
        watchDao = FakeEpisodeWatchStateDao()
        episodeDao = FakeEpisodeDao()
        homeProgressDao = FakeHomeProgressDao()

        tokenStore = testTokenStore(scope = scope)
        runBlocking {
            val pending = tokenStore.beginSession("test-token")
            tokenStore.completeSession(pending, ACCOUNT_ID, "tester")
        }

        val engine = MockEngine { request ->
            requestedPaths += request.url.encodedPath
            respond(
                // 放送时刻表的请求回一份空的（有时刻的用例直接往表里写），放送表照常
                content = if (request.url.encodedPath == BANGUMI_DATA_PATH) "{\"items\": []}" else calendarBody,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        httpClient = resources.apiClient(engine)
    }

    @AfterTest
    fun tearDown() {
        runBlocking {
            // 可控的计算调度器上还排着的协程要推一下才能收到取消，收尾才结束。
            val teardown = async(Dispatchers.Default) { main.uninstall(viewModels.map { it.viewModelScope } + scope) }
            awaitUntil("所有协程已收尾") {
                computationSchedulers.forEach { it.runCurrent() }
                teardown.isCompleted
            }
            teardown.await()
        }
        viewModels.clear()
    }

    private fun CalendarViewModel.day(day: Int): CalendarDaySections? = uiState.value.calendar.valueOrNull()?.get(day)

    /** [dataDispatcher]：收藏观察与首页仓库的计算调度器，生产里与页面共用 [computationDispatcher]。 */
    private fun createViewModel(
        computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
        nowMillis: Long = THURSDAY,
        dataDispatcher: CoroutineDispatcher = computationDispatcher,
    ): CalendarViewModel {
        val airingTimeRepo = testAiringTimeRepository(BangumiDataApi(httpClient), airingTimeDao)
        val calendarRepo = testCalendarRepository(
            resources,
            nextApi = NextApi(httpClient),
            calendarDao = calendarDao,
            subjectDao = subjectDao,
            nsfwFilter = testNsfwFilter(resources),
            tokenStore = tokenStore,
        )
        // 收藏观察者的合并（两路失败的去重在那里）与页面的合并用同一个计算调度器，与生产一样
        val collectionRepo = testCollections(
            resources,
            collectionApi = CollectionApi(httpClient),
            subjectDao = subjectDao,
            userCollectionDao = userCollectionDao,
            tokenStore = tokenStore,
            episodeWatchStateDao = watchDao,
            episodeDao = episodeDao,
            computationDispatcher = dataDispatcher,
        )
        val subjectRepo = testSubjectRepository(
            resources,
            subjectApi = SubjectApi(httpClient),
            nextApi = NextApi(httpClient),
            subjectDao = subjectDao,
            episodeDao = episodeDao,
            subjectSectionDao = FakeSubjectSectionDao(),
            nsfwFilter = testNsfwFilter(resources),
            tokenStore = tokenStore,
        )
        val homeRepo = testHomeRepository(
            nextApi = NextApi(httpClient),
            homeProgressDao = homeProgressDao,
            subjectDao = subjectDao,
            tokenStore = tokenStore,
            collections = collectionRepo,
            episodeDao = episodeDao,
            computationDispatcher = dataDispatcher,
        )
        if (computationDispatcher is TestDispatcher) computationSchedulers += computationDispatcher.scheduler
        return CalendarViewModel(
            calendarRepo, airingTimeRepo, collectionRepo.observer, subjectRepo, homeRepo, testAppTime(nowMillis), StillCollectionMarker(), StillCollectionEditing(),
            computationDispatcher,
        ).also { viewModels.add(it) }
    }

    @Test
    fun `放送表与收藏的合并和进度计算跑在注入的计算调度器上_不占主线程`() = runBlocking {
        // 只有页面拿到计数的调度器：收藏观察与首页仓库自己也切调度器，同用一个就数不出页面切没切
        val computation = CountingDispatcher()
        val vm = createViewModel(computation, dataDispatcher = Dispatchers.Default)
        awaitUntil("放送数据加载完成") { vm.uiState.value.calendar is Load.Ready }
        assertTrue(computation.dispatches > 0, "合并与进度计算没有切到计算调度器")
    }

    @Test
    fun `初始状态默认选中今天且数据加载完成后正确分组`() = runBlocking {
        val vm = createViewModel()
        assertEquals(4, vm.uiState.value.selectedDay, "打开时选中今天（周四）")

        // 等待数据加载完成（单调条件：放送表就绪）
        awaitUntil("放送数据加载完成") { vm.day(1) != null }

        val mondaySections = vm.day(1)
        assertNotNull(mondaySections)
        assertTrue(mondaySections.following.isEmpty())
        // 默认无在追条目、放送时刻表为空：都在「时刻未知」里，按 watchers 降序：20 (500) > 10 (100)
        assertTrue(mondaySections.slots.isEmpty())
        assertEquals(listOf(20, 10), mondaySections.unknown.map { it.subject.id })
    }

    @Test
    fun `今天按放送日算_北京时间凌晨三点仍是前一天_现在线的时刻写成27点`() = runBlocking {
        val vm = createViewModel(nowMillis = FRIDAY_SMALL_HOURS)
        assertEquals(AiringNow(weekday = 4, minute = AiringMinute(27 * 60)), vm.uiState.value.now)
        assertEquals(4, vm.uiState.value.selectedDay, "周五凌晨 3 点打开，选中的还是周四")
    }

    @Test
    fun `放送时刻表里有时刻的按时刻分组_其余归时刻未知_表一变页面跟着变`() = runBlocking {
        // 服务端把条目 10 也放进了周二：时刻表里它是周一 20:00，周二那一份对不上星期
        val calendar = Json.parseToJsonElement(calendarJson).jsonObject
        calendarBody = JsonObject(calendar + ("2" to JsonArray(listOf(calendar.getValue("1").jsonArray[0])))).toString()
        val vm = createViewModel()
        awaitUntil("放送数据加载完成") { vm.day(1) != null }

        airingTimeDao.upsertAll(listOf(AiringTimeEntity(subjectId = 10, broadcastStart = MONDAY_2000)))

        awaitUntil("条目 10 进了 20:00 那一组") { vm.day(1)?.slots?.any { it.minute.label() == "20:00" } == true }
        val monday = vm.day(1)!!
        assertEquals(listOf(10), monday.slots.single().entries.map { it.subject.id })
        assertEquals(listOf(20), monday.unknown.map { it.subject.id })
        // 同一部作品在别的星期（服务端放错天）没有时刻：周二那一份归时刻未知
        val tuesday = vm.day(2)!!
        assertTrue(tuesday.slots.isEmpty())
        assertEquals(listOf(10), tuesday.unknown.map { it.subject.id })
    }

    @Test
    fun `打开每日放送时顺带取一次放送时刻表`() = runBlocking {
        val vm = createViewModel()
        awaitUntil("放送时刻表请求发出") { BANGUMI_DATA_PATH in requestedPaths }
        awaitUntil("放送数据加载完成") { vm.uiState.value.calendar is Load.Ready }
        assertEquals(1, requestedPaths.count { it == BANGUMI_DATA_PATH }, "一次打开只取一次")
    }

    @Test
    fun `切换天更新selectedDay且越界值被拒绝`() = runBlocking {
        val vm = createViewModel()
        vm.onSelectDay(5)
        assertEquals(5, vm.uiState.value.selectedDay)

        // 越界值 0 和 8 应被忽略，保持 5
        vm.onSelectDay(0)
        assertEquals(5, vm.uiState.value.selectedDay)
        vm.onSelectDay(8)
        assertEquals(5, vm.uiState.value.selectedDay)

        vm.onSelectDay(1)
        assertEquals(1, vm.uiState.value.selectedDay)
    }

    @Test
    fun `收藏观察已有内容后失败保留放送表与进度并只提示一次`() = runBlocking {
        val failure = CompletableDeferred<Unit>()
        val dao = object : FakeUserCollectionDao() {
            override fun observeCurrent(accountId: Int): Flow<List<UserCollectionEntity>> {
                val source = super.observeCurrent(accountId)
                return flow {
                    emitAll(source.take(1))
                    failure.await()
                    throw IOException("collection database unavailable")
                }
            }
        }
        dao.upsert(
            UserCollectionEntity(
                accountId = ACCOUNT_ID,
                subjectId = 10,
                subjectType = SubjectType.ANIME.value,
                type = CollectionType.DOING.value,
                epStatus = 3,
                detailsLoaded = true,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        userCollectionDao = dao
        val vm = createViewModel()
        awaitUntil("收藏已并入放送表") { vm.day(1)?.following?.any { it.subject.id == 10 } == true }
        val before = vm.day(1)
        val progressBefore = vm.uiState.value.progressCounts

        failure.complete(Unit)
        awaitUntil("存储失败提示到达") { vm.failures.current.value != null }

        val notice = vm.failures.current.value!!
        assertEquals(NoticeText.Reason(Failure.Storage), notice.text)
        assertEquals(before, vm.day(1))
        assertEquals(progressBefore, vm.uiState.value.progressCounts)
    }

    @Test
    fun `两路同一存储故障只提示一次 重试恢复两路`() = runBlocking {
        val broken = AtomicBoolean(true)
        val collectionReads = AtomicInteger()
        val episodeReads = AtomicInteger()
        val releaseSecond = CompletableDeferred<Unit>()
        val secondFailed = CompletableDeferred<Unit>()
        userCollectionDao = object : FakeUserCollectionDao() {
            override fun observeCurrent(accountId: Int): Flow<List<UserCollectionEntity>> {
                collectionReads.incrementAndGet()
                return if (broken.get()) flow { throw IOException("collections unavailable") }
                else super.observeCurrent(accountId)
            }
        }
        watchDao = object : FakeEpisodeWatchStateDao() {
            override fun observeAll(accountId: Int): Flow<List<EpisodeWatchStateEntity>> {
                episodeReads.incrementAndGet()
                return if (broken.get()) flow {
                    releaseSecond.await()
                    secondFailed.complete(Unit)
                    throw IOException("episodes unavailable")
                } else super.observeAll(accountId)
            }
        }
        // 两路的合并与去重跑在计算调度器上：由测试驱动，第二路失败那一轮推到空之后，它若要上主线程此时已经排队
        val computation = StandardTestDispatcher()
        val vm = createViewModel(computation)
        awaitUntil("首条存储失败") { computation.scheduler.runCurrent(); vm.failures.current.value?.text == NoticeText.Reason(Failure.Storage) }
        val notice = vm.failures.current.value!!
        val beforeCollection = collectionReads.get()
        val beforeEpisodes = episodeReads.get()
        vm.failures.consume(notice)
        releaseSecond.complete(Unit)
        computation.scheduler.runCurrent()
        assertTrue(secondFailed.isCompleted, "第二路的失败还没在计算调度器上跑完")
        withContext(main.dispatcher) { }
        assertEquals(null, vm.failures.current.value, "同一故障不能再发一次通知")

        broken.set(false)
        withContext(main.dispatcher) { notice.retry!!.invoke() }
        awaitUntil("两路都重新订阅") { computation.scheduler.runCurrent(); collectionReads.get() > beforeCollection && episodeReads.get() > beforeEpisodes }
        awaitUntil("放送表恢复") { computation.scheduler.runCurrent(); vm.uiState.value.calendar is Load.Ready }
    }

    @Test
    fun `本地收藏变化后自动更新在追分区_本地没有章节时进度按收藏记录落定`() = runBlocking {
        val vm = createViewModel()

        awaitUntil("放送数据加载完成") { vm.day(1) != null }

        // 初始排序：20 在前 (watchers 500)，10 在后 (watchers 100)，following 为空
        val initialSections = vm.day(1)
        assertNotNull(initialSections)
        assertEquals(listOf(20, 10), initialSections.unknown.map { it.subject.id })
        assertTrue(initialSections.following.isEmpty())

        // 用户将 10 加入本地在追收藏 (CollectionType.DOING)，看到第 3 集
        userCollectionDao.upsert(
            UserCollectionEntity(
                accountId = ACCOUNT_ID,
                subjectId = 10,
                subjectType = SubjectType.ANIME.value,
                type = CollectionType.DOING.value,
                epStatus = 3,
                detailsLoaded = true,
                updatedAt = System.currentTimeMillis(),
            ),
        )

        // 等待分区函数更新（单调条件：在追分区出现条目 10）
        awaitUntil("本地在追包含条目 10") {
            vm.day(1)?.following?.any { it.subject.id == 10 } == true
        }

        // 分区立即被重新驱动：
        // 1. following 仅包含在追条目 10
        val updatedSections = vm.day(1)
        assertNotNull(updatedSections)
        assertEquals(listOf(10), updatedSections.following.map { it.subject.id })

        // 2. 这一页不取章节：本地没有就按收藏记录给「看过 3 集」，不停在加载骨架上
        assertEquals(ProgressCount.Known(unit = null, current = 3, total = 0), vm.uiState.value.progressCounts[10])

        // 3. 全部仍然是全量（包含 10 与 20），按热度降序 20 在前、10 在后
        assertEquals(listOf(20, 10), updatedSections.unknown.map { it.subject.id })
    }

    @Test
    fun `逐集表与观看状态到齐后进度通过EpisodeTracking计算且分子分母只数正片`() = runBlocking {
        // 准备章节：3 集正片 (type 0) + 1 集特别篇 (type 1)
        val episodes = listOf(
            EpisodeEntity(id = 101, subjectId = 10, type = 0, name = "Ep 1", nameCn = "第1集", sort = 1.0),
            EpisodeEntity(id = 102, subjectId = 10, type = 0, name = "Ep 2", nameCn = "第2集", sort = 2.0),
            EpisodeEntity(id = 103, subjectId = 10, type = 0, name = "Ep 3", nameCn = "第3集", sort = 3.0),
            EpisodeEntity(id = 104, subjectId = 10, type = 1, name = "SP 1", nameCn = "特别篇", sort = 1.0),
        )
        episodeDao.upsertAll(episodes)
        // 观看状态：看过 101、102 和 104 (SP)
        // 若按 epStatus 数字是 3；但经 EpisodeTracking 计算正片只数 101 和 102，进度应为 2 / 3
        watchDao.upsertAll(
            listOf(
                EpisodeWatchStateEntity(episodeId = 101, accountId = ACCOUNT_ID, subjectId = 10, status = EpisodeStatus.DONE.value),
                EpisodeWatchStateEntity(episodeId = 102, accountId = ACCOUNT_ID, subjectId = 10, status = EpisodeStatus.DONE.value),
                EpisodeWatchStateEntity(episodeId = 103, accountId = ACCOUNT_ID, subjectId = 10, status = EpisodeStatus.NONE.value),
                EpisodeWatchStateEntity(episodeId = 104, accountId = ACCOUNT_ID, subjectId = 10, status = EpisodeStatus.DONE.value),
            ),
        )
        userCollectionDao.upsert(
            UserCollectionEntity(
                accountId = ACCOUNT_ID,
                subjectId = 10,
                subjectType = SubjectType.ANIME.value,
                type = CollectionType.DOING.value,
                epStatus = 3,
                detailsLoaded = true,
                updatedAt = System.currentTimeMillis(),
            ),
        )

        val vm = createViewModel()
        awaitUntil("条目 10 计算出正确正片进度") {
            vm.uiState.value.progressCounts[10] is ProgressCount.Known
        }

        val count = vm.uiState.value.progressCounts[10] as ProgressCount.Known
        assertEquals(2, count.current) // 正片只有 2 集看过，特典 104 不计入分子
        assertEquals(3, count.total)   // 正片只有 3 集，特典 104 不计入分母
    }

    @Test
    fun `刷新动作能正确重置isRefreshing`() = runBlocking {
        val refreshArrived = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val calendarRequests = AtomicInteger()
        httpClient = resources.apiClient(MockEngine { request ->
            val calendar = request.url.encodedPath == "/p1/calendar"
            if (calendar && calendarRequests.incrementAndGet() == 2) {
                refreshArrived.complete(Unit)
                releaseRefresh.await()
            }
            respond(
                if (calendar) calendarJson else "{\"items\": []}",
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        })
        val vm = createViewModel()
        awaitUntil("放送数据初始加载完成") { vm.uiState.value.calendar is Load.Ready }
        assertEquals(1, calendarRequests.get())
        try {
            withContext(main.dispatcher) { vm.refresh() }
            assertTrue(vm.uiState.value.isRefreshing, "刷新发起后先显示刷新态")
            awaitUntil("第二次放送请求到达") { refreshArrived.isCompleted }
            assertTrue(vm.uiState.value.isRefreshing, "响应未放行前不能结束刷新")
        } finally {
            releaseRefresh.complete(Unit)
        }
        awaitUntil("刷新完成") { !vm.uiState.value.isRefreshing }
        assertFalse(vm.uiState.value.isRefreshing)
        assertEquals(2, calendarRequests.get())
    }

    @Test
    fun `打开每日放送不请求首页进度`() = runBlocking {
        val vm = createViewModel()
        // 打开时发出的两轮各等自己的信号：放送表就绪、放送时刻表的请求发出（它前面先读一次本机的表，不与放送表同时到）
        awaitUntil("放送数据加载完成") { vm.uiState.value.calendar is Load.Ready }
        awaitUntil("放送时刻表请求发出") { BANGUMI_DATA_PATH in requestedPaths }
        // 在追进度只读本地：不跑首页进度那一整轮（/p1/home 加补全分页）
        assertEquals(setOf("/p1/calendar", BANGUMI_DATA_PATH), requestedPaths.toSet())
    }

    @Test
    fun `放送表已取回但还没和收藏合并完时_页面仍是等待而不是空`() = runBlocking {
        val computation = StandardTestDispatcher()
        val vm = createViewModel(computation)
        // 下拉刷新在主线程上发起（打开时那一轮随之取消）：之后只有这一轮的结果会把 isRefreshing 收回去，
        // 而它收回时放送表已经交给了合并。
        withContext(main.dispatcher) { vm.refresh() }
        awaitUntil("这一轮的结果已到") { !vm.uiState.value.isRefreshing }
        assertEquals(Load.Pending, vm.uiState.value.calendar, "放送表到了、合并没算完：画骨架，不画「暂无内容」")

        awaitUntil("合并算完") { computation.scheduler.runCurrent(); vm.uiState.value.calendar is Load.Ready }
        assertEquals(listOf(20, 10), vm.day(1)?.unknown?.map { it.subject.id })
    }

    /** 当前账号把 [subjectId] 标为在看。 */
    private suspend fun following(subjectId: Int) = userCollectionDao.upsert(
        UserCollectionEntity(
            accountId = ACCOUNT_ID,
            subjectId = subjectId,
            subjectType = SubjectType.ANIME.value,
            type = CollectionType.DOING.value,
            detailsLoaded = true,
            updatedAt = System.currentTimeMillis(),
        ),
    )

    /** 首页进度缓存里 [subjectId] 的章节窗口：每集 `id to airdate`，都是正片。 */
    private suspend fun homeWindow(subjectId: Int, vararg episodes: Pair<Int, String>) = homeProgressDao.upsertAll(
        listOf(
            HomeProgressEntity(
                subjectId = subjectId,
                accountId = ACCOUNT_ID,
                subjectType = SubjectType.ANIME.value,
                epsJson = episodes.withIndex().joinToString(",", "[", "]") { (index, episode) ->
                    """{"id":${episode.first},"type":0,"name":"","nameCn":"","sort":${index + 1}.0,"airdate":"${episode.second}"}"""
                },
            ),
        ),
    )

    @Test
    fun `放送表里没有的在追作品按章节的播出日进我在追`() = runBlocking {
        // 条目 30 不在 bgm.tv 放送表里；首页进度缓存说下一集 2026-10-20（周二）播，上一集周二已播
        subjectDao.upsert(SubjectEntity(id = 30, type = SubjectType.ANIME.value, name = "Donghua 30", nameCn = "国创30", updatedAt = 0L))
        homeWindow(30, 301 to "2026-10-13", 302 to "2026-10-20")
        following(30)

        val vm = createViewModel()
        awaitUntil("条目 30 进了周二的我在追") { vm.day(2)?.following?.any { it.subject.id == 30 } == true }
        assertEquals(listOf(30), vm.day(2)!!.unknown.map { it.subject.id }, "也在那一天的全部里")
        assertTrue((1..7).filter { it != 2 }.none { day -> vm.day(day)!!.following.isNotEmpty() })
    }

    @Test
    fun `在追作品放在章节说的那一天_不照放送表的星期_放送表里的其余作品照旧`() = runBlocking {
        // 放送表把条目 10 放在周一；本机章节说下一集 2026-10-17（周六）播（这周停一次）
        homeWindow(10, 101 to "2026-10-10", 102 to "2026-10-17")
        following(10)

        val vm = createViewModel()
        awaitUntil("条目 10 进了周六的我在追") { vm.day(6)?.following?.any { it.subject.id == 10 } == true }
        assertTrue(vm.day(1)!!.following.isEmpty())
        assertEquals(listOf(20), vm.day(1)!!.unknown.map { it.subject.id }, "周一只剩没在追的条目 20")
        assertEquals(listOf(10), vm.day(6)!!.unknown.map { it.subject.id })
    }
}
