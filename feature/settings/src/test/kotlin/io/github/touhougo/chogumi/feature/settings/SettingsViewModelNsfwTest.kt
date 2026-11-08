package io.github.touhougo.chogumi.feature.settings

import io.github.touhougo.chogumi.core.data.repository.testAuthRepository
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotice
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.data.repository.AuthRepository
import io.github.touhougo.chogumi.core.data.repository.ConcurrentRequestRecorder
import io.github.touhougo.chogumi.core.data.repository.DisplayScaleRepository
import io.github.touhougo.chogumi.core.data.repository.cancelAndAwait
import io.github.touhougo.chogumi.core.data.repository.NsfwPreferenceRepository
import io.github.touhougo.chogumi.core.data.repository.noWebClient
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.testUserRepository
import io.github.touhougo.chogumi.core.data.repository.testWebSessionRepository
import io.github.touhougo.chogumi.core.data.db.user.UserDao
import io.github.touhougo.chogumi.core.data.db.user.UserEntity
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.model.AppIcon
import io.github.touhougo.chogumi.core.model.CoverQuality
import io.github.touhougo.chogumi.core.model.DisplayScale
import io.github.touhougo.chogumi.core.model.ExperienceMode
import io.github.touhougo.chogumi.core.model.ReadingTextSize
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.ThemeColorMode
import io.github.touhougo.chogumi.core.model.ThemeMode
import io.github.touhougo.chogumi.core.model.ThemePreferences
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.network.api.PrivacyApi
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.github.touhougo.chogumi.core.network.createTokenHttpClient
import io.github.touhougo.chogumi.core.network.testApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 设置页 NSFW 开关的**四态**。
 *
 * 真源是 bgm.tv 账号设置，本地只是缓存，故界面必须分清四种状态：
 * 未登录 / 已登录但拿不准（加载中或失败）/ 账号无权改 / 可改。
 * 断言的是**最终态**（`first { 条件 }`），不使用 delay/Turbine。
 */
class SettingsViewModelNsfwTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()


    // Main 用单线程执行器而非 UnconfinedTestDispatcher：Unconfined 会让 VM 协程
    // 恢复点散落到 IO 线程并发交叠，"读 state→copy→写回" 互相覆盖（同 feature:progress）。
    // 单线程串行与生产 Main 线程 confinement 同语义，竞态在构造上消失。
    private val main = TestMain()
    private val escapedErrors = MutableStateFlow<List<Throwable>>(emptyList())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, error ->
        escapedErrors.update { it + error }
    })
    private val viewModels = mutableListOf<ViewModel>()
    private val clients = mutableListOf<HttpClient>()
    private val engines = mutableListOf<HttpClientEngine>()
    private val directories = mutableListOf<File>()
    private var previousErrorHandler: Thread.UncaughtExceptionHandler? = null

    @BeforeTest
    fun setUp() {
        main.install()
        runBlocking(main.dispatcher) {
            previousErrorHandler = Thread.currentThread().uncaughtExceptionHandler
            Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                escapedErrors.update { it + error }
            }
        }
    }

    @AfterTest
    fun tearDown() {
        val scopes = viewModels.map { it.viewModelScope } + scope
        cancelAndAwait(scopes)
        clients.forEach { it.close() }
        engines.forEach { it.close() }
        cancelAndAwait(clients.map { CoroutineScope(it.coroutineContext) } + engines.map { CoroutineScope(it.coroutineContext) })
        runBlocking(main.dispatcher) { Thread.currentThread().uncaughtExceptionHandler = previousErrorHandler }
        main.uninstall(scopes)
        directories.forEach { it.deleteRecursively() }
        viewModels.clear()
        clients.clear()
        engines.clear()
        directories.clear()
        assertEquals(emptyList(), escapedErrors.value, "异常必须进入失败通道，不得逃出生产作用域")
    }

    private class Harness(
        /** 外观、首页与进度、评分与社区三页的 ViewModel。 */
        val prefs: PreferenceSettingsViewModel,
        /** 「内容过滤」页的 ViewModel（NSFW）。 */
        val content: ContentSettingsViewModel,
        /** 一级菜单的 ViewModel（图片缓存、退出登录）。 */
        val home: SettingsViewModel,
        val nsfwRepository: NsfwPreferenceRepository,
        val authRepository: AuthRepository,
        val preferencesRepository: PreferencesRepository,
        /** 偏好写入完成的次数，只增不减。 */
        val preferencesWrites: StateFlow<Int>,
        /** 偏好流已有首个值的收集者个数，只增不减。 */
        val collectorsFirstRead: StateFlow<Int>,
        val requests: ConcurrentRequestRecorder,
        val appIcons: FakeAppIcons,
        val imageCache: FakeImageCache,
    ) {
        /** [awaitStoredThenShown] 已经等过的写入次数：每次切换多等一次。 */
        var writesAwaited = 0
    }

    /** [responder] 决定 /p1/privacy 与 /v0/me 的返回；登录走真实 AuthRepository。 */
    /**
     * [gatePreferencesRead] 非空时，**偏好 DataStore 的首次读取会被按住**，直到该 Deferred 完成。
     *
     * 这是给「体验模式切换只写偏好」那条回归用的注入延迟：它把"DataStore 首读晚到"这个
     * 真实竞态变成确定性的——不需要 delay，只要不放行，首读就一定还没发生。
     */
    private fun harness(
        gatePreferencesRead: CompletableDeferred<Unit>? = null,
        faultNsfwRead: () -> Boolean = { false },
        faultNsfwWrite: () -> Boolean = { false },
        faultPreferencesRead: () -> Boolean = { false },
        initialIcon: AppIcon = AppIcon.DEFAULT,
        failSelect: Boolean = false,
        cacheBytes: Long = 0L,
        responder: suspend MockRequestScope.() -> HttpResponseData,
    ): Harness {
        val dir = File(System.getProperty("java.io.tmpdir")!!, "settings-nsfw-${System.nanoTime()}").apply {
            mkdirs()
            directories += this
        }
        val requests = ConcurrentRequestRecorder()
        val engine = MockEngine {
            requests.record(it)
            responder(MockRequestScope(this, it))
        }.also { engines += it }
        val client = testApiClient(engine).also { clients += it }
        val webClient = noWebClient().also { clients += it; engines += it.engine }
        val tokenClient = createTokenHttpClient(appVersion = "test", engine = engine).also { clients += it }
        fun dataStore(name: String): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "$name.preferences_pb")
        }
        val tokenStore = TokenStore(dataStore("tokens"))
        val observedPreferences = ObservedPreferencesStore(dataStore("prefs"), gatePreferencesRead, faultPreferencesRead)
        val preferences = PreferencesRepository(observedPreferences)
        val nsfwRepository = NsfwPreferenceRepository(
            dataStore = ObservedPreferencesStore(dataStore("nsfw"), readGate = null, faultRead = faultNsfwRead, faultWrite = faultNsfwWrite),
            privacyApi = PrivacyApi(client),
            tokenStore = tokenStore,
            webSessions = testWebSessionRepository(tokenStore, webClient),
        )
        val authRepository = testAuthRepository(
            resources, client, tokenStore, AuthConfig("id", "secret", "chogumi://oauth/callback"), scope,
            webClient = webClient, tokenClient = tokenClient,
        )
        val appIcons = FakeAppIcons(initialIcon, failSelect)
        val imageCache = FakeImageCache(cacheBytes)
        // 三页的 ViewModel 都在构造时开始读，与打开各自的页一样。
        val prefs = PreferenceSettingsViewModel(preferences, DisplayScaleRepository(DisplayScale.SYSTEM) {}, appIcons)
            .also { viewModels += it }
        val content = ContentSettingsViewModel(nsfwRepository).also { viewModels += it }
        val users = testUserRepository(resources, UserApi(client), tokenStore, NoCachedUsers)
        val home = SettingsViewModel(authRepository, users, imageCache).also { viewModels += it }
        return Harness(
            prefs, content, home, nsfwRepository, authRepository, preferences,
            observedPreferences.writesDone, observedPreferences.collectorsFirstRead,
            requests, appIcons, imageCache,
        )
    }

    /**
     * 偏好 DataStore 的测试装饰器：数写入完成了几次（[writesDone]）；[readGate] 非空时，`data` 的收集先等它放行，
     * 写入照常直通。
     *
     * 读闸把「偏好首读晚到」做成确定事件（见 [harness] 的参数说明），无需 delay 就能证明界面状态是等流回放来的。
     */
    /** 用户缓存是空的：一级菜单的资料行在这里的测试都还没登录，只读不写。 */
    private object NoCachedUsers : UserDao {
        override suspend fun get(id: Int): UserEntity? = null
        override suspend fun getByUsername(username: String): UserEntity? = null
        override suspend fun upsert(user: UserEntity) = Unit
    }

    /** 系统里开着的入口：[select] 记下每次切换；[failSelect] 时切换抛出（入口没换）。 */
    private class FakeAppIcons(initial: AppIcon, private val failSelect: Boolean) : AppIcons {
        @Volatile
        var enabled: AppIcon = initial
        val selected = java.util.concurrent.CopyOnWriteArrayList<AppIcon>()

        /** 切换之后系统里实际开着哪个入口：读回与请求的不同时，界面该画读回的那个。 */
        @Volatile
        var enabledAfterSelect: (AppIcon) -> AppIcon = { it }

        override suspend fun current(): AppIcon = enabled

        override suspend fun select(icon: AppIcon) {
            selected += icon
            if (failSelect) throw IOException("入口切换失败")
            enabled = enabledAfterSelect(icon)
        }
    }

    /** 磁盘上的图片缓存：清完还剩正在用的 [IN_USE_BYTES]（量出来的数不是零），界面该画量出来的那个。 */
    private class FakeImageCache(initialBytes: Long) : ImageCache {
        @Volatile
        var bytes: Long = initialBytes
        val clears = AtomicInteger()

        override suspend fun size(): Long = bytes

        override suspend fun clear() {
            clears.incrementAndGet()
            bytes = IN_USE_BYTES
        }
    }

    private class ObservedPreferencesStore(
        private val delegate: DataStore<Preferences>,
        readGate: CompletableDeferred<Unit>?,
        private val faultRead: () -> Boolean = { false },
        private val faultWrite: () -> Boolean = { false },
    ) : DataStore<Preferences> {
        val writesDone = MutableStateFlow(0)
        val collectorsFirstRead = MutableStateFlow(0)

        private val gated: Flow<Preferences> = if (readGate == null) delegate.data else delegate.data.onStart { readGate.await() }

        override val data: Flow<Preferences> = flow {
            var first = true
            gated.collect { value ->
                if (faultRead()) throw IOException("无法读取偏好存储")
                if (first) {
                    first = false
                    collectorsFirstRead.update { it + 1 }
                }
                emit(value)
            }
        }

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = delegate.updateData { current ->
            val next = transform(current)
            if (faultWrite()) throw IOException("无法写入偏好存储")
            next
        }.also { writesDone.update { it + 1 } }
    }

    private inner class MockRequestScope(
        private val handle: MockRequestHandleScope,
        private val request: HttpRequestData,
    ) {
        val path: String get() = request.url.encodedPath
        val isPatch: Boolean get() = request.method == HttpMethod.Patch
        val bearer: String? get() = request.headers["Authorization"]
        val requestJob: Job get() = request.executionContext

        /** /p1/privacy 的响应；allowNsfw/canSet 由服务端判定。 */
        fun privacy(allowNsfw: Boolean, showNsfwSubject: Boolean = allowNsfw, canSet: Boolean = true): HttpResponseData =
            with(handle) {
                respond(
                    content = privacyJson(allowNsfw, showNsfwSubject, canSet),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }

        fun privacyForbidden(): HttpResponseData = with(handle) { respondError(HttpStatusCode.Forbidden) }

        /** /p1/privacy PATCH 命中 5xx（线上实测形态）。 */
        fun privacyServerError(): HttpResponseData = with(handle) {
            respond(
                content = """{"error":"Internal Server Error","message":"internal error, please contact admin","statusCode":500}""",
                status = HttpStatusCode.InternalServerError,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }

        /** /v0/me：登录校验用。nickname 是 v0 UserDto 的必需字段，缺了会解析失败。 */
        fun me(id: Int = 1001, username: String = "tester"): HttpResponseData = with(handle) {
            respond(
                content = """{"id":$id,"username":"$username","nickname":"$username","user_group":10,
                    "avatar":{"large":"l","medium":"m","small":"s"},"sign":"","email":"a@b.c","reg_time":"2020-01-01T00:00:00Z"}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
    }

    private fun privacyJson(allowNsfw: Boolean, showNsfwSubject: Boolean, canSet: Boolean) = """
        {
          "settings": {
            "privateMessage": "all", "timelineReply": "all", "timelineCollectReply": "all",
            "follow": "all", "mentionNotification": "all", "commentNotification": "all",
            "friendNotification": "all"
          },
          "preferences": {
            "showNsfwSubject": $showNsfwSubject, "canSetNsfwSubject": $canSet, "allowNsfw": $allowNsfw
          }
        }
    """.trimIndent()

    /**
     * 开关切换后的最终态：先等这次写入完成，再看偏好里存的，最后等界面跟上。超时时报出界面当时的值，
     * 分得清是没写进偏好，还是写进了而界面没收到。
     *
     * 新起的偏好流收集者必须等写入完成后再起：DataStore（1.2.1）的新收集者在写入途中开始时，先读文件、后读版本号，
     * 可能拿到旧内容配上新版本号；缓存里随后的新值版本号不比它新，被当成看过的丢掉，这个收集者就一直停在旧值，
     * 直到下一次写入。
     */
    private suspend fun <T> Harness.awaitStoredThenShown(expected: T, stored: Flow<T>, shown: (PreferenceSettingsUiState) -> T) {
        writesAwaited++
        assertEquals(
            writesAwaited,
            withTimeoutOrNull(WAIT_BUDGET_MS) { preferencesWrites.first { it >= writesAwaited } },
            "第 $writesAwaited 次偏好写入没完成（界面当时是 ${shown(prefs.uiState.value)}）",
        )
        assertEquals(
            expected,
            withTimeoutOrNull(WAIT_BUDGET_MS) { stored.first { it == expected } },
            "偏好没写成 $expected（界面当时是 ${shown(prefs.uiState.value)}）",
        )
        assertEquals(
            expected,
            withTimeoutOrNull(WAIT_BUDGET_MS) { prefs.uiState.first { shown(it) == expected } }?.let(shown),
            "偏好已是 $expected，界面没跟上（界面当时是 ${shown(prefs.uiState.value)}）",
        )
    }

    /**
     * 九条偏好订阅都已读过存储才拨开关；首读计数只增不减，不等可能短暂追平的「已读 / 已启动」比值。
     * 初始默认 uiState 证明不了订阅就绪（写入途中开始的收集者可能停在旧值，见 [awaitStoredThenShown]）。
     */
    private suspend fun Harness.awaitCollectorsRead() {
        withTimeout(WAIT_BUDGET_MS) { collectorsFirstRead.first { it >= 9 } }
        withContext(Dispatchers.Main) { }
    }

    private fun ContentSettingsViewModel.awaitNsfw(predicate: (NsfwSwitchState) -> Boolean): NsfwSwitchState = runBlocking {
        withTimeout(WAIT_BUDGET_MS) {
            merge(
                uiState.map { it.nsfw },
                escapedErrors.map { errors ->
                    assertEquals(emptyList(), errors, "NSFW IO失败必须进入失败通道")
                    null
                }.filterNotNull(),
            ).first(predicate)
        }
    }

    /** 记录发起事件新增的生产协程，用它的完成等待整个回调结束，而不是等网络记录或中间状态。 */
    private suspend fun ViewModel.actionJob(action: () -> Unit): Job = withContext(Dispatchers.Main) {
        val owner = viewModelScope.coroutineContext[Job]!!
        val before = owner.children.toSet()
        action()
        owner.children.single { it !in before }
    }

    private suspend fun Job.awaitFinished() {
        withTimeout(WAIT_BUDGET_MS) { join() }
    }

    @Test
    fun `旧账号写结果不取消新账号读取也不恢复旧值`() = runBlocking {
        val patchGate = CompletableDeferred<Unit>()
        val patchEntered = CompletableDeferred<Unit>()
        val otherGetGate = CompletableDeferred<Unit>()
        val otherGetEntered = CompletableDeferred<Job>()
        val otherGetCancelled = CompletableDeferred<Unit>()
        val h = harness {
            when {
                path.endsWith("/v0/me") -> if (bearer == "Bearer tok2") me(2002, "other") else me()
                isPatch -> {
                    patchEntered.complete(Unit)
                    patchGate.await()
                    privacy(allowNsfw = false)
                }
                bearer == "Bearer tok2" -> {
                    requestJob.invokeOnCompletion { cause ->
                        if (cause is CancellationException) otherGetCancelled.complete(Unit)
                    }
                    otherGetEntered.complete(requestJob)
                    otherGetGate.await()
                    privacy(allowNsfw = false)
                }
                else -> privacy(allowNsfw = true)
            }
        }
        assertTrue(h.authRepository.loginWithToken("tok"))
        assertTrue(h.content.awaitNsfw { it is NsfwSwitchState.Editable }.checked)
        val oldWrite = h.content.actionJob { h.content.onNsfwChange(false) }
        withTimeout(WAIT_BUDGET_MS) { patchEntered.await() }
        val beforeSwitch = withContext(Dispatchers.Main) { h.content.viewModelScope.coroutineContext[Job]!!.children.toSet() }

        assertTrue(h.authRepository.loginWithToken("tok2"))
        val newGet = withTimeout(WAIT_BUDGET_MS) { otherGetEntered.await() }
        assertNull(h.nsfwRepository.cached(), "新账号没有缓存")
        val newLoad = withContext(Dispatchers.Main) {
            h.content.viewModelScope.coroutineContext[Job]!!.children.single { it !in beforeSwitch }
        }
        patchGate.complete(Unit)
        oldWrite.awaitFinished()
        withContext(Dispatchers.Main) { }
        assertIs<NsfwSwitchState.Unavailable>(
            h.content.uiState.value.nsfw,
            "旧写结束后新账号仍等待自己的读取，不得显示旧账号的 Editable（旧回调失败=${h.content.failures.current.value?.reasonOrNull()}）",
        )
        assertTrue(newLoad.isActive, "旧写不得取消新账号的生产读取协程")
        assertFalse(otherGetCancelled.isCompleted, "旧写不得取消新账号 GET")
        assertNull(h.content.failures.current.value, "旧账号的失效结果不通知新账号")

        otherGetGate.complete(Unit)
        newGet.awaitFinished()
        newLoad.awaitFinished()
        val final = assertIs<NsfwSwitchState.Editable>(h.content.uiState.value.nsfw)
        assertFalse(final.checked, "显示新账号服务端返回的关闭状态")
        assertFalse(final.pending)
        assertEquals(1, h.requests.count { it.method == HttpMethod.Patch && it.headers["Authorization"] == "Bearer tok" })
        assertEquals(1, h.requests.count { it.url.encodedPath == "/p1/privacy" && it.headers["Authorization"] == "Bearer tok2" })
    }

    @Test
    fun `缓存首读IO失败进入可重试失败态`() = runBlocking {
        val failRead = AtomicBoolean(true)
        val h = harness(faultNsfwRead = failRead::get) {
            if (path.endsWith("/v0/me")) me() else privacy(allowNsfw = true)
        }
        assertTrue(h.authRepository.loginWithToken("tok"))
        val failed = assertIs<NsfwSwitchState.Unavailable>(
            h.content.awaitNsfw { it is NsfwSwitchState.Unavailable && it.failureReason != null },
        )
        assertEquals(Failure.Interrupted, failed.failureReason)
        assertFalse(failed.loading)
        assertNull(h.content.failures.current.value, "没有内容的加载失败由失败态显示")

        failRead.set(false)
        h.content.actionJob { h.content.retryNsfw() }.awaitFinished()
        assertTrue(assertIs<NsfwSwitchState.Editable>(h.content.uiState.value.nsfw).checked)
    }

    @Test
    fun `写失败后缓存IO失败仍结束在途态并可重试`() = runBlocking {
        val failRead = AtomicBoolean(false)
        val h = harness(faultNsfwRead = failRead::get) {
            when {
                path.endsWith("/v0/me") -> me()
                isPatch -> {
                    failRead.set(true)
                    throw IOException("连接中断")
                }
                else -> privacy(allowNsfw = true)
            }
        }
        assertTrue(h.authRepository.loginWithToken("tok"))
        h.content.awaitNsfw { it is NsfwSwitchState.Editable }
        h.content.actionJob { h.content.onNsfwChange(false) }.awaitFinished()

        val state = assertIs<NsfwSwitchState.Editable>(h.content.uiState.value.nsfw)
        assertFalse(state.pending, "缓存读失败不能把开关永远留在在途态")
        assertTrue(state.checked, "保留同账号已确认的值")
        val notice = h.content.failures.awaitFailure()
        assertEquals(Failure.Interrupted, notice.reasonOrNull())
        failRead.set(false)
        h.content.actionJob { assertIs<() -> Unit>(notice.retry).invoke() }.awaitFinished()
        assertTrue(assertIs<NsfwSwitchState.Editable>(h.content.uiState.value.nsfw).checked)
        assertEquals(2, h.requests.count { it.url.encodedPath == "/p1/privacy" && it.method == HttpMethod.Get }, "重试实际重新读取服务端")
    }

    @Test
    fun `读取有内容时缓存IO失败保留旧值并提供重试`() = runBlocking {
        val failRead = AtomicBoolean(false)
        val h = harness(faultNsfwRead = failRead::get) {
            if (path.endsWith("/v0/me")) me() else privacy(allowNsfw = true)
        }
        assertTrue(h.authRepository.loginWithToken("tok"))
        h.content.awaitNsfw { it is NsfwSwitchState.Editable }
        failRead.set(true)
        h.content.actionJob { h.content.retryNsfw() }.awaitFinished()
        val notice = h.content.failures.awaitFailure()
        assertEquals(Failure.Interrupted, notice.reasonOrNull())
        assertTrue(assertIs<NsfwSwitchState.Editable>(h.content.uiState.value.nsfw).checked)
        val retry = assertIs<() -> Unit>(notice.retry)
        failRead.set(false)
        h.content.actionJob(retry).awaitFinished()
        assertTrue(assertIs<NsfwSwitchState.Editable>(h.content.uiState.value.nsfw).checked)
    }

    @Test
    fun `403权限缓存写IO失败不逃出作用域并可重试`() = runBlocking {
        val failWrite = AtomicBoolean(false)
        val h = harness(faultNsfwWrite = failWrite::get) {
            when {
                path.endsWith("/v0/me") -> me()
                isPatch -> {
                    failWrite.set(true)
                    privacyForbidden()
                }
                else -> privacy(allowNsfw = true)
            }
        }
        assertTrue(h.authRepository.loginWithToken("tok"))
        h.content.awaitNsfw { it is NsfwSwitchState.Editable }
        h.content.actionJob { h.content.onNsfwChange(false) }.awaitFinished()
        assertFalse(assertIs<NsfwSwitchState.Editable>(h.content.uiState.value.nsfw).pending)
        val notice = h.content.failures.awaitFailure()
        assertEquals(Failure.Interrupted, notice.reasonOrNull())
        failWrite.set(false)
        h.content.actionJob { assertIs<() -> Unit>(notice.retry).invoke() }.awaitFinished()
        assertTrue(assertIs<NsfwSwitchState.Editable>(h.content.uiState.value.nsfw).checked)
    }

    @Test
    fun `本地偏好流IO失败保留显示并通过提示重试订阅`() = runBlocking {
        val failRead = AtomicBoolean(false)
        val h = harness(faultPreferencesRead = failRead::get) { privacy(allowNsfw = true) }
        h.awaitCollectorsRead()
        h.prefs.onThemeModeChange(ThemeMode.DARK)
        h.awaitStoredThenShown(ThemeMode.DARK, h.preferencesRepository.themeMode) { it.themeMode }
        val shown = h.prefs.uiState.value
        failRead.set(true)
        h.preferencesRepository.setThemeMode(ThemeMode.LIGHT)
        val notice = h.prefs.failures.awaitFailure()
        assertEquals(Failure.Interrupted, notice.reasonOrNull())
        assertEquals(shown, h.prefs.uiState.value, "读取失败保留已显示的全部偏好")
        val theme = ThemePreferences(mode = ThemeColorMode.SYSTEM)
        h.preferencesRepository.setThemePreferences(theme)
        h.preferencesRepository.setExperienceMode(ExperienceMode.FOCUS)
        h.preferencesRepository.setShowPublicRatings(false)
        h.preferencesRepository.setShowCoverScores(false)
        h.preferencesRepository.setShowFriendMarks(false)
        h.preferencesRepository.setToWatchLimit(12)
        h.preferencesRepository.setProgressDefaultType(SubjectType.BOOK)
        h.preferencesRepository.setReadingTextSize(ReadingTextSize.LARGE)
        h.preferencesRepository.setCoverQuality(CoverQuality.ORIGINAL)
        h.preferencesRepository.setRefreshRate(120)

        failRead.set(false)
        withContext(Dispatchers.Main) { assertIs<() -> Unit>(notice.retry).invoke() }
        val expected = shown.copy(
            themeMode = ThemeMode.LIGHT,
            themePreferences = theme,
            experienceMode = ExperienceMode.FOCUS,
            showPublicRatings = false,
            showCoverScores = false,
            showFriendMarks = false,
            toWatchLimit = 12,
            progressDefaultType = SubjectType.BOOK,
            readingTextSize = ReadingTextSize.LARGE,
            coverQuality = CoverQuality.ORIGINAL,
            refreshRate = 120,
        )
        withTimeout(WAIT_BUDGET_MS) { h.prefs.uiState.first { it == expected } }
        assertEquals(expected, h.prefs.uiState.value, "一条重试重新订阅全部偏好")
    }

    // ---- 体验模式（完整 / 专注） ----

    @Test
    fun `体验模式切换只写偏好_界面状态只从偏好流来`() = runBlocking {
        // 红线一的确定性回归：**同一状态不许有两个来源**。
        // 按住偏好 DataStore 的首次读取再拨开关：
        // - 若 onExperienceModeChange 里 optimistic 写 uiState：状态**立即**变成 FOCUS → 本断言失败，
        //   且真实场景里稍后到来的旧值会把开关盖回去（界面闪一下）；
        // - 只写偏好、状态只从 collect 来：写入尚未被观察到，状态必须仍是 FULL。
        // 全程零 delay、零等待窗口：断言的就是"此刻状态由谁写"。
        val gate = CompletableDeferred<Unit>()
        val h = harness(gatePreferencesRead = gate) { privacy(allowNsfw = true) }

        val before = withTimeout(WAIT_BUDGET_MS) { h.prefs.uiState.first() }
        assertEquals(ExperienceMode.FULL, before.experienceMode)

        h.prefs.onExperienceModeChange(ExperienceMode.FOCUS)

        // 关键断言：偏好流还没发出任何值，界面就不得自己先动
        assertEquals(
            ExperienceMode.FULL,
            h.prefs.uiState.value.experienceMode,
            "onExperienceModeChange 不得自行写 uiState（单一真源：状态只从偏好流来）",
        )

        // 等写入完成落盘：确保首读放行时磁盘上确已是 FOCUS
        withTimeout(WAIT_BUDGET_MS) { h.preferencesWrites.first { it >= 1 } }
        // 写入协程已经跑过：写在协程里的乐观值此刻也该露出来了，状态仍得是偏好流给的那个
        assertEquals(ExperienceMode.FULL, h.prefs.uiState.value.experienceMode, "写偏好的协程里也不得自行写 uiState")

        // 放行首读：此时磁盘上已是 FOCUS（前面那次 edit 已落盘），流回放后界面才切过去
        gate.complete(Unit)
        // 两处最终态一致：界面显示的就是偏好里存的
        h.awaitStoredThenShown(ExperienceMode.FOCUS, h.preferencesRepository.experienceMode) { it.experienceMode }
    }

    @Test
    fun `体验模式切换后偏好与界面都到达最终态`() = runBlocking {
        val h = harness { privacy(allowNsfw = true) }

        h.awaitCollectorsRead()

        h.prefs.onExperienceModeChange(ExperienceMode.FOCUS)

        // 两边都等**最终态**（不是中间态）：偏好里确实存着 FOCUS，界面也显示 FOCUS。
        h.awaitStoredThenShown(ExperienceMode.FOCUS, h.preferencesRepository.experienceMode) { it.experienceMode }

        // 切回完整同样到达最终态
        h.prefs.onExperienceModeChange(ExperienceMode.FULL)
        h.awaitStoredThenShown(ExperienceMode.FULL, h.preferencesRepository.experienceMode) { it.experienceMode }
    }

    @Test
    fun `主题模式切换只写偏好_界面状态只从偏好流来`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val h = harness(gatePreferencesRead = gate) { privacy(allowNsfw = true) }

        val before = withTimeout(WAIT_BUDGET_MS) { h.prefs.uiState.first() }
        assertEquals(ThemeMode.FOLLOW_SYSTEM, before.themeMode)

        h.prefs.onThemeModeChange(ThemeMode.DARK)

        // 关键断言：偏好流还没发出任何值，界面就不得自己先动（单一真源）
        assertEquals(
            ThemeMode.FOLLOW_SYSTEM,
            h.prefs.uiState.value.themeMode,
            "onThemeModeChange 不得自行写 uiState（单一真源：状态只从偏好流来）",
        )

        withTimeout(WAIT_BUDGET_MS) { h.preferencesWrites.first { it >= 1 } }
        assertEquals(ThemeMode.FOLLOW_SYSTEM, h.prefs.uiState.value.themeMode, "写偏好的协程里也不得自行写 uiState")

        gate.complete(Unit)
        h.awaitStoredThenShown(ThemeMode.DARK, h.preferencesRepository.themeMode) { it.themeMode }
    }

    @Test
    fun `公共评分切换只写偏好_界面状态只从偏好流来`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val h = harness(gatePreferencesRead = gate) { privacy(allowNsfw = true) }

        val before = withTimeout(WAIT_BUDGET_MS) { h.prefs.uiState.first() }
        assertTrue(before.showPublicRatings)

        h.prefs.onShowPublicRatingsChange(false)

        assertEquals(
            true,
            h.prefs.uiState.value.showPublicRatings,
            "onShowPublicRatingsChange 不得自行写 uiState（单一真源：状态只从偏好流来）",
        )

        withTimeout(WAIT_BUDGET_MS) { h.preferencesWrites.first { it >= 1 } }
        assertEquals(true, h.prefs.uiState.value.showPublicRatings, "写偏好的协程里也不得自行写 uiState")

        gate.complete(Unit)
        h.awaitStoredThenShown(false, h.preferencesRepository.showPublicRatings) { it.showPublicRatings }
    }

    @Test
    fun `封面评分切换后偏好与界面都到达最终态`() = runBlocking {
        val h = harness { privacy(allowNsfw = true) }

        h.awaitCollectorsRead()

        h.prefs.onShowCoverScoresChange(false)

        h.awaitStoredThenShown(false, h.preferencesRepository.showCoverScores) { it.showCoverScores }
    }

    @Test
    fun `好友标识切换后偏好与界面都到达最终态`() = runBlocking {
        val h = harness { privacy(allowNsfw = true) }

        h.awaitCollectorsRead()

        h.prefs.onShowFriendMarksChange(false)

        h.awaitStoredThenShown(false, h.preferencesRepository.showFriendMarks) { it.showFriendMarks }
    }

    @Test
    fun `公共评分切换后偏好与界面都到达最终态`() = runBlocking {
        val h = harness { privacy(allowNsfw = true) }

        h.awaitCollectorsRead()

        h.prefs.onShowPublicRatingsChange(false)

        h.awaitStoredThenShown(false, h.preferencesRepository.showPublicRatings) { it.showPublicRatings }

        h.prefs.onShowPublicRatingsChange(true)
        h.awaitStoredThenShown(true, h.preferencesRepository.showPublicRatings) { it.showPublicRatings }
    }

    @Test
    fun `设置页画的是系统里开着的那个入口`() = runBlocking {
        val h = harness(initialIcon = AppIcon.TV_NAVY) { privacy(allowNsfw = true) }

        assertEquals(
            AppIcon.TV_NAVY,
            withTimeoutOrNull(WAIT_BUDGET_MS) { h.prefs.uiState.first { it.appIcon == AppIcon.TV_NAVY } }?.appIcon,
            "进页要读系统里开着的入口，不能停在默认图标",
        )
    }

    @Test
    fun `换图标先切换入口再按系统读回的画`() = runBlocking {
        val h = harness { privacy(allowNsfw = true) }

        h.appIcons.enabledAfterSelect = { AppIcon.TV_NAVY }

        h.prefs.onAppIconChange(AppIcon.TV_ON_PINK)

        assertEquals(
            AppIcon.TV_NAVY,
            withTimeoutOrNull(WAIT_BUDGET_MS) { h.prefs.uiState.first { it.appIcon == AppIcon.TV_NAVY } }?.appIcon,
            "界面画系统读回的入口，不画自己请求的那个",
        )
        assertEquals(listOf(AppIcon.TV_ON_PINK), h.appIcons.selected.toList())
    }

    @Test
    fun `入口切换失败时提示失败，界面留在系统里开着的那个`() = runBlocking {
        val h = harness(initialIcon = AppIcon.TV_PINK, failSelect = true) { privacy(allowNsfw = true) }
        withTimeout(WAIT_BUDGET_MS) { h.prefs.uiState.first { it.appIcon == AppIcon.TV_PINK } }

        h.prefs.onAppIconChange(AppIcon.SAKURA_RAINBOW)

        assertEquals(
            Failure.Storage,
            withTimeoutOrNull(WAIT_BUDGET_MS) { h.prefs.failures.current.filterNotNull().first() }?.reasonOrNull(),
        )
        assertEquals(AppIcon.TV_PINK, h.prefs.uiState.value.appIcon)
    }

    @Test
    fun `进页画的是量出来的图片缓存占用`() = runBlocking {
        val h = harness(cacheBytes = 4_096L) { privacy(allowNsfw = true) }

        assertEquals(
            4_096L,
            withTimeoutOrNull(WAIT_BUDGET_MS) { h.home.uiState.first { it.imageCacheBytes != null } }?.imageCacheBytes,
            "进页要量一次缓存占用，不能停在没量到",
        )
    }

    @Test
    fun `清理图片缓存后画的是重新量出来的占用`() = runBlocking {
        val h = harness(cacheBytes = 4_096L) { privacy(allowNsfw = true) }
        withTimeout(WAIT_BUDGET_MS) { h.home.uiState.first { it.imageCacheBytes == 4_096L } }

        h.home.clearImageCache()

        assertEquals(
            IN_USE_BYTES,
            withTimeoutOrNull(WAIT_BUDGET_MS) { h.home.uiState.first { it.imageCacheBytes == IN_USE_BYTES } }?.imageCacheBytes,
            "清理后要重新量，不自己写零，也不留在清理前的数",
        )
        assertEquals(1, h.imageCache.clears.get())
    }

    // ---- 状态一：未登录 ----

    @Test
    fun `未登录时开关禁用并显示登录提示`() {
        val h = harness { privacy(allowNsfw = true) }

        val state = h.content.awaitNsfw { it is NsfwSwitchState.LoggedOut }

        assertIs<NsfwSwitchState.LoggedOut>(state)
        assertFalse(state.checked, "未登录不得显示成开启")
    }

    // ---- 状态二：已登录但缓存为空 ----

    @Test
    fun `已登录但请求失败时显示失败原因与可重试`() {
        val h = harness {
            when {
                path.endsWith("/v0/me") -> me()
                else -> throw java.io.IOException("boom")
            }
        }
        runBlocking { h.authRepository.loginWithToken("tok") }

        val state = h.content.awaitNsfw { it is NsfwSwitchState.Unavailable && it.failureReason != null }

        val unavailable = assertIs<NsfwSwitchState.Unavailable>(state)
        assertFalse(unavailable.loading)
        assertEquals(Failure.Interrupted, unavailable.failureReason, "失败要给出原因供界面显示")
        assertNull(runBlocking { h.nsfwRepository.cached() }, "首次就失败，缓存为空")
        assertFalse(state.checked)
    }

    @Test
    fun `重试后成功则切到可改态`() {
        var fail = true
        val h = harness {
            when {
                path.endsWith("/v0/me") -> me()
                fail -> throw java.io.IOException("boom")
                else -> privacy(allowNsfw = true)
            }
        }
        runBlocking { h.authRepository.loginWithToken("tok") }
        h.content.awaitNsfw { it is NsfwSwitchState.Unavailable && it.failureReason != null }

        fail = false
        h.content.retryNsfw()

        val state = h.content.awaitNsfw { it is NsfwSwitchState.Editable }
        assertIs<NsfwSwitchState.Editable>(state)
        assertTrue(state.checked)
    }

    // ---- 状态三：已登录但无权修改 ----

    @Test
    fun `canSetNsfwSubject为false时禁用并显示60天提示`() {
        val h = harness {
            when {
                path.endsWith("/v0/me") -> me()
                else -> privacy(allowNsfw = false, canSet = false)
            }
        }
        runBlocking { h.authRepository.loginWithToken("tok") }
        val state = h.content.awaitNsfw { it is NsfwSwitchState.Locked }

        val locked = assertIs<NsfwSwitchState.Locked>(state)
        assertFalse(locked.preference.canSetNsfwSubject)
        assertFalse(locked.checked)
        // 拨动不该有任何效果（界面禁用，事件层也拦住）
        h.content.onNsfwChange(true)
        assertIs<NsfwSwitchState.Locked>(h.content.uiState.value.nsfw)
    }

    // ---- 状态四：已登录可改 ----

    @Test
    fun `已登录可改时开关可用且切换走PATCH_服务端PATCH恢复正常时的契约`() {
        val h = harness {
            when {
                path.endsWith("/v0/me") -> me()
                isPatch -> privacy(allowNsfw = true, showNsfwSubject = true)
                else -> privacy(allowNsfw = false, showNsfwSubject = false)
            }
        }
        runBlocking { h.authRepository.loginWithToken("tok") }
        val initial = assertIs<NsfwSwitchState.Editable>(h.content.awaitNsfw { it is NsfwSwitchState.Editable })
        assertFalse(initial.checked)

        h.content.onNsfwChange(true)

        val state = h.content.awaitNsfw { it is NsfwSwitchState.Editable && it.checked && !it.pending }
        assertTrue(state.checked, "PATCH 成功后开关打开")
    }

    @Test
    fun `PATCH失败时开关回滚到旧值并提示`() {
        val h = harness {
            when {
                path.endsWith("/v0/me") -> me()
                isPatch -> throw java.io.IOException("boom")
                else -> privacy(allowNsfw = false, showNsfwSubject = false)
            }
        }
        runBlocking { h.authRepository.loginWithToken("tok") }
        h.content.awaitNsfw { it is NsfwSwitchState.Editable }

        h.content.onNsfwChange(true)

        // 先回滚、再提示：提示到达时状态里的回滚必须已经到位
        h.content.failures.awaitFailure()
        val state = h.content.uiState.value
        val nsfw = assertIs<NsfwSwitchState.Editable>(state.nsfw)
        assertFalse(nsfw.checked, "服务端未接受，开关不得停在用户拨到的位置")
        assertFalse(nsfw.pending)
    }

    @Test
    fun `PATCH返回500时错误可见_开关回滚且仍可改`() {
        // 线上实测：PATCH 端点对任何请求体都返回 500。此时设置页必须
        // ①把错误暴露给界面（错误没人消费的话，点了没反应）；②开关回滚原值；
        // ③保持 Editable —— 服务端坏了不等于账号无权，锁死会把故障变成永久降级。
        val h = harness {
            when {
                path.endsWith("/v0/me") -> me()
                isPatch -> privacyServerError()
                else -> privacy(allowNsfw = true, showNsfwSubject = true, canSet = true)
            }
        }
        runBlocking { h.authRepository.loginWithToken("tok") }
        h.content.awaitNsfw { it is NsfwSwitchState.Editable }

        h.content.onNsfwChange(false)

        assertEquals(
            Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED),
            h.content.failures.awaitFailure().reasonOrNull(),
            "500 且没有网页会话时要给可操作的原因（账号密码登录），不能是通用的服务器错误",
        )
        val state = h.content.uiState.value
        val nsfw = assertIs<NsfwSwitchState.Editable>(state.nsfw)
        assertTrue(nsfw.checked, "服务端未接受，开关必须回滚到旧值（当前是开）")
        assertFalse(nsfw.pending)
        assertTrue(nsfw.preference.canSetNsfwSubject, "5xx 不得把账号记成不可改")
    }

    @Test
    fun `提示显示完被确认后清空_开关状态不受影响`() {
        val h = harness {
            when {
                path.endsWith("/v0/me") -> me()
                isPatch -> privacyServerError()
                else -> privacy(allowNsfw = true, showNsfwSubject = true)
            }
        }
        runBlocking { h.authRepository.loginWithToken("tok") }
        h.content.awaitNsfw { it is NsfwSwitchState.Editable }
        h.content.onNsfwChange(false)
        val notice = h.content.failures.awaitFailure()

        h.content.failures.consume(notice)

        assertNull(h.content.failures.current.value)
        val cleared = h.content.uiState.value
        // 关键：提示清空不得把开关状态一起清掉
        assertIs<NsfwSwitchState.Editable>(cleared.nsfw)
        assertTrue(cleared.nsfw.checked)
    }

    @Test
    fun `403视为无权修改_状态切到Locked并带错误提示`() {        val h = harness {
            when {
                path.endsWith("/v0/me") -> me()
                isPatch -> privacyForbidden()
                else -> privacy(allowNsfw = false, showNsfwSubject = false)
            }
        }
        runBlocking { h.authRepository.loginWithToken("tok") }
        // 初始可改（服务端此刻报 canSetNsfwSubject=true）
        h.content.awaitNsfw { it is NsfwSwitchState.Editable }

        h.content.onNsfwChange(true)

        assertEquals(Failure.Rejected(RejectReason.NSFW_ACCOUNT_TOO_NEW), h.content.failures.awaitFailure().reasonOrNull())
        val state = h.content.uiState.value
        // 关键断言：必须切到 **Locked**（禁用态），而不是停在 Editable。
        // 服务端已明确拒绝，界面若仍是"可改"，用户会反复拨动、每次都被拒。
        val locked = assertIs<NsfwSwitchState.Locked>(state.nsfw)
        assertFalse(locked.preference.canSetNsfwSubject, "403 后仓库已把可改性记为 false")
        assertFalse(locked.checked)
    }

    @Test
    fun `切换在途时重试读取不盖掉在途态_也不能再发一次PATCH`() {
        val patchGate = CompletableDeferred<Unit>()
        val patches = AtomicInteger()
        val h = harness {
            when {
                path.endsWith("/v0/me") -> me()
                isPatch -> {
                    patches.incrementAndGet()
                    patchGate.await()
                    privacy(allowNsfw = true, showNsfwSubject = true)
                }
                else -> privacy(allowNsfw = false, showNsfwSubject = false)
            }
        }
        runBlocking { h.authRepository.loginWithToken("tok") }
        h.content.awaitNsfw { it is NsfwSwitchState.Editable }
        h.content.onNsfwChange(true)
        h.content.awaitNsfw { it is NsfwSwitchState.Editable && it.pending }

        h.content.retryNsfw()
        assertEquals(
            true,
            (h.content.uiState.value.nsfw as? NsfwSwitchState.Editable)?.pending,
            "切换在途：读取不得把开关换成别的状态",
        )
        h.content.onNsfwChange(false)

        patchGate.complete(Unit)
        val state = h.content.awaitNsfw { it is NsfwSwitchState.Editable && it.checked && !it.pending }
        assertTrue(state.checked, "切换的结果落地")
        assertEquals(1, patches.get(), "在途期间不再发第二个 PATCH")
    }

    @Test
    fun `拨开关时还在路上的读取被作废_PATCH成功后不会把开关盖回旧值`() {
        val staleGate = CompletableDeferred<Unit>()
        val staleCancelled = CompletableDeferred<Unit>()
        val staleEntered = CompletableDeferred<Unit>()
        val gets = AtomicInteger()
        val h = harness {
            when {
                path.endsWith("/v0/me") -> me()
                isPatch -> privacy(allowNsfw = true, showNsfwSubject = true)
                gets.incrementAndGet() == 2 -> {
                    staleEntered.complete(Unit)
                    try {
                        staleGate.await()
                    } catch (e: CancellationException) {
                        staleCancelled.complete(Unit)
                        throw e
                    }
                    privacy(allowNsfw = false, showNsfwSubject = false)
                }
                else -> privacy(allowNsfw = false, showNsfwSubject = false)
            }
        }
        runBlocking { h.authRepository.loginWithToken("tok") }
        h.content.awaitNsfw { it is NsfwSwitchState.Editable }
        h.content.retryNsfw()
        runBlocking { withTimeout(WAIT_BUDGET_MS) { staleEntered.await() } }

        h.content.onNsfwChange(true)
        val state = h.content.awaitNsfw { it is NsfwSwitchState.Editable && it.checked && !it.pending }

        assertTrue(state.checked)
        runBlocking { withTimeout(WAIT_BUDGET_MS) { staleCancelled.await() } }
        assertTrue((h.content.uiState.value.nsfw as NsfwSwitchState.Editable).checked, "旧读取不再落地")
    }

    @Test
    fun `换到另一个账号时重新读取开关`() {
        var otherAccount = false
        val h = harness {
            when {
                path.endsWith("/v0/me") -> if (otherAccount) me(id = 2002, username = "other") else me()
                else -> if (otherAccount) privacy(allowNsfw = true) else privacy(allowNsfw = false)
            }
        }
        runBlocking { h.authRepository.loginWithToken("tok") }
        assertFalse(h.content.awaitNsfw { it is NsfwSwitchState.Editable }.checked)

        otherAccount = true
        runBlocking { h.authRepository.loginWithToken("tok2") }

        assertTrue(h.content.awaitNsfw { it is NsfwSwitchState.Editable && it.checked }.checked, "新账号的开关是开的")
    }

    /** 等到 ViewModel 发出失败提示（提示一旦发出，测试里不会被清掉：单调）。 */
    private fun FailureNotices.awaitFailure(): FailureNotice = runBlocking {
        withTimeout(WAIT_BUDGET_MS) {
            merge(
                current.filterNotNull(),
                escapedErrors.map { errors ->
                    assertEquals(emptyList(), errors, "偏好IO失败必须进入失败通道")
                    null
                }.filterNotNull(),
            ).first()
        }
    }

    private fun FailureNotice.reasonOrNull(): Failure? = (text as? NoticeText.Reason)?.failure
}

/** 清掉图片缓存后磁盘上仍在用的字节数。 */
private const val IN_USE_BYTES = 512L
