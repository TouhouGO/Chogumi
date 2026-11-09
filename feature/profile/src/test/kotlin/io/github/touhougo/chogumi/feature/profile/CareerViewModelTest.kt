package io.github.touhougo.chogumi.feature.profile

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.data.repository.CollectionStatsDataSource
import io.github.touhougo.chogumi.core.data.repository.OtherCareerDataSource
import io.github.touhougo.chogumi.core.data.repository.SyncProgress
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.UserRepository
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.repository.testUserRepository
import io.github.touhougo.chogumi.core.foundation.BANGUMI_ZONE
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.testing.testAppTime
import io.github.touhougo.chogumi.core.model.CareerStatistics
import io.github.touhougo.chogumi.core.model.CollectionStatistics
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.headersOf
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 生涯 ViewModel：本人（同步、统计流）与他人（服务端摘要、按需汇总）两条路径。
 *
 * 数据源都是切面 fake——[CollectionStatsDataSource] / [OtherCareerDataSource]；
 * 他人资料走真实 [UserRepository] + MockEngine（摘要取自 p1 的 stats）。
 * 等待只认单调条件：计数只增、状态一旦到达就不回退，不采样「中途态」。
 */
class CareerViewModelTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()


    // Main 用单线程执行器（同 ProfileViewModelTest 的理由：与生产 Main 线程同语义，竞态在构造上消失）。
    private val main = TestMain()
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(bgExecutor.asCoroutineDispatcher() + SupervisorJob())
    private val viewModels = mutableListOf<CareerViewModel>()

    @BeforeTest
    fun setUp() {
        main.install()
    }

    @AfterTest
    fun tearDown() {
        main.uninstall(viewModels.map { it.viewModelScope } + scope)
        viewModels.clear()
        bgExecutor.shutdown()
    }

    // ---- fake 数据源 ----

    private class FakeStatsSource : CollectionStatsDataSource {
        private val stats = MutableStateFlow<Load<CareerStatistics>>(Load.Pending)

        @Volatile var refreshCalls = 0
        @Volatile var lastForce: Boolean? = null
        @Volatile var nextRefresh: Outcome<Unit> = Outcome.Success(Unit)

        override val syncProgress = MutableStateFlow(SyncProgress())

        override fun observeStatistics(currentYear: Int): Flow<Load<CareerStatistics>> = stats

        override suspend fun refreshAllUserCollections(force: Boolean): Outcome<Unit> {
            lastForce = force
            refreshCalls++
            return nextRefresh
        }

        override suspend fun lastSyncedAt(): Long? = SYNCED_AT

        /** 合起来的那份只用到总数，这里直接加总。 */
        fun emit(value: Map<SubjectType, CollectionStatistics>) {
            stats.value = Load.Ready(CareerStatistics(value, CollectionStatistics(totalCount = value.values.sumOf { it.totalCount })))
        }

        fun fail(failure: Failure) {
            stats.value = Load.Failed(failure)
        }
    }

    private class FakeOtherCareers : OtherCareerDataSource {
        /** 每次 statisticsFor 的类型，按到达顺序。 */
        val calls = java.util.Collections.synchronizedList(mutableListOf<SubjectType>())
        /** 已在会话内缓存里的类型（另一个 VM 汇总过）。 */
        val cached = java.util.Collections.synchronizedSet(mutableSetOf<SubjectType>())
        /** 每次合起来算时给的类型。 */
        val overallCalls = java.util.Collections.synchronizedList(mutableListOf<Set<SubjectType>>())

        @Volatile var statistics: Outcome<CollectionStatistics> = Outcome.Success(OTHER_STATS)
        /** 不为 null 时每一类的汇总挂在这里，等测试放行。 */
        @Volatile var gate: CompletableDeferred<Unit>? = null

        override suspend fun statisticsFor(
            username: String,
            subjectType: SubjectType,
            currentYear: Int,
            onProgress: (SyncProgress) -> Unit,
        ): Outcome<CollectionStatistics> {
            calls += subjectType
            gate?.await()
            return statistics
        }

        override fun hasCached(username: String, subjectType: SubjectType): Boolean = subjectType in cached

        /** 每次 coverPicksFor 的类型。 */
        val coverCalls = java.util.Collections.synchronizedList(mutableListOf<SubjectType>())

        /** 最近一页挑出的封面；默认与汇总挑的同一份。 */
        @Volatile var previewPicks: List<SubjectBo> = OTHER_OVERALL.coverPicks
        /** 不为 null 时最近一页的封面挂在这里，等测试放行。 */
        @Volatile var coverGate: CompletableDeferred<Unit>? = null

        override suspend fun coverPicksFor(username: String, subjectType: SubjectType, currentYear: Int): Outcome<List<SubjectBo>> {
            coverCalls += subjectType
            coverGate?.await()
            return Outcome.Success(previewPicks)
        }

        /** 合起来算的结果；每次调用开始时取当时的值。 */
        @Volatile var overall: CollectionStatistics = OTHER_OVERALL
        /** 不为 null 时合起来算挂在这里，等测试放行。 */
        @Volatile var overallGate: CompletableDeferred<Unit>? = null

        override suspend fun overallStatisticsFor(
            username: String,
            subjectTypes: Collection<SubjectType>,
            currentYear: Int,
        ): Outcome<CollectionStatistics> {
            val result = overall
            val gate = overallGate
            overallCalls += subjectTypes.toSet()
            gate?.await()
            return Outcome.Success(result)
        }
    }

    // ---- 构造 ----

    /** [userSubjectJson] 是 p1 `stats.subject` 的内容，如 `"2":{"1":4,"3":5}`（类型 → 状态 → 数）。 */
    private fun makeUserRepository(
        userSubjectJson: String,
        profileCached: Boolean = false,
        offline: AtomicBoolean = AtomicBoolean(false),
    ): UserRepository {
        val v0 = """{"id":42,"username":"other","nickname":"Other","user_group":10,
            "avatar":{"large":"l","medium":"m","small":"s"},"sign":""}"""
        val p1 = """{"id":42,"username":"other","nickname":"Other","avatar":{"large":"l","medium":"m","small":"s"},
            "group":10,"joinedAt":1500000000,"sign":"","site":"","location":"","bio":"","networkServices":[],
            "homepage":{"left":[],"right":[]},
            "stats":{"subject":{$userSubjectJson},"mono":{"character":0,"person":0},
            "blog":0,"friend":0,"group":0,"index":{"create":0,"collect":0}},"isFriend":false}"""
        val engine = MockEngine { request ->
            if (offline.get()) throw java.net.UnknownHostException(request.url.host)
            respond(
                content = if (request.url.host == "next.bgm.tv") p1 else v0,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val store = testTokenStore(scope = scope).also { s ->
            runBlocking {
                s.completeSession(s.beginSession("token"), 9, "fixture_user")
            }
        }
        val dao = object : io.github.touhougo.chogumi.core.data.db.user.UserDao {
            override suspend fun get(id: Int) = null
            // 用户缓存表没有「每类每状态的数」这一列：命中缓存的资料 subjectStats 为空（主页看过 TA 之后就是这样）。
            override suspend fun getByUsername(username: String) =
                if (profileCached) io.github.touhougo.chogumi.core.data.db.user.UserEntity(
                    id = 42, username = username, nickname = "Other",
                    avatarLarge = "l", avatarMedium = "m", avatarSmall = "s", sign = "",
                    updatedAt = System.currentTimeMillis(),
                ) else null
            override suspend fun upsert(user: io.github.touhougo.chogumi.core.data.db.user.UserEntity) = Unit
        }
        return testUserRepository(resources, UserApi(client), store, dao)
    }

    private fun selfVm(source: CollectionStatsDataSource): CareerViewModel {
        val users = makeUserRepository(userSubjectJson = "")
        return CareerViewModel(null, SubjectType.ANIME, false, source, FakeOtherCareers(), users, appTime = inYear2026)
            .also { viewModels += it }
    }

    private fun otherVm(
        others: FakeOtherCareers,
        userSubjectJson: String,
        profileCached: Boolean = false,
        offline: AtomicBoolean = AtomicBoolean(false),
        summaryOnly: Boolean = false,
    ): CareerViewModel {
        val users = makeUserRepository(userSubjectJson, profileCached, offline)
        return CareerViewModel("other", SubjectType.ANIME, summaryOnly, FakeStatsSource(), others, users, appTime = inYear2026)
            .also { viewModels += it }
    }

    // ---- 本人 ----

    @Test
    fun `同步时刻读取失败仍结束刷新并保留已有内容`() = runBlocking<Unit> {
        val source = FakeStatsSource()
        var readFails = false
        val failed = CompletableDeferred<Unit>()
        val wrapped = object : CollectionStatsDataSource by source {
            override suspend fun lastSyncedAt(): Long? {
                if (readFails) {
                    failed.complete(Unit)
                    throw java.io.IOException("同步时刻读失败")
                }
                return source.lastSyncedAt()
            }
        }
        val vm = withContext(main.dispatcher) { selfVm(wrapped) }
        awaitUntil("首次同步完成") { vm.uiState.value.lastSyncedAt != null }
        source.emit(mapOf(SubjectType.ANIME to SELF_STATS))
        awaitUntil("统计已显示") { vm.uiState.value.ownStats != null }
        withContext(main.dispatcher) {
            readFails = true
            vm.refresh()
        }
        failed.await()
        withContext(main.dispatcher) {
            assertEquals(false, vm.uiState.value.refreshing, "时刻读失败仍要结束刷新")
            assertEquals(SYNCED_AT, vm.uiState.value.lastSyncedAt, "失败保留上次同步时刻")
            assertEquals(NoticeText.Reason(Failure.Storage), vm.failures.current.value?.text)
        }
        assertIs<CareerContent.Ready>(vm.uiState.value.content())
    }

    @Test
    fun `本人打开即请求一次非强制同步，统计到达后画出完整内容`() = runBlocking<Unit> {
        val source = FakeStatsSource()
        val vm = selfVm(source)
        awaitUntil("首次同步完成") { vm.uiState.value.lastSyncedAt != null }
        assertEquals(1, source.refreshCalls)
        assertEquals(false, source.lastForce, "自动同步不绕过 30 分钟新鲜期")

        assertIs<CareerContent.Loading>(vm.uiState.value.content(), "统计流还没到：不能画成空态")
        source.emit(mapOf(SubjectType.ANIME to SELF_STATS))
        awaitUntil("统计到达") { vm.uiState.value.ownStats != null }
        val content = assertIs<CareerContent.Ready>(vm.uiState.value.content())
        assertEquals(3, content.ladder.done)
    }

    @Test
    fun `统计已到但这一类没有收藏时是空态而不是加载中`() = runBlocking<Unit> {
        val source = FakeStatsSource()
        val vm = selfVm(source)
        source.emit(mapOf(SubjectType.ANIME to SELF_STATS))
        awaitUntil("统计到达") { vm.uiState.value.ownStats != null }
        vm.selectType(SubjectType.BOOK)
        assertIs<CareerContent.Empty>(vm.uiState.value.content())
    }

    @Test
    fun `本人的生涯卡把各类合起来看`() = runBlocking<Unit> {
        val source = FakeStatsSource()
        val vm = selfVm(source)
        assertIs<CareerCardContent.Loading>(vm.uiState.value.cardContent(publicRatings = true), "统计流还没到：卡片是骨架")
        source.emit(mapOf(SubjectType.ANIME to SELF_STATS, SubjectType.BOOK to OTHER_STATS))
        awaitUntil("统计到达") { vm.uiState.value.ownStats != null }
        val card = assertIs<CareerCardContent.Ready>(vm.uiState.value.cardContent(publicRatings = true))
        assertEquals(3 + 5, card.portrait.done)
        assertEquals(SubjectType.BOOK, card.openType, "点卡片先看看过最多的那一类")
    }

    @Test
    fun `下拉与点刷新强制同步`() = runBlocking<Unit> {
        val source = FakeStatsSource()
        val vm = selfVm(source)
        awaitUntil("首次同步完成") { vm.uiState.value.lastSyncedAt != null && !vm.uiState.value.refreshing }

        vm.refresh()
        awaitUntil("强制同步完成") { source.refreshCalls >= 2 && !vm.uiState.value.refreshing }
        assertEquals(true, source.lastForce)
    }

    @Test
    fun `同步失败走提示条且带重试_不影响已有统计`() = runBlocking<Unit> {
        val source = FakeStatsSource().apply { nextRefresh = Outcome.Failed(Failure.Offline) }
        val vm = selfVm(source)
        source.emit(mapOf(SubjectType.ANIME to SELF_STATS))
        awaitUntil("失败发到提示条") { vm.failures.current.value != null }
        val notice = vm.failures.current.value!!
        assertEquals(NoticeText.Reason(Failure.Offline), notice.text)
        source.nextRefresh = Outcome.Success(Unit)
        notice.retry!!.invoke()
        awaitUntil("提示条上的重试强制同步") { source.refreshCalls >= 2 && source.lastForce == true }
        awaitUntil("统计到达") { vm.uiState.value.ownStats != null }
        assertIs<CareerContent.Ready>(vm.uiState.value.content())
    }

    @Test
    fun `统计观察失败保留最后统计并只提示一次`() = runBlocking<Unit> {
        val source = FakeStatsSource()
        val vm = selfVm(source)
        source.emit(mapOf(SubjectType.ANIME to SELF_STATS))
        awaitUntil("统计到达") { vm.uiState.value.ownStats != null }
        val before = vm.uiState.value.ownStats

        source.fail(Failure.Storage)
        awaitUntil("统计失败提示到达") { vm.failures.current.value != null }

        val notice = vm.failures.current.value!!
        assertEquals(NoticeText.Reason(Failure.Storage), notice.text)
        assertEquals(before, vm.uiState.value.ownStats)
    }

    // ---- 他人 ----

    @Test
    fun `他人不超过自动上限时打开即汇总`() = runBlocking<Unit> {
        val others = FakeOtherCareers()
        val vm = otherVm(others, userSubjectJson = SMALL_SUMMARY)
        awaitUntil("各类型汇总完成") { others.calls.toSet() == setOf(SubjectType.ANIME, SubjectType.BOOK) }
        awaitUntil("汇总完成") { vm.uiState.value.summaries[SubjectType.ANIME] is OtherSummary.Loaded }

        assertEquals(setOf(SubjectType.ANIME, SubjectType.BOOK), others.calls.toSet())
        assertIs<CareerContent.Ready>(vm.uiState.value.content())
    }

    @Test
    fun `主页已把TA的资料写进缓存时，生涯页仍取到收藏数而不是误判为没有公开`() = runBlocking<Unit> {
        val others = FakeOtherCareers()
        val vm = otherVm(others, userSubjectJson = SMALL_SUMMARY, profileCached = true)
        awaitUntil("汇总完成") { vm.uiState.value.summaries[SubjectType.ANIME] is OtherSummary.Loaded }

        assertIs<CareerContent.Ready>(vm.uiState.value.content())
    }

    @Test
    fun `他人的资料取不到时完整统计与生涯卡都给重试_重试成功后照常汇总`() = runBlocking<Unit> {
        val offline = AtomicBoolean(true)
        val others = FakeOtherCareers()
        val vm = otherVm(others, userSubjectJson = SMALL_SUMMARY, offline = offline)
        awaitUntil("资料取不到") { vm.uiState.value.owner is Load.Failed }
        assertIs<CareerContent.Unavailable>(vm.uiState.value.content())
        assertIs<CareerCardContent.Unavailable>(vm.uiState.value.cardContent(publicRatings = true))

        offline.set(false)
        vm.retryOwner()
        awaitUntil("重试后汇总完成") { vm.uiState.value.summaries[SubjectType.ANIME] is OtherSummary.Loaded }
        assertIs<CareerContent.Ready>(vm.uiState.value.content())
    }

    @Test
    fun `他人的称呼取昵称而不是路由里的用户名`() = runBlocking<Unit> {
        val vm = otherVm(FakeOtherCareers(), userSubjectJson = SMALL_SUMMARY)
        awaitUntil("摘要到达") { vm.uiState.value.owner is Load.Ready }

        assertEquals("Other", vm.uiState.value.ownerName)
    }

    @Test
    fun `超过自动上限不自动汇总，点按钮才拉一次`() = runBlocking<Unit> {
        val others = FakeOtherCareers()
        val vm = otherVm(others, userSubjectJson = HUGE_SUMMARY)
        awaitUntil("摘要到达") { vm.uiState.value.owner is Load.Ready }
        val waiting = assertIs<CareerContent.AwaitingConfirm>(vm.uiState.value.content())
        assertEquals(1001, waiting.count)

        vm.summarize()
        awaitUntil("汇总完成") { vm.uiState.value.summaries[SubjectType.ANIME] is OtherSummary.Loaded }
        // 若超限时误自动汇总，这里会多一条记录
        assertEquals(listOf(SubjectType.ANIME), others.calls.toList())
    }

    @Test
    fun `超过自动上限但本次会话已汇总过时，新开的页面直接读缓存不再要求点按钮`() = runBlocking<Unit> {
        val others = FakeOtherCareers().apply { cached += SubjectType.ANIME }
        val vm = otherVm(others, userSubjectJson = HUGE_SUMMARY)
        awaitUntil("汇总完成") { vm.uiState.value.summaries[SubjectType.ANIME] is OtherSummary.Loaded }

        assertIs<CareerContent.Ready>(vm.uiState.value.content())
        assertEquals(listOf(SubjectType.ANIME), others.calls.toList())
    }

    @Test
    fun `汇总失败给失败态，重试成功后换成内容`() = runBlocking<Unit> {
        val others = FakeOtherCareers().apply { statistics = Outcome.Failed(Failure.Timeout) }
        val vm = otherVm(others, userSubjectJson = SMALL_SUMMARY)
        awaitUntil("失败态") { vm.uiState.value.summaries[SubjectType.ANIME] is OtherSummary.Failed }
        assertEquals(Failure.Timeout, assertIs<CareerContent.Failed>(vm.uiState.value.content()).failure)

        others.statistics = Outcome.Success(OTHER_STATS)
        vm.summarize()
        awaitUntil("重试成功") { vm.uiState.value.summaries[SubjectType.ANIME] is OtherSummary.Loaded }
        assertIs<CareerContent.Ready>(vm.uiState.value.content())
    }

    @Test
    fun `他人的生涯卡等可自动汇总的类别都汇总完，再合起来算一份`() = runBlocking<Unit> {
        val others = FakeOtherCareers()
        val vm = otherVm(others, userSubjectJson = SMALL_SUMMARY)
        awaitUntil("合起来算过") { vm.uiState.value.otherOverall != null }

        assertEquals(listOf(setOf(SubjectType.ANIME, SubjectType.BOOK)), others.overallCalls.toList())
        val card = assertIs<CareerCardContent.Ready>(vm.uiState.value.cardContent(publicRatings = true))
        assertEquals(5, card.portrait.done, "完成数读服务端摘要")
        assertEquals(OTHER_OVERALL.coverPicks, card.portrait.covers)
    }

    @Test
    fun `先开始的合并晚到时不覆盖后一次合并的结果`() = runBlocking<Unit> {
        val first = CompletableDeferred<Unit>()
        val others = FakeOtherCareers().apply { overallGate = first }
        val vm = otherVm(others, userSubjectJson = SMALL_SUMMARY)
        awaitUntil("第一次合起来算开始") { others.overallCalls.size == 1 }

        val newer = OTHER_OVERALL.copy(totalCount = 11)
        others.overallGate = null
        others.overall = newer
        vm.summarize(SubjectType.ANIME)
        awaitUntil("后一次合起来算落下") { vm.uiState.value.otherOverall == newer }

        // 假数据源不换线程，第一次合并放行后整轮在 Main 上：排空 Main，它的写入（如果有）已经落下
        first.complete(Unit)
        withContext(Dispatchers.Main) { }
        assertEquals(newer, vm.uiState.value.otherOverall, "先开始的那次晚到，不能把结果换回旧的")
    }

    @Test
    fun `他人的生涯卡不等汇总：资料到了先画计数与最近一页的封面，汇总完再补均分`() = runBlocking<Unit> {
        val others = FakeOtherCareers().apply { gate = CompletableDeferred() }
        val vm = otherVm(others, userSubjectJson = SMALL_SUMMARY)
        awaitUntil("汇总开始") { others.calls.isNotEmpty() }
        awaitUntil("封面到了") { vm.uiState.value.otherCovers.isNotEmpty() }

        val early = assertIs<CareerCardContent.Ready>(vm.uiState.value.cardContent(publicRatings = true), "汇总还挂着，卡片已经画出").portrait
        assertEquals(5, early.done, "完成数读服务端摘要")
        assertEquals(OTHER_OVERALL.coverPicks, early.covers)
        assertNull(early.average, "均分要汇总才有")

        others.gate!!.complete(Unit)
        awaitUntil("合起来算过") { vm.uiState.value.otherOverall != null }
        assertEquals(7.5, assertIs<CareerCardContent.Ready>(vm.uiState.value.cardContent(publicRatings = true)).portrait.average)
    }

    @Test
    fun `他人的生涯卡封面先到的留着：最近一页先画出，汇总挑出另一份也不换`() = runBlocking<Unit> {
        val others = FakeOtherCareers().apply { gate = CompletableDeferred(); previewPicks = PREVIEW_PICKS }
        val vm = otherVm(others, userSubjectJson = SMALL_SUMMARY)
        awaitUntil("最近一页的封面画出") { vm.uiState.value.otherCovers.isNotEmpty() }

        others.gate!!.complete(Unit)
        awaitUntil("合起来算过") { vm.uiState.value.otherOverall != null }
        val card = assertIs<CareerCardContent.Ready>(vm.uiState.value.cardContent(publicRatings = true)).portrait
        assertEquals(7.5, card.average, "汇总补上均分")
        assertEquals(PREVIEW_PICKS, card.covers, "汇总挑的封面不换掉已画出的：整块换图每格先空再变")
    }

    @Test
    fun `他人的生涯卡封面先到的留着：汇总先出，晚到的最近一页不换掉`() = runBlocking<Unit> {
        val others = FakeOtherCareers().apply { coverGate = CompletableDeferred(); previewPicks = PREVIEW_PICKS }
        val vm = otherVm(others, userSubjectJson = SMALL_SUMMARY)
        awaitUntil("合起来算过") { vm.uiState.value.otherOverall != null }
        assertEquals(OTHER_OVERALL.coverPicks, assertIs<CareerCardContent.Ready>(vm.uiState.value.cardContent(publicRatings = true)).portrait.covers)

        // 最近一页的读取整轮在 Main 上（假数据源不换线程）：放行后排空 Main，那一轮的写入已经落下
        others.coverGate!!.complete(Unit)
        withContext(Dispatchers.Main) { }
        assertEquals(OTHER_OVERALL.coverPicks, assertIs<CareerCardContent.Ready>(vm.uiState.value.cardContent(publicRatings = true)).portrait.covers)
    }

    @Test
    fun `他人每一类都超过自动上限时生涯卡照画计数，不拉收藏`() = runBlocking<Unit> {
        val others = FakeOtherCareers()
        val vm = otherVm(others, userSubjectJson = HUGE_SUMMARY)
        awaitUntil("合起来算过") { vm.uiState.value.otherOverall != null }
        awaitUntil("封面到了") { vm.uiState.value.otherCovers.isNotEmpty() }

        assertTrue(others.calls.isEmpty())
        assertTrue(others.overallCalls.isEmpty())
        val card = assertIs<CareerCardContent.Ready>(vm.uiState.value.cardContent(publicRatings = true))
        assertEquals(501, card.portrait.done)
        assertEquals(OTHER_OVERALL.coverPicks, card.portrait.covers, "没有汇总的封面，用最近一页挑的")
    }

    @Test
    fun `只要摘要时生涯卡画服务端计数，不拉收藏也不合起来算`() = runBlocking<Unit> {
        val others = FakeOtherCareers()
        val vm = otherVm(others, userSubjectJson = SMALL_SUMMARY, summaryOnly = true)
        awaitUntil("摘要画出") { vm.uiState.value.cardContent(publicRatings = true) is CareerCardContent.Ready }

        assertTrue(others.calls.isEmpty())
        assertTrue(others.overallCalls.isEmpty())
        assertTrue(vm.uiState.value.summaries.isEmpty())
        val card = assertIs<CareerCardContent.Ready>(vm.uiState.value.cardContent(publicRatings = true))
        assertEquals(5, card.portrait.done)
        awaitUntil("封面到了") { vm.uiState.value.otherCovers.isNotEmpty() }
        assertEquals(listOf(SubjectType.ANIME), others.coverCalls.toList())
        assertEquals(OTHER_OVERALL.coverPicks, assertIs<CareerCardContent.Ready>(vm.uiState.value.cardContent(publicRatings = true)).portrait.covers)
    }

    @Test
    fun `只要摘要时封面取看过最多的那一类，不一定是动画`() = runBlocking<Unit> {
        val others = FakeOtherCareers()
        val vm = otherVm(others, userSubjectJson = """"1":{"2":9},"2":{"1":4,"2":2}""", summaryOnly = true)
        awaitUntil("封面到了") { vm.uiState.value.otherCovers.isNotEmpty() }

        assertEquals(listOf(SubjectType.BOOK), others.coverCalls.toList())
    }

    @Test
    fun `只要摘要且没有任何收藏时不请求封面`() = runBlocking<Unit> {
        val others = FakeOtherCareers()
        val vm = otherVm(others, userSubjectJson = "", summaryOnly = true)
        awaitUntil("摘要画出") { vm.uiState.value.cardContent(publicRatings = true) is CareerCardContent.Empty }

        assertTrue(others.coverCalls.isEmpty())
    }

    @Test
    fun `这一类服务端摘要为零时直接空态，不去拉`() = runBlocking<Unit> {
        val others = FakeOtherCareers()
        val vm = otherVm(others, userSubjectJson = SMALL_SUMMARY)
        awaitUntil("动画汇总完成") { vm.uiState.value.summaries[SubjectType.ANIME] is OtherSummary.Loaded }

        vm.selectType(SubjectType.GAME)
        assertIs<CareerContent.Empty>(vm.uiState.value.content())
        // 先切到有数据的书籍并等它落定：之后若游戏被误拉，calls 里必有它
        vm.selectType(SubjectType.BOOK)
        awaitUntil("书籍汇总完成") { vm.uiState.value.summaries[SubjectType.BOOK] is OtherSummary.Loaded }
        assertTrue(SubjectType.GAME !in others.calls)
    }

    private companion object {
        const val SYNCED_AT = 1_760_000_000_000L

        /** 动画 4 想看 + 5 看过；书籍 1 想看。 */
        const val SMALL_SUMMARY = """"2":{"1":4,"2":5},"1":{"1":1}"""

        /** 动画 1001 部：刚好越过自动上限。 */
        const val HUGE_SUMMARY = """"2":{"1":500,"2":501}"""

        val SELF_STATS = CollectionStatistics(
            totalCount = 3,
            statusCounts = mapOf(CollectionType.DONE to 3),
        )
        val OTHER_STATS = CollectionStatistics(
            totalCount = 9,
            statusCounts = mapOf(CollectionType.WISH to 4, CollectionType.DONE to 5),
        )
        val PREVIEW_PICKS = listOf(SubjectBo(id = 2, type = SubjectType.ANIME, name = "b", nameCn = "b", imageLarge = "https://lain.bgm.tv/b.jpg"))
        val OTHER_OVERALL = CollectionStatistics(
            totalCount = 10,
            averageRating = 7.5,
            coverPicks = listOf(SubjectBo(id = 1, type = SubjectType.ANIME, name = "a", nameCn = "a", imageLarge = "https://lain.bgm.tv/a.jpg")),
        )
    }

    /** 时光机看到 2026 年：今天固定在 2026 年年中。 */
    private val inYear2026 = testAppTime(LocalDate.of(2026, 6, 1).atStartOfDay(BANGUMI_ZONE).toInstant().toEpochMilli())
}
