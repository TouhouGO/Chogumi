package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.touhougo.chogumi.core.data.db.airing.AiringTimeDao
import io.github.touhougo.chogumi.core.data.db.calendar.CalendarDao
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionDao
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.db.home.HomeProgressDao
import io.github.touhougo.chogumi.core.data.db.search.RecentSearchDao
import io.github.touhougo.chogumi.core.data.db.search.SearchFirstPageDao
import io.github.touhougo.chogumi.core.data.db.subject.EpisodeDao
import io.github.touhougo.chogumi.core.data.db.subject.SubjectDao
import io.github.touhougo.chogumi.core.data.db.subject.SubjectSectionDao
import io.github.touhougo.chogumi.core.data.db.tag.TagCatalogDao
import io.github.touhougo.chogumi.core.data.db.user.UserDao
import io.github.touhougo.chogumi.core.data.mark.CollectionMarkCoordinator
import io.github.touhougo.chogumi.core.data.progress.EpTapCoordinator
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.web.CookieCipher
import io.github.touhougo.chogumi.core.data.web.WebSessionRepository
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.model.SubjectLineup
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.network.api.AuthApi
import io.github.touhougo.chogumi.core.network.api.BangumiDataApi
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.MonoApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.github.touhougo.chogumi.core.network.api.WebLoginApi
import io.github.touhougo.chogumi.core.network.api.WebPageApi
import io.github.touhougo.chogumi.core.network.api.WebSessionApi
import io.github.touhougo.chogumi.core.network.createTokenHttpClient
import io.github.touhougo.chogumi.core.network.createWebHttpClient
import io.github.touhougo.chogumi.core.network.testApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking

/*
 * 仓库的测试构造（core:data 与各 feature 模块的单测共用这一份）。
 *
 * 生产构造函数没有默认值：每个依赖都由 Koin 显式给出，没有「单测才走」的空分支。
 * 单测通常只关心其中几项，其余在这里给测试默认值——未登录的令牌库、空的假表、
 * 直通事务、内存里的同步时刻、系统时钟、Default 调度器。
 * 参数名与顺序同生产构造函数，位置参数与具名参数都原样适用。
 */

/** 独立的临时令牌库，由 [resources] 结束 DataStore 并删除文件；给了 [userId] 就预先登录。 */
fun testTokenStore(resources: TestResources, userId: Int? = null, username: String = "fixture_user"): TokenStore {
    val dir = resources.directory("token-test")
    val store = TokenStore(PreferenceDataStoreFactory.create(scope = tokenStoreScope(resources.scope)) { File(dir, "tokens.preferences_pb") })
    if (userId != null) runBlocking {
        val pending = store.beginSession("token")
        store.completeSession(pending, userId, username)
    }
    return store
}

/**
 * 未登录的令牌库，DataStore 的生命周期由 [scope] 拥有（runTest 的 backgroundScope、测试自己的 scope）。
 * 登录由测试自己做，避免在虚拟调度器上用 runBlocking 等待磁盘写入。
 * 临时目录随作用域真正完成后删除，不在取消请求刚发出时删除。
 */
fun testTokenStore(scope: CoroutineScope): TokenStore {
    val dir = File(System.getProperty("java.io.tmpdir")!!, "token-test-${System.nanoTime()}").apply { check(mkdirs()) }
    val file = File(dir, "tokens.preferences_pb")
    scope.coroutineContext[kotlinx.coroutines.Job]!!.invokeOnCompletion { dir.deleteRecursively() }
    return TokenStore(PreferenceDataStoreFactory.create(scope = tokenStoreScope(scope)) { file })
}

/**
 * 令牌 DataStore 自己的作用域：跑在 [owner] 的调度器上（runTest 的虚拟时钟照常驱动它），Job 独立，随 owner 结束而取消
 * （与生产同构：DataStore 有自己的作用域，不与仓库共用）。不能直接把 [owner] 交给 DataStore：会话写入在 `NonCancellable` 里
 * （[TokenStore.updateSession]），owner 收尾取消时正在结束的登录还要写一笔，而 DataStore 的写入协程在正在取消的作用域上起不来——
 * 这一笔永远等不到回应，owner 也就永远结束不了（`cancelAndAwait` 超时）。
 */
private fun tokenStoreScope(owner: CoroutineScope): CoroutineScope =
    CoroutineScope(owner.coroutineContext.minusKey(kotlinx.coroutines.Job) + SupervisorJob())
        .also { store -> owner.coroutineContext.job.invokeOnCompletion { store.cancel() } }

/** 原样「加密」：JVM 单测没有 Android Keystore。 */
object PlainCookieCipher : CookieCipher {
    override fun seal(plain: String): String = plain
    override fun open(sealed: String): String = sealed
}

/** 网页客户端，配置同生产（networkModule）：不跟随跳转、非 2xx 不抛。请求由 [handler] 应答。 */
fun testWebClient(handler: MockRequestHandler): HttpClient = createWebHttpClient(MockEngine(handler))

/** 不该打开网页的测试用的网页客户端：一有请求就失败。 */
fun noWebClient(): HttpClient = testWebClient { request -> error("不该打开网页：${request.url}") }

/** 网页会话：Cookie 不加密，网页请求走 [client]。 */
fun testWebSessionRepository(tokenStore: TokenStore, client: HttpClient, cipher: CookieCipher = PlainCookieCipher): WebSessionRepository =
    WebSessionRepository(tokenStore, cipher, WebSessionApi(client))

/** 登录：用户接口走 [client]，凭据接口走独立 [tokenClient]，网页登录与网页会话走 [webClient]。 */
fun testAuthRepository(
    resources: TestResources,
    client: HttpClient,
    tokenStore: TokenStore,
    authConfig: AuthConfig,
    scope: CoroutineScope,
    webClient: HttpClient = resources.own(noWebClient()),
    tokenClient: HttpClient = resources.own(createTokenHttpClient(appVersion = "test", engine = client.engine)),
    cipher: CookieCipher = PlainCookieCipher,
): AuthRepository =
    AuthRepository(
        authApi = AuthApi(resources.own(tokenClient), authConfig),
        userApi = UserApi(resources.own(client)),
        webLoginApi = WebLoginApi(resources.own(webClient), authConfig),
        webSessions = testWebSessionRepository(tokenStore, webClient, cipher),
        tokenStore = tokenStore,
        scope = resources.own(scope),
    )

/** 不包事务，直接执行（JVM 单测没有 Room 运行时）。 */
object PassthroughTransactions : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
}

/** 内存里的「上次成功同步时刻」。 */
class InMemorySyncPreferenceStore(initial: Map<Int, Long> = emptyMap()) : SyncPreferenceStore {
    private val values = initial.toMutableMap()
    override suspend fun lastSyncedAt(accountId: Int): Long? = values[accountId]
    override suspend fun markSynced(accountId: Int, at: Long) { values[accountId] = at }
}

/** 本人收藏的整套组件，与 DataModule 一样组装：几处写入共用一份占用集合，统计与同步共用同一个同步。 */
class TestCollections(
    val observer: CollectionObserver,
    val store: CollectionStore,
    val watchStates: WatchStateStore,
    val sync: CollectionSync,
    val statistics: LocalCareerStatistics,
    val holds: LocalProgressHolds,
    val tokenStore: TokenStore,
) {
    fun progressOrchestrator(subjects: SubjectRepository) = ProgressOrchestrator(subjects, watchStates)

    fun editing(subjects: SubjectRepository, orchestrator: ProgressOrchestrator) =
        CollectionEditingImpl(subjects, store, observer, watchStates, orchestrator, tokenStore)

    fun epTapCoordinator(orchestrator: ProgressOrchestrator, scope: CoroutineScope, clock: () -> Long, awaitCommit: suspend (Long) -> Unit) =
        EpTapCoordinator(orchestrator, store, observer, watchStates, holds, tokenStore, scope, clock, awaitCommit)

    fun markCoordinator(orchestrator: ProgressOrchestrator, scope: CoroutineScope, clock: () -> Long, awaitCommit: suspend (Long) -> Unit) =
        CollectionMarkCoordinator(store, observer, tokenStore, orchestrator, scope, clock, awaitCommit)
}

fun testCollections(
    resources: TestResources,
    collectionApi: CollectionApi,
    subjectDao: SubjectDao,
    userCollectionDao: UserCollectionDao,
    tokenStore: TokenStore,
    episodeWatchStateDao: EpisodeWatchStateDao,
    episodeDao: EpisodeDao = FakeEpisodeDao(),
    syncPreferenceStore: SyncPreferenceStore = InMemorySyncPreferenceStore(),
    transactions: TransactionRunner = PassthroughTransactions,
    clock: AppClock = AppClock.SYSTEM,
    computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    lineupSource: SubjectLineupSource = NoLineupSource,
    webClient: HttpClient = resources.own(noWebClient()),
): TestCollections {
    val holds = LocalProgressHolds()
    val observer = CollectionObserver(subjectDao, userCollectionDao, episodeWatchStateDao, tokenStore, computationDispatcher)
    val store = CollectionStore(
        collectionApi, subjectDao, userCollectionDao, episodeWatchStateDao, tokenStore, syncPreferenceStore, transactions, clock,
        computationDispatcher, holds, testWebSessionRepository(tokenStore, resources.own(webClient)),
    )
    val watchStates = WatchStateStore(collectionApi, userCollectionDao, episodeWatchStateDao, episodeDao, tokenStore, transactions, clock, holds)
    val sync = CollectionSync(
        collectionApi, store, userCollectionDao, tokenStore, syncPreferenceStore, clock,
        watchStates, holds, lineupSource,
    )
    val statistics = LocalCareerStatistics(observer, sync, episodeDao, lineupSource, computationDispatcher)
    return TestCollections(observer, store, watchStates, sync, statistics, holds, tokenStore)
}

/** 没有任何阵容缓存、也不拉阵容的来源：不关心阵容的测试用它。 */
object NoLineupSource : SubjectLineupSource {
    override suspend fun cachedLineups(subjects: Map<Int, SubjectType>): Map<Int, SubjectLineup> = emptyMap()
    override suspend fun missingLineups(subjectIds: List<Int>): List<Int> = emptyList()
    override suspend fun fetchLineup(subjectId: Int, session: io.github.touhougo.chogumi.core.data.token.AccountSession, permits: kotlinx.coroutines.sync.Semaphore) = Unit
    override fun observeLineupChanges(): Flow<Unit> = flowOf(Unit)
}

/**
 * 默认的收藏仓库只在已登录时用到（每日放送顺带写入收藏状态），它的收藏接口不该被请求，
 * 请求即失败；与每日放送共用同一个令牌库。
 */
fun testCalendarRepository(
    resources: TestResources,
    nextApi: NextApi,
    calendarDao: CalendarDao,
    subjectDao: SubjectDao,
    nsfwFilter: NsfwFilter,
    tokenStore: TokenStore = testTokenStore(resources),
    collectionStore: CollectionStore = testCollections(
        resources, CollectionApi(resources.own(testApiClient { error("每日放送不请求收藏接口") })),
        subjectDao, FakeUserCollectionDao(), tokenStore, FakeEpisodeWatchStateDao(),
    ).store,
    clock: AppClock = AppClock.SYSTEM,
    computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) = CalendarRepository(nextApi, calendarDao, subjectDao, nsfwFilter, tokenStore, collectionStore, clock, computationDispatcher)

fun testSubjectRepository(
    resources: TestResources,
    subjectApi: SubjectApi,
    nextApi: NextApi,
    subjectDao: SubjectDao,
    episodeDao: EpisodeDao,
    subjectSectionDao: SubjectSectionDao,
    nsfwFilter: NsfwFilter,
    tokenStore: TokenStore = testTokenStore(resources),
    clock: AppClock = AppClock.SYSTEM,
    webPageApi: WebPageApi = WebPageApi(resources.own(testApiClient { error("这里不请求网页") })),
    computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) = SubjectRepository(subjectApi, nextApi, webPageApi, subjectDao, episodeDao, subjectSectionDao, nsfwFilter, tokenStore, clock, computationDispatcher)

fun testHomeRepository(
    nextApi: NextApi,
    homeProgressDao: HomeProgressDao,
    subjectDao: SubjectDao,
    tokenStore: TokenStore,
    collections: TestCollections,
    episodeDao: EpisodeDao,
    clock: AppClock = AppClock.SYSTEM,
    computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    transactions: TransactionRunner = PassthroughTransactions,
) = HomeRepository(
    nextApi, homeProgressDao, subjectDao, tokenStore, collections.store, collections.observer, collections.watchStates, collections.holds, episodeDao,
    transactions, clock, computationDispatcher,
)

fun testSearchRepository(
    resources: TestResources,
    subjectApi: SubjectApi,
    nextApi: NextApi,
    subjectDao: SubjectDao,
    recentSearchDao: RecentSearchDao,
    nsfwFilter: NsfwFilter,
    tokenStore: TokenStore = testTokenStore(resources),
    clock: AppClock = AppClock.SYSTEM,
    computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    firstPageDao: SearchFirstPageDao = FakeSearchFirstPageDao(),
    historyPreferences: DataStore<Preferences> = TestSearchHistoryPreferences(),
) = SearchRepository(subjectApi, nextApi, subjectDao, recentSearchDao, firstPageDao, nsfwFilter, tokenStore, clock, computationDispatcher, historyPreferences)

fun testTagCatalogRepository(
    nextApi: NextApi,
    dao: TagCatalogDao,
    nsfwFilter: NsfwFilter,
    clock: AppClock = AppClock.SYSTEM,
) = TagCatalogRepository(nextApi, dao, nsfwFilter, clock)

fun testMonoRepository(
    resources: TestResources,
    monoApi: MonoApi,
    tokenStore: TokenStore,
    nsfwFilter: NsfwFilter,
    webSessions: WebSessionRepository = testWebSessionRepository(tokenStore, resources.own(noWebClient())),
) = MonoRepositoryImpl(monoApi, tokenStore, nsfwFilter, webSessions)

fun testUserRepository(
    resources: TestResources,
    userApi: UserApi,
    tokenStore: TokenStore,
    userDao: UserDao,
    clock: AppClock = AppClock.SYSTEM,
    webSessions: WebSessionRepository = testWebSessionRepository(tokenStore, resources.own(noWebClient())),
    friends: FriendsRepository = testFriendsRepository(resources, userApi, tokenStore, clock),
    blocked: BlockedUsersSource = BlockedUsersSource.NONE,
    accountMe: AccountMe = AccountMe(userApi, tokenStore),
) = UserRepository(userApi, tokenStore, userDao, clock, webSessions, friends, blocked, accountMe)

fun testAccountMe(resources: TestResources, tokenStore: TokenStore): AccountMe =
    AccountMe(UserApi(resources.apiClient { respond("", HttpStatusCode.NotFound) }), tokenStore)

/** 好友名单：缓存落在 [resources] 管的临时偏好 DataStore 里，后台重读跑在 [scope]。 */
fun testFriendsRepository(
    resources: TestResources,
    userApi: UserApi,
    tokenStore: TokenStore,
    clock: AppClock = AppClock.SYSTEM,
    scope: CoroutineScope = resources.scope,
): FriendsRepository {
    val dir = resources.directory("friends-test")
    val dataStore = PreferenceDataStoreFactory.create(scope = resources.scope) { File(dir, "prefs.preferences_pb") }
    return FriendsRepository(dataStore, userApi, tokenStore, clock, scope)
}

fun testOtherUserCollectionsCache(
    collectionApi: CollectionApi,
    subjectDao: SubjectDao,
    tokenStore: TokenStore,
    clock: AppClock = AppClock.SYSTEM,
    computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) = OtherUserCollectionsCache(collectionApi, subjectDao, tokenStore, clock, computationDispatcher)

fun testAiringTimeRepository(
    api: BangumiDataApi,
    dao: AiringTimeDao,
    clock: AppClock = AppClock.SYSTEM,
    computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) = AiringTimeRepository(api, dao, clock, computationDispatcher)
