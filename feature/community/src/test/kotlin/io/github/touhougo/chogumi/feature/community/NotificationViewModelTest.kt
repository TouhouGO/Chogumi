package io.github.touhougo.chogumi.feature.community

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.data.repository.ConcurrentRequestRecorder
import io.github.touhougo.chogumi.core.data.repository.NotificationRepository
import io.github.touhougo.chogumi.core.data.repository.PmRepository
import io.github.touhougo.chogumi.core.data.repository.testAccountMe
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.repository.testWebClient
import io.github.touhougo.chogumi.core.data.repository.testWebSessionRepository
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.foundation.orEmpty
import io.github.touhougo.chogumi.core.model.ExperienceMode
import io.github.touhougo.chogumi.core.model.NoticeBo
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.github.touhougo.chogumi.core.ui.common.navigation.InboxTab
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule

/** 铃铛面板：打开时停在哪一页，短信页的列表、全部已读、会话读成功才去掉未读，没有网页会话时的说明，铃铛的数是两种未读之和。 */
class NotificationViewModelTest {
    @get:Rule
    val resources = TestResources()

    private val main = TestMain()
    private lateinit var vm: NotificationViewModel
    private lateinit var pm: PmRepository

    @Volatile private var unreadNotices = 0
    @Volatile private var unreadMessages = 0

    /** 会话页这一次读取的回应：null 时照常给出会话（网页就此算作读过），否则按这个状态码失败。 */
    @Volatile private var conversationFailure: HttpStatusCode? = null
    private val web = ConcurrentRequestRecorder()

    private val tokenStore = testTokenStore(resources, userId = ME)
    private val webSessions = testWebSessionRepository(
        tokenStore,
        resources.own(
            testWebClient { request ->
                web.record(request)
                when (request.url.encodedPath) {
                    "/json/notify" -> respond(
                        """{"count":0,"pm_count":$unreadMessages,"pm_ignore_url":"\/pm\/ignore_unread.chii?gh=FORMHASH","pm_list":[]}""",
                        HttpStatusCode.OK,
                    )
                    "/pm/inbox.chii" -> respond(signedIn(if (request.url.parameters["page"] == "1") INBOX else EMPTY_INBOX), HttpStatusCode.OK)
                    "/pm/conversation/300003.chii" -> conversationFailure?.let { respond("", it) } ?: run {
                        unreadMessages = 0
                        respond(signedIn(CONVERSATION), HttpStatusCode.OK)
                    }
                    "/settings/privacy" -> respond(signedIn("""<a href="/logout/0a1b2c3d">登出</a>"""), HttpStatusCode.OK)
                    "/pm/ignore_unread.chii" -> {
                        unreadMessages = 0
                        respond("""{"status":"ok"}""", HttpStatusCode.OK)
                    }
                    else -> error("意外请求：${request.url}")
                }
            },
        ),
    )
    private val preferences = PreferencesRepository(
        PreferenceDataStoreFactory.create(scope = resources.scope) { File(resources.directory("inbox-prefs"), "prefs.preferences_pb") },
    )
    /** 通知列表的读取（不是只取未读数的那种）发出了几次。 */
    private val listReads = AtomicInteger()

    /** 设上时，下一次列表读取在这里停住：读到的是停住那一刻的未读状态（标为已读之前发出、晚到的读取）。 */
    @Volatile private var heldListRead: HeldRead? = null

    private class HeldRead(val sent: CompletableDeferred<Unit> = CompletableDeferred(), val release: CompletableDeferred<Unit> = CompletableDeferred())

    private val notifications = NotificationRepository(
        NextApi(
            resources.apiClient { request ->
                val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                if (request.url.encodedPath == "/p1/clear-notify") {
                    unreadNotices = 0
                    return@apiClient respond("{}", headers = json)
                }
                val unread = unreadNotices
                val isList = request.url.parameters["unread"] == null
                if (isList) {
                    listReads.incrementAndGet()
                    heldListRead?.let { held ->
                        heldListRead = null
                        held.sent.complete(Unit)
                        held.release.await()
                    }
                }
                respond("""{"data":[${if (isList) notice(unread > 0) else ""}],"total":$unread}""", headers = json)
            },
        ),
        tokenStore,
        webSessions,
        preferences,
        resources.scope,
    )

    @BeforeTest
    fun setUp() = runBlocking {
        main.install()
        preferences.setExperienceMode(ExperienceMode.FULL)
    }

    @AfterTest
    fun tearDown() = main.uninstall(listOf(vm.viewModelScope))

    private suspend fun signInOnWeb() = webSessions.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))

    private suspend fun openPanel(notices: Int, messages: Int) {
        unreadNotices = notices
        unreadMessages = messages
        pm = PmRepository(tokenStore, webSessions, notifications, resources.scope, testAccountMe(resources, tokenStore), Dispatchers.Unconfined)
        vm = withContext(main.dispatcher) { NotificationViewModel(notifications, pm) }
        notifications.refreshUnreadCount()
        awaitUntil("两种未读都已读到") { notifications.unreadCount.value == notices && notifications.unreadMessages.value == messages }
        withContext(main.dispatcher) { vm.open() }
        awaitUntil("会话列表首屏落定") { !vm.uiState.value.conversations.isLoading }
    }

    @Test
    fun `只有未读短信时打开面板停在短信页`() = runBlocking {
        signInOnWeb()
        openPanel(notices = 0, messages = 2)

        assertEquals(InboxTab.MESSAGES, vm.uiState.value.tab)
        assertEquals(listOf(300003 to 2), vm.uiState.value.conversations.items.map { it.id to it.unread })
    }

    @Test
    fun `有未读通知时打开面板停在通知页_铃铛的数是两种未读之和`() = runBlocking {
        signInOnWeb()
        openPanel(notices = 3, messages = 2)

        assertEquals(InboxTab.NOTICES, vm.uiState.value.tab)
        awaitUntil("铃铛的数合上两种未读") { vm.unreadTotal.value == 5 }
    }

    @Test
    fun `会话读成功后_这一行的未读数与铃铛都去掉`() = runBlocking {
        signInOnWeb()
        openPanel(notices = 0, messages = 2)

        assertTrue(pm.conversation(300003) is Outcome.Success)

        awaitUntil("读成功后这一行的未读数去掉") { vm.uiState.value.conversations.items.single().unread == 0 }
        awaitUntil("铃铛按服务端重读的未读短信数归零") { vm.unreadMessages.value == 0 }
    }

    @Test
    fun `会话读失败_这一行仍是未读`() = runBlocking {
        signInOnWeb()
        openPanel(notices = 0, messages = 2)
        conversationFailure = HttpStatusCode.InternalServerError

        assertTrue(pm.conversation(300003) is Outcome.Failed)

        assertTrue(web.any { it.url.encodedPath == "/pm/conversation/300003.chii" }, "会话页的读取发出去了")
        assertEquals(2, vm.uiState.value.conversations.items.single().unread)
        assertEquals(2, vm.unreadMessages.value)
    }

    @Test
    fun `短信全部已读_发出网页的全部已读_各行未读数去掉`() = runBlocking {
        signInOnWeb()
        openPanel(notices = 0, messages = 2)

        withContext(main.dispatcher) { vm.markAllMessagesRead() }
        awaitUntil("各行的未读数已去掉") { vm.uiState.value.conversations.items.single().unread == 0 }

        assertEquals("gh=FORMHASH&ajax=1", web.single { it.url.encodedPath == "/pm/ignore_unread.chii" }.url.encodedQuery)
        assertEquals(0, notifications.unreadMessages.value)
    }

    @Test
    fun `没有网页会话时短信页是要账号密码登录_不开网页`() = runBlocking {
        openPanel(notices = 0, messages = 0)

        assertEquals(Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED), vm.uiState.value.conversations.error)
        assertEquals(emptyList(), web.map { it.url.encodedPath })
    }

    @Test
    fun `读通知途中全部已读_晚到的旧列表不把未读圆点画回来`() = runBlocking {
        markWhileListInFlight { vm.markAllAsRead() }
    }

    @Test
    fun `读通知途中点开一条_晚到的旧列表不把这一条画回未读`() = runBlocking {
        markWhileListInFlight { vm.markRead(shownNotices().single()) }
    }

    /**
     * 列表重读在途（读到的还是未读）时标为已读：做成后重读一次，新一轮作废旧的那次，晚到的旧列表落不进来，
     * 显示的是标记之后读到的列表。
     */
    private suspend fun markWhileListInFlight(mark: () -> Unit) {
        openPanel(notices = 1, messages = 0)
        awaitUntil("通知列表到位") { shownNotices().singleOrNull()?.unread == true }
        val held = HeldRead().also { heldListRead = it }
        withContext(main.dispatcher) { vm.loadNotifications() }
        withTimeout(WAIT_BUDGET_MS) { held.sent.await() }

        withContext(main.dispatcher) { mark() }
        awaitUntil("标记做成后重读了列表") { listReads.get() >= 3 }
        held.release.complete(Unit)

        awaitUntil("显示标记之后读到的列表，旧列表没有把未读圆点画回来") { shownNotices().singleOrNull()?.unread == false }
    }

    private fun shownNotices(): List<NoticeBo> = vm.uiState.value.notices.orEmpty()

    private fun signedIn(page: String) = "<script>var CHOBITS_UID = $ME;</script>\n$page"

    private companion object {
        const val ME = 400200

        /** 一条通知（`/p1/notify` 的一行，字段照 p1 契约）。 */
        fun notice(unread: Boolean) = """{"id":101,"type":1,"sender":{"id":42,"username":"alice","nickname":"Alice",
            "avatar":{"large":"","medium":"","small":""},"group":10,"sign":"","joinedAt":0,"isFriend":false},
            "title":"t","mainID":1001,"relatedID":2002,"createdAt":1789691721,"unread":$unread}"""

        /** 会话列表的一页：一个有两条未读的会话（样本 `peer-inbox-unread.html` 的结构）。 */
        val INBOX = """
            <div id="contentPM"><div class="pm-conversation-list">
            <a href="/pm/conversation/300003.chii?page=1" class="pm-conversation-item ">
            <span class="avatarNeue avatarSize32 ll" style="background-image:url('//lain.bgm.tv/pic/user/l/000/40/02/400200.jpg')"></span>
            <span class="pm-conversation-name">示例好友</span><small class="pm-conversation-date">2026-10-26</small>
            <span class="pm-conversation-desc">Chogumi 测试回复：普通回复一次。</span><em class="pm-conversation-unread">2</em>
            </a></div></div>
        """.trimIndent()

        /** 会话 300003 的会话页（结构同 `PmRepositoryTest` 的合成会话页）。 */
        val CONVERSATION = """
            <div id="contentPM"><div class="pm-chat-panel">
            <div class="pm-chat-title"><strong><a href="/user/sample">示例好友</a></strong></div>
            <form id="pmReplyForm"><input name="msg_receivers" value="400100"><input name="related" value="1"></form>
            <div class="pm-thread-filter"><a href="?thread=1">主题</a></div>
            <div class="pm-message-list"><div class="pm-thread-label">主题</div>
            <div class="pm-message" id="msg_1"><div class="pm-message-body">你好</div><div class="pm-message-info">2026-10-26 12:09</div></div>
            </div></div></div>
        """.trimIndent()

        val EMPTY_INBOX = """<div id="contentPM"><div class="pm-conversation-list"></div></div>"""
    }
}
