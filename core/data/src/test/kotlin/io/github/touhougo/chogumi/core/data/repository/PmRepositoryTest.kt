package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.model.ExperienceMode
import io.github.touhougo.chogumi.core.model.PmDraft
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.network.PmSamples
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Rule

/** 站内短信：读网页、照网页的表单发信、删信后重读确认，未读短信随通知数刷新。网页由 [PmWebSite] 模拟。 */
class PmRepositoryTest {
    @get:Rule
    val resources = TestResources()

    private val site = PmWebSite(userId = ME)
    private val tokenStore = testTokenStore(resources, userId = ME)
    private var offset: Int? = 8
    private var malformedOffset = false
    private var meStatus = HttpStatusCode.OK
    private var onMe: suspend () -> Unit = {}
    private val meCalls = AtomicInteger()
    private val userApi = UserApi(resources.apiClient {
        meCalls.incrementAndGet()
        onMe()
        val offsetField = when {
            malformedOffset -> ""","time_offset":"invalid""""
            offset == null -> ""
            else -> ""","time_offset":$offset"""
        }
        respond(
            """{"id":$ME,"username":"test","nickname":"test","user_group":10,"avatar":{"large":"","medium":"","small":""},"sign":""$offsetField}""",
            status = meStatus,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    })
    private val webSessions = testWebSessionRepository(tokenStore, resources.own(site.client))
    private val preferences = PreferencesRepository(
        PreferenceDataStoreFactory.create(scope = resources.scope) { File(resources.directory("pm-prefs"), "prefs.preferences_pb") },
    )
    private val notifications = NotificationRepository(
        NextApi(resources.apiClient { respond("""{"data":[],"total":0}""", headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())) }),
        tokenStore,
        webSessions,
        preferences,
        resources.scope,
    )
    private val repository = PmRepository(tokenStore, webSessions, notifications, resources.scope, AccountMe(userApi, tokenStore), Dispatchers.Unconfined)

    private suspend fun signInOnWeb() {
        webSessions.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))
        preferences.setExperienceMode(ExperienceMode.FULL)
    }

    private fun paths() = site.requests.map { it.url.encodedPath }

    /** 读到会话后未读数在后台重读（`/json/notify`），先后不定：只看网页页面的请求（对方主页的跳转另有测试）。 */
    private fun pagePaths() = paths().filter { it != "/json/notify" && !it.startsWith("/user/") }

    private fun repository(computationDispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        PmRepository(tokenStore, webSessions, notifications, resources.scope, AccountMe(userApi, tokenStore), computationDispatcher)

    @Test
    fun `收件箱网页回包后_计算调度器放行前不交付解析与模型`() = runBlocking {
        signInOnWeb()
        site.inbox[1] = syntheticInbox(10)
        val computation = GatedDispatcher()
        val main = TestMain()
        main.install()
        val reading = async {
            kotlinx.coroutines.withContext(main.dispatcher) {
                repository(computation).conversations(0)
            }
        }
        try {
            withTimeout(WAIT_BUDGET_MS) {
                select<Unit> {
                    computation.entered.onAwait { }
                    reading.onJoin { fail("收件箱读取没有经过计算调度器就完成了") }
                }
            }
            assertFalse(reading.isCompleted, "计算调度器放行前收件箱解析与模型已经交付")
            computation.release.complete(Unit)
            val page = (withTimeout(WAIT_BUDGET_MS) { reading.await() } as Outcome.Success).value

            assertEquals((1..10).toList(), page.items.map { it.id })
            assertEquals(listOf("/pm/inbox.chii"), pagePaths())
        } finally {
            computation.close()
            reading.cancelAndJoin()
            computation.join()
            main.uninstall(emptyList())
        }
    }

    @Test
    fun `会话网页回包后_解析与正文模型分别经过计算调度器`() = runBlocking {
        signInOnWeb()
        site.conversations[300003] = syntheticConversation(10)
        val computation = SequencedDispatcher()
        val meEntered = CompletableDeferred<Unit>()
        onMe = { meEntered.complete(Unit) }
        val main = TestMain()
        main.install()
        val reading = async {
            kotlinx.coroutines.withContext(main.dispatcher) {
                repository(computation).conversation(300003)
            }
        }
        try {
            val parserStage = withTimeout(WAIT_BUDGET_MS) {
                select<DispatcherStage> {
                    computation.entered.onReceive { it }
                    reading.onJoin { fail("会话读取没有经过计算调度器就完成了") }
                }
            }
            assertFalse(meEntered.isCompleted, "会话 HTML 解析没有先经过计算调度器")
            parserStage.release.complete(Unit)
            withTimeout(WAIT_BUDGET_MS) { meEntered.await() }
            val modelStage = withTimeout(WAIT_BUDGET_MS) {
                select<DispatcherStage> {
                    computation.entered.onReceive { it }
                    reading.onJoin { fail("正文模型没有经过计算调度器就交付了") }
                }
            }
            assertFalse(reading.isCompleted, "正文模型没有在第二个计算调度器阶段完成")
            modelStage.release.complete(Unit)
            val conversation = (withTimeout(WAIT_BUDGET_MS) { reading.await() } as Outcome.Success).value!!

            assertEquals(10, conversation.messages.size)
            assertEquals("Synthetic [b]message 9[/b] & [i]text[/i]\nline", conversation.messages.last().body)
            assertEquals(listOf("/pm/conversation/300003.chii"), pagePaths())
        } finally {
            computation.close()
            reading.cancelAndJoin()
            computation.join()
            main.uninstall(emptyList())
        }
    }

    @Test
    fun `网页请求不占计算调度器_解析在计算入口等待时取消不重试`() = runBlocking {
        signInOnWeb()
        site.conversations[300003] = syntheticConversation(10)
        val requestArrived = CompletableDeferred<Unit>()
        site.onConversation = { requestArrived.complete(Unit) }
        val computation = GatedDispatcher()
        val reading = async { repository(computation).conversation(300003) }

        try {
            withTimeout(WAIT_BUDGET_MS) { requestArrived.await() }
            withTimeout(WAIT_BUDGET_MS) { computation.entered.await() }
            assertEquals(listOf("/pm/conversation/300003.chii"), pagePaths(), "计算入口未放行前网页请求已经完成")
            reading.cancel()
        } finally {
            computation.close()
            reading.cancelAndJoin()
            computation.join()
        }

        assertEquals(1, pagePaths().count { it == "/pm/conversation/300003.chii" })
    }

    @Test
    fun `网页回包后后台解析途中换号_整次读取仍作废`() = runBlocking {
        signInOnWeb()
        site.conversations[300003] = syntheticConversation(10)
        val computation = GatedDispatcher()
        val reading = async { repository(computation).conversation(300003) }
        try {
            withTimeout(WAIT_BUDGET_MS) { computation.entered.await() }
            tokenStore.completeSession(tokenStore.beginSession("other"), 7, "other")
            computation.release.complete(Unit)

            assertEquals(Outcome.Failed(Failure.SessionChanged), withTimeout(WAIT_BUDGET_MS) { reading.await() })
            assertEquals(1, pagePaths().count { it == "/pm/conversation/300003.chii" })
        } finally {
            computation.close()
            reading.cancelAndJoin()
            computation.join()
        }
    }

    @Test
    fun `坏短信页在计算调度器解析_回坏响应且不重试`() = runBlocking {
        signInOnWeb()
        site.conversations[300003] = "<main>不是短信页</main>"
        val computation = CountingDispatcher()

        assertEquals(Outcome.Failed(Failure.BadResponse), repository(computation).conversation(300003))

        assertEquals(1, pagePaths().count { it == "/pm/conversation/300003.chii" })
        assertTrue(computation.dispatches > 0)
    }

    @Test
    fun `没有网页会话不进计算调度器也不开网页`() = runBlocking {
        val computation = CountingDispatcher()

        assertEquals(
            Outcome.Failed(Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED)),
            repository(computation).conversation(300003),
        )

        assertEquals(emptyList(), paths())
        assertEquals(0, computation.dispatches)
    }

    @Test
    fun `合成收件箱与会话_十条一千条五千条逐字段不变`() = runBlocking {
        signInOnWeb()
        for (size in listOf(10, 1_000, 5_000)) {
            site.requests.clear()
            site.inbox[1] = syntheticInbox(size)
            site.conversations[300003] = syntheticConversation(size)

            val page = (repository.conversations(0) as Outcome.Success).value
            val conversation = (repository.conversation(300003) as Outcome.Success).value!!

            assertEquals(
                (0 until size).map { i ->
                    io.github.touhougo.chogumi.core.model.PmConversationSummary(
                        id = i + 1,
                        peer = UserBo(
                            id = 0,
                            username = "",
                            nickname = "Synthetic $i",
                            avatarLarge = "",
                            avatarMedium = "",
                            avatarSmall = "",
                        ),
                        date = LocalDate.of(2026, 10, 26),
                        preview = "preview & text $i",
                        unread = i % 3,
                    )
                },
                page.items,
            )
            assertEquals(0, page.total)
            assertEquals(1, page.nextOffset)
            assertEquals(300003, conversation.id)
            assertEquals(
                UserBo(
                    id = 400100,
                    username = "synthetic",
                    nickname = "Synthetic",
                    avatarLarge = "",
                    avatarMedium = "",
                    avatarSmall = "",
                ),
                conversation.peer,
            )
            assertEquals(listOf(io.github.touhougo.chogumi.core.model.PmThread(1, "Synthetic")), conversation.threads)
            assertEquals(
                (0 until size).map { i ->
                    io.github.touhougo.chogumi.core.model.PmMessage(
                        id = i + 1,
                        threadId = 1,
                        mine = i % 2 == 0,
                        body = "Synthetic [b]message $i[/b] & [i]text[/i]\nline",
                        sentAt = ZonedDateTime.parse("2026-10-26T12:09+08:00").toInstant().toEpochMilli(),
                    )
                },
                conversation.messages,
            )
            assertEquals(1, conversation.replyThreadId)
            assertEquals(1, pagePaths().count { it == "/pm/inbox.chii" })
            assertEquals(1, pagePaths().count { it == "/pm/conversation/300003.chii" })
        }
    }

    @Test
    fun `会话列表读网页的一页_页面写着当前账号就不另开账号页_记下读得出uid的对方`() = runBlocking {
        signInOnWeb()
        site.inbox[1] = PmSamples.read("conversation-two-threads.html")

        val page = (repository.conversations(0) as Outcome.Success).value

        assertEquals(listOf("/pm/inbox.chii"), paths())
        assertEquals("page=1", site.requests.single().url.encodedQuery)
        assertEquals(listOf(300003, 300001), page.items.map { it.id })
        val first = page.items.first()
        assertEquals("anonymous", first.peer.nickname)
        assertEquals(400100, first.peer.id)
        assertEquals(LocalDate.of(2026, 10, 26), first.date)
        assertEquals(LocalDate.of(2021, 3, 8), page.items[1].date)
        assertEquals(0, page.items[1].peer.id, "默认头像读不出对方 uid")
        assertEquals(1, page.nextOffset)
        assertEquals(300003, repository.conversationWith(400100))
    }

    @Test
    fun `翻到没有会话的页就到底了`() = runBlocking {
        signInOnWeb()

        val page = (repository.conversations(1) as Outcome.Success).value

        assertEquals("page=2", site.requests.single().url.encodedQuery)
        assertEquals(emptyList(), page.items)
        assertNull(page.nextOffset)
    }

    @Test
    fun `网页那边已登出_打开账号页确认后要账号密码登录并丢掉会话`() = runBlocking {
        signInOnWeb()
        site.userId = 0

        assertEquals(Outcome.Failed(Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED)), repository.conversations(0))

        assertEquals(listOf("/pm/inbox.chii", "/settings/privacy"), paths())
        assertNull(tokenStore.webSessionFor(tokenStore.session()))
    }

    @Test
    fun `没有网页会话时要账号密码登录且不开网页`() = runBlocking {
        assertEquals(Outcome.Failed(Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED)), repository.conversation(300003))
        assertEquals(emptyList(), paths())
        assertEquals(0, meCalls.get())
    }

    @Test
    fun `会话读出信与时刻_记下对方_网页算作读过后重读未读短信`() = runBlocking {
        signInOnWeb()
        site.conversations[300003] = PmSamples.read("conversation-two-threads.html")
        site.unread = 2
        notifications.refreshUnreadCount()
        assertEquals(2, notifications.unreadMessages.value)
        site.unread = 0

        val conversation = (repository.conversation(300003) as Outcome.Success).value!!

        assertEquals(400100, conversation.peer.id)
        assertEquals(listOf(500001, 500002, 500003), conversation.messages.map { it.id })
        assertEquals(ZonedDateTime.parse("2026-10-26T12:09+08:00").toInstant().toEpochMilli(), conversation.messages.first().sentAt)
        assertEquals(500003, conversation.replyThreadId)
        assertEquals(300003, repository.conversationWith(400100))
        withTimeout(WAIT_BUDGET_MS) { notifications.unreadMessages.first { it == 0 } }
        Unit
    }

    @Test
    fun `短信网页时刻按账号时区解释并允许跨日`() = runBlocking {
        signInOnWeb()
        offset = -5
        site.conversations[300003] = TWO_THREADS.replace("2026-10-26 12:09", "2026-10-26 00:09")

        val conversation = (repository.conversation(300003) as Outcome.Success).value!!

        assertEquals(ZonedDateTime.parse("2026-10-26T00:09-05:00").toInstant().toEpochMilli(), conversation.messages.first().sentAt)
    }

    @Test
    fun `缺失或非法偏移以及接口失败按默认时区且每会话只取一次`() = runBlocking {
        signInOnWeb()
        site.conversations[300003] = TWO_THREADS
        for ((index, invalid) in listOf<Int?>(null, 99, 8, -19).withIndex()) {
            if (index > 0) {
                tokenStore.completeSession(tokenStore.beginSession("next"), ME, "test")
                webSessions.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))
            }
            offset = invalid
            malformedOffset = index == 2
            repeat(2) {
                val message = (repository.conversation(300003) as Outcome.Success).value!!.messages.first()
                assertEquals(ZonedDateTime.parse("2026-10-26T12:09+08:00").toInstant().toEpochMilli(), message.sentAt)
            }
        }
        tokenStore.completeSession(tokenStore.beginSession("failed"), ME, "test")
        webSessions.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))
        meStatus = HttpStatusCode.InternalServerError
        repeat(2) { assertTrue(repository.conversation(300003) is Outcome.Success) }
        assertEquals(5, meCalls.get())
    }

    @Test
    fun `账号时区接受正负十八小时边界`() = runBlocking {
        signInOnWeb()
        site.conversations[300003] = TWO_THREADS
        for ((index, hours) in listOf(-18, 18).withIndex()) {
            if (index > 0) {
                tokenStore.completeSession(tokenStore.beginSession("next"), ME, "test")
                webSessions.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))
            }
            offset = hours
            val message = (repository.conversation(300003) as Outcome.Success).value!!.messages.first()
            val expected = java.time.LocalDateTime.parse("2026-10-26T12:09")
                .toInstant(java.time.ZoneOffset.ofHours(hours)).toEpochMilli()
            assertEquals(expected, message.sentAt)
        }
        assertEquals(2, meCalls.get())
    }

    @Test
    fun `网页时刻格式不合法仍记为零`() = runBlocking {
        signInOnWeb()
        site.conversations[300003] = TWO_THREADS.replace("2026-10-26 12:09", "not-a-date")
        val message = (repository.conversation(300003) as Outcome.Success).value!!.messages.first()
        assertEquals(0L, message.sentAt)
    }

    @Test
    fun `并发读会话共用一次时区请求_换号重取`() = runBlocking {
        signInOnWeb()
        site.conversations[300003] = TWO_THREADS
        offset = -5
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        onMe = { entered.complete(Unit); release.await() }
        val first = async { repository.conversation(300003) }
        entered.await()
        val secondPage = CompletableDeferred<Unit>()
        site.onConversation = { secondPage.complete(Unit) }
        val second = async { repository.conversation(300003) }
        secondPage.await()
        yield()
        release.complete(Unit)
        assertTrue(first.await() is Outcome.Success)
        assertTrue(second.await() is Outcome.Success)
        assertEquals(1, meCalls.get())

        tokenStore.completeSession(tokenStore.beginSession("new"), ME, "test")
        webSessions.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))
        offset = 9
        onMe = {}
        val message = (repository.conversation(300003) as Outcome.Success).value!!.messages.first()
        assertEquals(ZonedDateTime.parse("2026-10-26T12:09+09:00").toInstant().toEpochMilli(), message.sentAt)
        assertEquals(2, meCalls.get())
    }

    @Test
    fun `取消时区请求不缓存默认值`() = runBlocking {
        signInOnWeb()
        site.conversations[300003] = TWO_THREADS
        offset = -5
        val entered = CompletableDeferred<Unit>()
        onMe = { entered.complete(Unit); CompletableDeferred<Unit>().await() }
        val first = async { repository.conversation(300003) }
        entered.await()
        first.cancelAndJoin()
        onMe = {}
        val message = (repository.conversation(300003) as Outcome.Success).value!!.messages.first()
        assertEquals(ZonedDateTime.parse("2026-10-26T12:09-05:00").toInstant().toEpochMilli(), message.sentAt)
        assertEquals(2, meCalls.get())
    }

    @Test
    fun `旧账号时区迟到不能污染新会话`() = runBlocking {
        signInOnWeb()
        site.conversations[300003] = TWO_THREADS
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        offset = -5
        onMe = { entered.complete(Unit); release.await() }
        val old = async { repository.conversation(300003) }
        entered.await()
        tokenStore.completeSession(tokenStore.beginSession("new"), ME, "test")
        webSessions.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))
        release.complete(Unit)
        assertEquals(Outcome.Failed(Failure.SessionChanged), old.await())
        offset = 9
        onMe = {}
        val message = (repository.conversation(300003) as Outcome.Success).value!!.messages.first()
        assertEquals(ZonedDateTime.parse("2026-10-26T12:09+09:00").toInstant().toEpochMilli(), message.sentAt)
        assertEquals(2, meCalls.get())
    }

    @Test
    fun `会话页上对方主页的链接是uid_设了用户名的人换成用户名_同一个人只问一次`() = runBlocking {
        signInOnWeb()
        site.conversations[300003] = PmSamples.read("conversation-two-threads.html")
        site.usernames[400100] = "anon_sai"

        val conversation = (repository.conversation(300003) as Outcome.Success).value!!
        repository.conversation(300003)

        assertEquals("anon_sai", conversation.peer.username, "点头像按用户名开主页，不按 uid（设了用户名的人按 uid 是 404）")
        assertEquals(400100, conversation.peer.id)
        assertEquals(1, paths().count { it == "/user/400100" })
    }

    @Test
    fun `没设用户名的人主页不跳转_用户名就是uid`() = runBlocking {
        signInOnWeb()
        site.conversations[300003] = PmSamples.read("conversation-two-threads.html")

        val conversation = (repository.conversation(300003) as Outcome.Success).value!!

        assertEquals("400100", conversation.peer.username)
    }

    @Test
    fun `正文从网页的HTML还原成BBCode`() = runBlocking {
        signInOnWeb()
        site.conversations[300003] = PmSamples.read("conversation-all-tags.html")

        val body = (repository.conversation(300003) as Outcome.Success).value!!.messages.single().body

        assertTrue(body.startsWith("[b]粗体[/b] [i]斜体[/i] [u]下划线[/u] [s]删除线[/s] [mask]遮罩[/mask]\n"), body)
        assertTrue("表情 (bgm38) (bgm24)\n" in body, body)
        assertTrue("<span" !in body && "<img" !in body, body)
    }

    @Test
    fun `会话不在时为null`() = runBlocking {
        signInOnWeb()

        assertEquals(Outcome.Success(null), repository.conversation(999))
        assertEquals(listOf("/pm/conversation/999.chii", "/settings/privacy"), pagePaths(), "页面回跳转时确认会话仍然有效")
    }

    @Test
    fun `读会话途中换了账号_结果作废`() = runBlocking {
        signInOnWeb()
        site.conversations[300003] = PmSamples.read("conversation-two-threads.html")
        site.onConversation = { tokenStore.completeSession(tokenStore.beginSession("other"), 7, "other") }

        assertEquals(Outcome.Failed(Failure.SessionChanged), repository.conversation(300003))
    }

    @Test
    fun `回复照网页的表单发出_跳到会话就回会话id并记下对方`() = runBlocking {
        signInOnWeb()

        val sent = repository.send(PmDraft(receiverId = 400100, body = "回复", replyTo = 500003), conversationId = 300003)

        assertEquals(Outcome.Success(300003), sent)
        assertEquals(listOf("/settings/privacy", "/pm/create.chii"), paths())
        val post = site.requests.last()
        assertEquals("https://bgm.tv/pm/conversation/300003.chii", post.headers[HttpHeaders.Referrer])
        val form = (post.body as FormDataContent).formData
        assertEquals("0a1b2c3d", form["formhash"], "表单校验值取自账号页")
        assertEquals("400100", form["msg_receivers"])
        assertEquals("500003", form["related"])
        assertNull(form["new_topic"])
        assertEquals(300003, repository.conversationWith(400100))
    }

    @Test
    fun `找和某人的会话_本机没记过时读会话列表第一页_读得出uid的行就是那段会话`() = runBlocking {
        signInOnWeb()
        site.inbox[1] = TWO_THREADS
        site.conversations[300003] = TWO_THREADS
        val computation = CountingDispatcher()

        val found = (repository(computation).findConversation(ANONYMOUS) as Outcome.Success).value

        assertEquals(300003, found?.id)
        assertEquals(listOf("/pm/inbox.chii", "/pm/conversation/300003.chii"), pagePaths())
        assertTrue(computation.dispatches >= 3, "inbox、会话页、逐信模型都经过计算调度器")
    }

    @Test
    fun `本机记得和某人的会话_直接读那段会话_不读会话列表`() = runBlocking {
        signInOnWeb()
        site.conversations[300003] = TWO_THREADS
        val computation = CountingDispatcher()
        val counted = repository(computation)
        counted.conversation(300003)
        val before = computation.dispatches
        site.requests.clear()

        assertEquals(300003, (counted.findConversation(ANONYMOUS) as Outcome.Success).value?.id)
        assertEquals(listOf("/pm/conversation/300003.chii"), pagePaths())
        assertTrue(computation.dispatches >= before + 2, "已知会话仍复用 read 的 parse 与 model 两段")
    }

    @Test
    fun `默认头像的行按名字试_会话里对方uid对得上才算_同名的别人不算`() = runBlocking {
        signInOnWeb()
        // 默认头像的地址里没有 uid：这一行只读得出名字
        site.inbox[1] = TWO_THREADS.replace("/pic/user/l/000/40/01/400100_a1B2c.jpg", "/pic/user/l/icon.jpg")
        site.conversations[300003] = TWO_THREADS

        assertEquals(300003, (repository.findConversation(ANONYMOUS) as Outcome.Success).value?.id)
        assertEquals(Outcome.Success(null), repository.findConversation(ANONYMOUS.copy(id = 999)))
        assertNull(repository.conversationWith(999))
    }

    @Test
    fun `和某人没有会话_回null`() = runBlocking {
        signInOnWeb()

        assertEquals(Outcome.Success(null), repository.findConversation(ANONYMOUS))
        assertEquals(listOf("/pm/inbox.chii"), pagePaths())
    }

    @Test
    fun `新信的表单在写给对方的写信页上`() = runBlocking {
        signInOnWeb()
        site.sendAnswer = { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/pm/conversation/400000.chii")) }

        assertEquals(Outcome.Success(400000), repository.send(PmDraft(receiverId = 300002, body = "你好", title = "Chogumi")))

        assertEquals("https://bgm.tv/pm/compose/300002.chii", site.requests.last().headers[HttpHeaders.Referrer])
        assertEquals(400000, repository.conversationWith(300002))
    }

    @Test
    fun `收件人不存在时回网页给的原因`() = runBlocking {
        signInOnWeb()
        site.sendAnswer = { respond(PmSamples.read("create-error-no-receiver.html"), HttpStatusCode.OK) }

        assertEquals(Outcome.Failed(Failure.Rejected(RejectReason.PM_RECEIVER_NOT_FOUND)), repository.send(PmDraft(receiverId = 1, body = "x")))
        assertNull(repository.conversationWith(1))
    }

    @Test
    fun `删信打开网页的删除链接_重读会话那封不在了才算做成`() = runBlocking {
        signInOnWeb()
        val before = PmSamples.read("conversation-two-threads.html")
        site.conversations[300003] = before
        site.onErase = { id -> site.conversations[300003] = before.withoutMessage(id) }
        val computation = CountingDispatcher()

        val after = (repository(computation).prepareErase(300003, 500002).invoke() as Outcome.Success).value!!

        assertEquals(listOf(500001, 500003), after.messages.map { it.id })
        val erase = site.requests.single { it.url.encodedPath.startsWith("/pm/erase/") }
        assertEquals("https://bgm.tv/pm/erase/500002.chii?gh=0a1b2c3d", erase.url.toString())
        assertEquals("https://bgm.tv/pm/conversation/300003.chii", erase.headers[HttpHeaders.Referrer])
        assertEquals("/pm/conversation/300003.chii", pagePaths().last(), "删完重读会话")
        assertEquals(1, pagePaths().count { it == "/pm/conversation/300003.chii" })
        assertTrue(computation.dispatches >= 2, "删除确认复用 read 的 parse 与 model 两段")
    }

    @Test
    fun `看会话和删信后的回读都发出读过_坏页不发`() = runBlocking {
        signInOnWeb()
        val before = PmSamples.read("conversation-two-threads.html")
        site.conversations[300003] = before
        site.conversations[300004] = "<main>不是短信页</main>"
        site.onErase = { id -> site.conversations[300003] = before.withoutMessage(id) }
        val reads = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val collector = launch(Dispatchers.Unconfined) { repository.conversationsRead.collect { reads += it } }
        try {
            assertTrue(repository.conversation(300003) is Outcome.Success)
            assertTrue(repository.conversation(300004) is Outcome.Failed)
            assertTrue(repository.prepareErase(300003, 500002).invoke() is Outcome.Success)

            assertTrue(pagePaths().contains("/pm/conversation/300004.chii"), "坏页的读取发出去了")
            assertEquals(listOf(300003, 300003), reads.toList())
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun `删完重读那封还在_是删不掉`() = runBlocking {
        signInOnWeb()
        site.conversations[300003] = PmSamples.read("conversation-two-threads.html")

        assertEquals(Outcome.Failed(Failure.Rejected(RejectReason.PM_DELETE_UNAVAILABLE)), repository.prepareErase(300003, 500002).invoke())
        assertTrue(paths().any { it == "/pm/erase/500002.chii" })
    }

    @Test
    fun `删掉会话里最后一封_会话不在了回null_不再记着对方`() = runBlocking {
        signInOnWeb()
        site.conversations[300003] = PmSamples.read("peer-conversation.html")
        assertEquals(300003, (repository.conversation(300003) as Outcome.Success).value?.id)
        site.onErase = { site.conversations.remove(300003) }

        assertEquals(Outcome.Success(null), repository.prepareErase(300003, 500002).invoke())
        assertNull(repository.conversationWith(400200))
    }

    @Test
    fun `有网页会话时未读短信随通知数一同刷新_没有网页会话时为0且不开网页`() = runBlocking {
        preferences.setExperienceMode(ExperienceMode.FULL)
        site.unread = 2
        notifications.refreshUnreadCount()
        assertEquals(0, notifications.unreadMessages.value)
        assertEquals(emptyList(), paths())

        signInOnWeb()
        notifications.refreshUnreadCount()
        assertEquals(2, notifications.unreadMessages.value)
        assertEquals(listOf("/json/notify"), paths())
    }

    @Test
    fun `全部已读打开网页给的地址_做成后归零`() = runBlocking {
        signInOnWeb()
        site.unread = 2
        notifications.refreshUnreadCount()

        assertEquals(Outcome.Success(Unit), notifications.markAllMessagesRead())

        assertEquals(0, notifications.unreadMessages.value)
        assertEquals("gh=FORMHASH&ajax=1", site.requests.single { it.url.encodedPath == "/pm/ignore_unread.chii" }.url.encodedQuery)
    }

    @Test
    fun `全部已读之前发出的未读读数迟到也不覆盖归零`() = runBlocking {
        signInOnWeb()
        site.unread = 2
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        site.onNotify = {
            site.onNotify = {}
            arrived.complete(Unit)
            release.await()
        }
        val late = resources.scope.async { notifications.refreshUnreadCount() }
        withTimeout(WAIT_BUDGET_MS) { arrived.await() }

        assertEquals(Outcome.Success(Unit), notifications.markAllMessagesRead())
        release.complete(Unit)
        withTimeout(WAIT_BUDGET_MS) { late.await() }

        assertEquals(0, notifications.unreadMessages.value)
    }

    private companion object {
        const val ME = 400200
    }
}

/** 去掉会话页上的一封信（[id]）。 */
private fun String.withoutMessage(id: Int): String =
    replace(Regex("""<div id="msg_$id".*?</div>\s*</div>\s*</div>""", RegexOption.DOT_MATCHES_ALL), "")

private val TWO_THREADS = PmSamples.read("conversation-two-threads.html")

/** 测试号：会话样本里的对方。 */
private val ANONYMOUS = UserBo(id = 400100, username = "anonymous_test", nickname = "anonymous")

/** 全合成的收件箱页；字段公式由测试直接构造 expected，不经过生产 parser。 */
private fun syntheticInbox(size: Int) = buildString {
    append("<div id='contentPM'><div class='pm-conversation-list'>")
    repeat(size) { i ->
        append("<a class='pm-conversation-item' href='/pm/conversation/${i + 1}.chii'>")
        append("<span class='pm-conversation-name'>Synthetic $i</span>")
        append("<span class='pm-conversation-date'>2026-10-26</span>")
        append("<span class='pm-conversation-desc'>preview &amp; text $i</span>")
        append("<span class='pm-conversation-unread'>${i % 3}</span></a>")
    }
    append("</div></div>")
}

/** 全合成的会话页；每封信都让生产路径执行 HTML -> BBCode 和时刻转换。 */
private fun syntheticConversation(size: Int) = buildString {
    append("<div id='contentPM'><div class='pm-chat-panel'>")
    append("<div class='pm-chat-title'><strong><a href='/user/synthetic'>Synthetic</a></strong></div>")
    append("<form id='pmReplyForm'><input name='msg_receivers' value='400100'><input name='related' value='1'></form>")
    append("<div class='pm-thread-filter'><a href='?thread=1'>Synthetic</a></div>")
    append("<div class='pm-message-list'><div class='pm-thread-label'>Synthetic</div>")
    repeat(size) { i ->
        append("<div class='pm-message${if (i % 2 == 0) " pm-message-self" else ""}' id='msg_${i + 1}'>")
        append("<div class='pm-message-body'>Synthetic <span style='font-weight:bold'>message $i</span> &amp; ")
        append("<span style='font-style:italic'>text</span><br>line</div>")
        append("<div class='pm-message-info'>2026-10-26 12:09</div></div>")
    }
    append("</div></div></div>")
}

/** 一次调度对应一个独立阶段；测试拿到 token 后才放行该段 continuation。 */
private class DispatcherStage {
    val release = CompletableDeferred<Unit>()
}

/** 记录进入并等测试放行；用来证明网页请求先完成、只有后续解析被切换。 */
private class GatedDispatcher : CoroutineDispatcher() {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val lock = Any()
    private var closed = false

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val runInline = synchronized(lock) {
            if (closed) {
                true
            } else {
                // launch 也放进锁内，保证 close/join 不可能先完成而漏掉已经接受的 continuation。
                scope.launch {
                    entered.complete(Unit)
                    release.await()
                    block.run()
                }
                false
            }
        }
        if (runInline) {
            block.run()
        }
    }

    /** 封住后续 dispatch 并放行所有已排队的 continuation；[join] 负责回收调度任务。 */
    fun close() {
        synchronized(lock) { closed = true }
        release.complete(Unit)
    }

    suspend fun join() {
        job.complete()
        job.join()
    }
}

/** 每次 dispatch 都交付独立 stage token，测试逐个关联并放行解析、正文模型两个阶段。 */
private class SequencedDispatcher : CoroutineDispatcher() {
    val entered = Channel<DispatcherStage>(Channel.UNLIMITED)
    private val pending = mutableListOf<DispatcherStage>()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val lock = Any()
    private var closed = false

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val runInline = synchronized(lock) {
            if (closed) {
                true
            } else {
                val stage = DispatcherStage().also { pending += it }
                // 子 job 必须在锁内注册：否则 close/join 可能先完成，使随后 launch 的 block 永远不运行。
                scope.launch {
                    entered.send(stage)
                    stage.release.await()
                    block.run()
                }
                false
            }
        }
        if (runInline) {
            block.run()
        }
    }

    /** 封住后续 dispatch 并放行所有已排队 stage，确保取消 continuation 也能运行。 */
    fun close() {
        val pendingStages = synchronized(lock) {
            closed = true
            pending.toList().also { pending.clear() }
        }
        pendingStages.forEach { it.release.complete(Unit) }
    }

    suspend fun join() {
        job.complete()
        job.join()
        entered.close()
    }
}

/**
 * 假的旧站短信页：账号页回 [userId] 登录着的页面；短信页是样本外面套上网页每页都有的当前用户脚本（`CHOBITS_UID`）。
 * [inbox] 是会话列表的各页（没写的页没有会话），[conversations] 是各会话（没写的会话回跳转到 `/pm`），
 * 发信回 [sendAnswer]，删除链接先跑 [onErase] 再跳回 `/pm`，未读数 JSON 回 [unread] 条，「全部已读」把它清零。请求都记进 [requests]。
 */
private class PmWebSite(@Volatile var userId: Int, private val formhash: String = "0a1b2c3d") {
    val requests = ConcurrentRequestRecorder()
    val inbox = ConcurrentHashMap<Int, String>()
    val conversations = ConcurrentHashMap<Int, String>()

    /** 设了用户名的人：uid → 用户名（公开主页 `/user/{uid}` 跳到 `/user/{用户名}`）；不在这里的人主页不跳转。 */
    val usernames = ConcurrentHashMap<Int, String>()

    @Volatile var unread = 0
    @Volatile var onNotify: suspend () -> Unit = {}
    @Volatile var onConversation: suspend () -> Unit = {}
    @Volatile var onErase: (Int) -> Unit = {}
    @Volatile var sendAnswer: io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData =
        { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/pm/conversation/300003.chii")) }

    private fun signedIn(page: String) = "<script>var CHOBITS_UID = $userId;</script>\n$page"

    val client = testWebClient { request ->
        requests.record(request)
        val path = request.url.encodedPath
        val conversation = Regex("""/pm/conversation/(\d+)\.chii""").matchEntire(path)?.groupValues?.get(1)?.toInt()
        val erase = Regex("""/pm/erase/(\d+)\.chii""").matchEntire(path)?.groupValues?.get(1)?.toInt()
        val home = Regex("""/user/(\d+)""").matchEntire(path)?.groupValues?.get(1)?.toInt()
        when {
            request.method == HttpMethod.Post && path == "/pm/create.chii" -> sendAnswer(request)
            path == "/settings/privacy" -> respond(signedIn("""<a href="/logout/$formhash">登出</a>"""), HttpStatusCode.OK)
            path == "/pm/inbox.chii" -> {
                val page = request.url.parameters["page"]?.toInt() ?: 1
                respond(signedIn(inbox[page] ?: PmSamples.read("inbox-empty.html")), HttpStatusCode.OK)
            }
            conversation != null -> {
                onConversation()
                val page = conversations[conversation]
                if (page != null) respond(signedIn(page), HttpStatusCode.OK) else respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/pm"))
            }
            home != null -> usernames[home]
                ?.let { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/user/$it")) }
                ?: respond("", HttpStatusCode.OK)
            erase != null -> {
                onErase(erase)
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/pm"))
            }
            path == "/json/notify" -> {
                val count = unread
                onNotify()
                respond(PmSamples.read("notify.json").replace("\"pm_count\":0", "\"pm_count\":$count"), HttpStatusCode.OK)
            }
            path == "/pm/ignore_unread.chii" -> {
                unread = 0
                respond("""{"status":"ok"}""", HttpStatusCode.OK)
            }
            else -> error("意外请求：${request.url}")
        }
    }
}
