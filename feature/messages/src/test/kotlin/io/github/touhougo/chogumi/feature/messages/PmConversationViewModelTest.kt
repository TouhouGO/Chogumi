package io.github.touhougo.chogumi.feature.messages

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.data.repository.boundDraftOwner
import io.github.touhougo.chogumi.core.data.repository.testDraftRepository
import io.github.touhougo.chogumi.core.data.repository.testUserRepository
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.model.PostDraftKind
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.github.touhougo.chogumi.core.data.repository.NotificationRepository
import io.github.touhougo.chogumi.core.data.repository.PmRepository
import io.github.touhougo.chogumi.core.data.repository.testAccountMe
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.repository.testWebSessionRepository
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.network.PmSamples
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 会话页：读整段会话；回复、开新主题照网页表单发出，重读会话后才收起面板；删信用删完重读的那份会话替换。
 * 写给一个人：查到对方、找和该用户的会话，打开即弹出编辑器；没有会话时写第一封，发出后就是网页跳去的会话。
 */
class PmConversationViewModelTest {
    private val main = TestMain()
    private val resources = TestResources()
    private val site = PmSite()
    private val tokenStore = testTokenStore(resources, userId = ME)
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
    private val repository = PmRepository(tokenStore, webSessions, notifications, resources.scope, testAccountMe(resources, tokenStore), Dispatchers.Unconfined)
    private val users = testUserRepository(resources, UserApi(resources.apiClient { respond("", HttpStatusCode.NotFound) }), tokenStore, CachedUsers)
    private val drafts = testDraftRepository(resources.scope, tokens = tokenStore)
    private var vm: PmConversationViewModel? = null

    @BeforeTest
    fun setUp() {
        main.install()
        runBlocking { webSessions.save(tokenStore.session(), WebSession("chii_auth=c00k1e")) }
    }

    @AfterTest
    fun tearDown() {
        main.uninstall(listOfNotNull(vm?.viewModelScope))
        resources.close()
    }

    private suspend fun open(key: PmChatKey = PmChatKey.Conversation(CONVERSATION)): PmConversationViewModel {
        val created = withContext(main.dispatcher) { PmConversationViewModel(key, repository, users, drafts, AppClock.SYSTEM) }
        vm = created
        awaitUntil("会话已读到") { created.uiState.value.chat is Load.Ready }
        return created
    }

    private fun PmConversationViewModel.conversation() = uiState.value.chat.valueOrNull()?.conversation

    private fun PmConversationViewModel.messageIds() = conversation()?.messages?.map { it.id }

    private fun paths() = site.requests.map { it.url.encodedPath }

    private suspend fun sendReply(vm: PmConversationViewModel, threadId: Int?, draft: ComposerDraft) {
        withContext(main.dispatcher) { vm.reply(threadId) }
        awaitUntil("回复面板已打开") { vm.uiState.value.composer.target != null }
        withContext(main.dispatcher) { vm.composer.send(draft, token = "") }
        awaitUntil("面板收起") { vm.uiState.value.composer.sent }
    }

    @Test
    fun `默认回复网页上最新那条线_引用条是它的主题名`() = runBlocking {
        site.conversations[CONVERSATION] = TWO_THREADS
        val vm = open()

        withContext(main.dispatcher) { vm.reply() }

        // 面板先绑定草稿的账号再给出对象（草稿按收件人与线存）
        awaitUntil("回复面板已打开") { vm.uiState.value.composer.target != null }
        val target = assertNotNull(vm.uiState.value.composer.target)
        assertEquals(500003, target.threadId)
        assertEquals(400100, target.receiverId)
        assertEquals("Chogumi 测试新主题", target.quote?.author)
        assertEquals("Chogumi 新主题选项测试。", target.quote?.excerpt)
    }

    @Test
    fun `回复照网页表单发出_重读会话新信已在里面才收起面板`() = runBlocking {
        site.conversations[CONVERSATION] = TWO_THREADS.withoutMessage(500003)
        site.onSend = { site.conversations[CONVERSATION] = TWO_THREADS }
        val vm = open()
        assertEquals(listOf(500001, 500002), vm.messageIds())

        sendReply(vm, threadId = null, ComposerDraft(title = "", text = "回复"))

        val form = (site.requests.single { it.method == HttpMethod.Post }.body as FormDataContent).formData
        assertEquals("500003", form["related"])
        assertEquals("回复", form["msg_body"])
        assertNull(form["new_topic"], "没填主题是回复这条线")
        assertTrue(paths().lastIndexOf("/pm/conversation/$CONVERSATION.chii") > paths().indexOf("/pm/create.chii"), "发出后重读会话")
        assertEquals(listOf(500001, 500002, 500003), vm.messageIds(), "面板收起时新信已经在会话里")
    }

    @Test
    fun `发信确认重读与删除共用队列_删除等重读落定再执行`() = runBlocking {
        site.conversations[CONVERSATION] = TWO_THREADS.withoutMessage(500003)
        site.onSend = { site.conversations[CONVERSATION] = TWO_THREADS }
        val vm = open()
        val sendReadCaptured = CompletableDeferred<Unit>()
        val releaseSendRead = CompletableDeferred<Unit>()
        site.holdNextConversationRead(sendReadCaptured, releaseSendRead)
        site.onErase = { id -> site.conversations[CONVERSATION] = site.conversations.getValue(CONVERSATION).withoutMessage(id) }
        val erased = vm.conversation()!!.messages.first()
        withContext(main.dispatcher) { vm.reply() }
        awaitUntil("回复面板已打开") { vm.uiState.value.composer.target != null }

        withContext(main.dispatcher) { vm.composer.send(ComposerDraft(title = "", text = "回复"), token = "") }
        withTimeout(WAIT_BUDGET_MS) { sendReadCaptured.await() }
        withContext(main.dispatcher) { vm.erase(erased) {} }
        assertEquals(0, site.eraseRequests.get(), "删除明确等发信确认重读落定，不从队列旁路")

        releaseSendRead.complete(Unit)
        awaitUntil("确认重读后删除换上服务器最新会话") {
            site.eraseResponses.get() == 1 && vm.messageIds() == listOf(500002, 500003) && vm.uiState.value.composer.sent
        }
    }

    @Test
    fun `点主题标签回复那条线_填了主题就是在会话里开新主题`() = runBlocking {
        site.conversations[CONVERSATION] = TWO_THREADS
        val vm = open()

        sendReply(vm, threadId = 500001, ComposerDraft(title = "另起一个", text = "正文"))

        val form = (site.requests.single { it.method == HttpMethod.Post }.body as FormDataContent).formData
        assertEquals("500001", form["related"])
        assertEquals("1", form["new_topic"])
        assertEquals("另起一个", form["msg_title"])
    }

    @Test
    fun `删掉最后一封_会话不在了_通知页面返回`() = runBlocking {
        site.conversations[CONVERSATION] = PmSamples.read("peer-conversation.html")
        site.onErase = { site.conversations.remove(CONVERSATION) }
        val vm = open()
        val message = vm.conversation()!!.messages.last()
        val gone = CompletableDeferred<Unit>()

        withContext(main.dispatcher) { vm.erase(message) { gone.complete(Unit) } }

        withTimeout(WAIT_BUDGET_MS) { gone.await() }
        assertEquals(Load.Ready(null), vm.uiState.value.chat)
    }

    @Test
    fun `删完重读那封还在_提示删不掉并能重试_会话不动`() = runBlocking {
        site.conversations[CONVERSATION] = TWO_THREADS
        val vm = open()
        val message = vm.conversation()!!.messages.first()

        withContext(main.dispatcher) { vm.erase(message) {} }

        awaitUntil("失败提示已到") { vm.failures.current.value != null }
        val notice = assertNotNull(vm.failures.current.value)
        assertEquals(NoticeText.Reason(Failure.Rejected(RejectReason.PM_DELETE_UNAVAILABLE)), notice.text)
        assertNotNull(notice.retry)
        assertEquals(listOf(500001, 500002, 500003), vm.messageIds())
    }

    @Test
    fun `删信之前发出的重读迟到_不把删掉的那封带回来`() = runBlocking {
        site.conversations[CONVERSATION] = TWO_THREADS
        site.onErase = { id -> site.conversations[CONVERSATION] = TWO_THREADS.withoutMessage(id) }
        val vm = open()
        // 回到页面时的重读：读到的是删信之前的会话，回得比删信晚
        val stalled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        site.holdNextConversationRead(stalled, release)
        withContext(main.dispatcher) { vm.refresh() }
        withTimeout(WAIT_BUDGET_MS) { stalled.await() }

        val message = vm.conversation()!!.messages[1]
        withContext(main.dispatcher) { vm.erase(message) {} }
        release.complete(Unit)

        awaitUntil("排在旧重读之后的删除换上最新会话") { vm.messageIds() == listOf(500001, 500003) }
        assertEquals(listOf(500001, 500003), vm.messageIds(), "旧会话不覆盖删完的那份")
        assertTrue(paths().any { it == "/pm/erase/500002.chii" })
    }

    @Test
    fun `连续删不同短信_第二笔明确排在第一笔之后_最终两封都不复活`() = runBlocking {
        site.conversations[CONVERSATION] = TWO_THREADS
        site.onErase = { id -> site.conversations[CONVERSATION] = site.conversations.getValue(CONVERSATION).withoutMessage(id) }
        val firstReachedServer = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        site.respondToErase { id ->
            if (id == 500001) {
                firstReachedServer.complete(Unit)
                releaseFirst.await()
            }
        }
        val vm = open()
        val first = vm.conversation()!!.messages[0]
        val second = vm.conversation()!!.messages[1]

        withContext(main.dispatcher) { vm.erase(first) {} }
        withTimeout(WAIT_BUDGET_MS) { firstReachedServer.await() }
        withContext(main.dispatcher) { vm.erase(second) {} }
        assertEquals(1, site.eraseRequests.get(), "第二笔已经收下但尚未访问服务器，要等第一笔完成")

        releaseFirst.complete(Unit)
        awaitUntil("两笔删除都完成") { site.eraseResponses.get() == 2 && vm.messageIds() == listOf(500003) }

        assertEquals(listOf("/pm/erase/500001.chii", "/pm/erase/500002.chii"), paths().filter { it.startsWith("/pm/erase/") })
        assertEquals(listOf(500003), vm.messageIds())
    }

    @Test
    fun `连续删最后两封_只通知页面返回一次`() = runBlocking {
        site.conversations[CONVERSATION] = PmSamples.read("peer-conversation.html")
        site.onErase = { id ->
            site.conversations.computeIfPresent(CONVERSATION) { _, page ->
                page.withoutMessage(id).takeIf { remaining -> Regex("""id="msg_\d+"""").containsMatchIn(remaining) }
            }
        }
        val firstReachedServer = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        site.respondToErase { id ->
            if (id == 500001) {
                firstReachedServer.complete(Unit)
                releaseFirst.await()
            }
        }
        val vm = open()
        val messages = vm.conversation()!!.messages
        val gone = AtomicInteger(0)

        withContext(main.dispatcher) { vm.erase(messages[0]) { gone.incrementAndGet() } }
        withTimeout(WAIT_BUDGET_MS) { firstReachedServer.await() }
        withContext(main.dispatcher) { vm.erase(messages[1]) { gone.incrementAndGet() } }

        releaseFirst.complete(Unit)
        awaitUntil("两笔删除都完成且页面只返回一次") { site.eraseResponses.get() == 2 && gone.get() == 1 }
        assertEquals(Load.Ready(null), vm.uiState.value.chat)
        assertEquals(1, gone.get())
    }

    @Test
    fun `连续重复删同一封_第二笔也排队_已经不在时仍保持最新会话`() = runBlocking {
        site.conversations[CONVERSATION] = TWO_THREADS
        site.onErase = { id -> site.conversations[CONVERSATION] = site.conversations.getValue(CONVERSATION).withoutMessage(id) }
        val firstReachedServer = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        site.respondToErase { id ->
            if (id == 500001 && site.eraseRequests.get() == 1) {
                firstReachedServer.complete(Unit)
                releaseFirst.await()
            }
        }
        val vm = open()
        val message = vm.conversation()!!.messages.first()

        withContext(main.dispatcher) { vm.erase(message) {} }
        withTimeout(WAIT_BUDGET_MS) { firstReachedServer.await() }
        withContext(main.dispatcher) { vm.erase(message) {} }
        assertEquals(1, site.eraseRequests.get(), "重复动作没有静默丢弃，先在队列里等待")

        releaseFirst.complete(Unit)
        awaitUntil("重复的两笔删除都完成") { site.eraseResponses.get() == 2 }
        assertEquals(listOf(500002, 500003), vm.messageIds())
    }

    @Test
    fun `删除失败后重试_仍会重新排队并能删掉`() = runBlocking {
        site.conversations[CONVERSATION] = TWO_THREADS
        val attempts = AtomicInteger(0)
        site.onErase = { id ->
            if (attempts.incrementAndGet() > 1) {
                site.conversations[CONVERSATION] = site.conversations.getValue(CONVERSATION).withoutMessage(id)
            }
        }
        val vm = open()
        val message = vm.conversation()!!.messages.first()

        withContext(main.dispatcher) { vm.erase(message) {} }
        awaitUntil("失败提示已到") { vm.failures.current.value != null }
        assertEquals(listOf(500001, 500002, 500003), vm.messageIds(), "单笔失败保留屏上会话")
        val retry = assertNotNull(vm.failures.current.value?.retry)

        withContext(main.dispatcher) { retry() }
        awaitUntil("重试删掉目标短信") { site.eraseResponses.get() == 2 && vm.messageIds() == listOf(500002, 500003) }
        assertEquals(2, attempts.get())
    }

    @Test
    fun `删除在途时普通重读排在删除之后_不把旧会话带回来`() = runBlocking {
        site.conversations[CONVERSATION] = TWO_THREADS
        val eraseReachedServer = CompletableDeferred<Unit>()
        val releaseErase = CompletableDeferred<Unit>()
        site.respondToErase { id ->
            eraseReachedServer.complete(Unit)
            releaseErase.await()
            site.conversations[CONVERSATION] = site.conversations.getValue(CONVERSATION).withoutMessage(id)
        }
        val vm = open()
        val readsBefore = site.conversationReads.get()
        val message = vm.conversation()!!.messages.first()

        withContext(main.dispatcher) { vm.erase(message) {} }
        withTimeout(WAIT_BUDGET_MS) { eraseReachedServer.await() }
        withContext(main.dispatcher) { vm.refresh() }
        assertEquals(readsBefore, site.conversationReads.get(), "普通重读已经收下但要等删除与确认读完成")

        releaseErase.complete(Unit)
        awaitUntil("删除与排队的重读都落定") { site.conversationReads.get() == readsBefore + 2 && vm.messageIds() == listOf(500002, 500003) }
        assertEquals(listOf(500002, 500003), vm.messageIds())
    }

    @Test
    fun `排队的第二笔删除固定在点击时账号_切号后不访问新账号`() = runBlocking {
        site.conversations[CONVERSATION] = TWO_THREADS
        site.onErase = { id -> site.conversations[CONVERSATION] = site.conversations.getValue(CONVERSATION).withoutMessage(id) }
        val firstReachedServer = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        site.respondToErase { id ->
            if (id == 500001) {
                firstReachedServer.complete(Unit)
                releaseFirst.await()
            }
        }
        val vm = open()
        val messages = vm.conversation()!!.messages

        withContext(main.dispatcher) { vm.erase(messages[0]) {} }
        withTimeout(WAIT_BUDGET_MS) { firstReachedServer.await() }
        withContext(main.dispatcher) { vm.erase(messages[1]) {} }
        tokenStore.completeSession(tokenStore.beginSession("other"), userId = ME + 1, username = "other")
        releaseFirst.complete(Unit)

        awaitUntil("旧账号两笔动作均因切号结束") { vm.failures.current.value?.text == NoticeText.Reason(Failure.SessionChanged) }
        assertEquals(1, site.eraseRequests.get(), "排队的第二笔不拿新账号凭据访问服务器")
    }

    @Test
    fun `页面销毁时在途与排队删除都取消_不再访问服务器或返回`() = runBlocking {
        site.conversations[CONVERSATION] = TWO_THREADS
        val firstReachedServer = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        site.respondToErase { id ->
            if (id == 500001) {
                firstReachedServer.complete(Unit)
                neverRelease.await()
            }
        }
        val vm = open()
        val messages = vm.conversation()!!.messages
        val gone = AtomicInteger(0)

        withContext(main.dispatcher) { vm.erase(messages[0]) { gone.incrementAndGet() } }
        withTimeout(WAIT_BUDGET_MS) { firstReachedServer.await() }
        withContext(main.dispatcher) { vm.erase(messages[1]) { gone.incrementAndGet() } }

        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        assertEquals(1, site.eraseRequests.get(), "排队的第二笔随页面销毁，没有再访问服务器")
        assertEquals(0, gone.get())
    }

    @Test
    fun `点击删除同步固定账号_主线程尚未启动协程就切号也不访问新账号`() = runBlocking {
        site.conversations[CONVERSATION] = TWO_THREADS
        val vm = open()
        val message = vm.conversation()!!.messages.first()
        // 先占住主线程：删除协程排在它后面，放开之前启动不了，切号一定落在协程启动之前；点删除从测试线程发起，
        // 它只取账号、把协程排进主线程队列，不需要主线程
        val mainHeld = CountDownLatch(1)
        val holding = launch(main.dispatcher) { mainHeld.await(WAIT_BUDGET_MS, TimeUnit.MILLISECONDS) }

        vm.erase(message) {}
        tokenStore.completeSession(tokenStore.beginSession("other-before-main-runs"), userId = ME + 1, username = "other")
        mainHeld.countDown()
        holding.join()

        awaitUntil("点击时账号失效的提示已到") { vm.failures.current.value?.text == NoticeText.Reason(Failure.SessionChanged) }
        assertEquals(0, site.eraseRequests.get(), "删除协程排到时不能改用新账号")
    }

    @Test
    fun `写给一个人_找得到会话就读那段会话_打开即弹出编辑器回复最新那条线`() = runBlocking {
        site.inbox = TWO_THREADS
        site.conversations[CONVERSATION] = TWO_THREADS

        val vm = open(PmChatKey.Peer(PEER_USERNAME))

        awaitUntil("编辑器已弹出") { vm.uiState.value.composer.target != null }
        assertEquals(500003, vm.uiState.value.composer.target?.threadId)
        assertEquals(PEER_USERNAME, vm.uiState.value.chat.valueOrNull()?.peer?.username, "对方取查到的资料，点了进主页")
        assertEquals(listOf(500001, 500002, 500003), vm.messageIds())
    }

    @Test
    fun `写给一个人_没有会话时写第一封_发出后就是网页跳去的会话`() = runBlocking {
        site.onSend = { site.conversations[CONVERSATION] = TWO_THREADS }
        val vm = open(PmChatKey.Peer(PEER_USERNAME))
        assertNull(vm.conversation())
        awaitUntil("编辑器已弹出") { vm.uiState.value.composer.target != null }
        assertNull(vm.uiState.value.composer.target?.threadId, "没有线可回：一封新信")

        withContext(main.dispatcher) { vm.composer.send(ComposerDraft(title = "", text = "你好"), token = "") }
        awaitUntil("面板收起") { vm.uiState.value.composer.sent }

        val send = site.requests.single { it.method == HttpMethod.Post }
        val form = (send.body as FormDataContent).formData
        assertEquals("$PEER_ID", form["msg_receivers"])
        assertNull(form["related"])
        assertEquals("https://bgm.tv/pm/compose/$PEER_ID.chii", send.headers[HttpHeaders.Referrer])
        assertEquals(CONVERSATION, vm.conversation()?.id, "发出后读的是网页跳去的那段会话")
    }

    @Test
    fun `从最近联系人选进来_读到会话就弹出回复面板`() = runBlocking {
        site.conversations[CONVERSATION] = TWO_THREADS

        val vm = open(PmChatKey.Conversation(CONVERSATION, compose = true))

        awaitUntil("回复面板已弹出") { vm.uiState.value.composer.target != null }
        assertEquals(500003, vm.uiState.value.composer.target?.threadId)
    }

    @Test
    fun `草稿按收件人与线存_再写给同一个人时没写完的还在`() = runBlocking {
        drafts.savePost(drafts.boundDraftOwner(), PostDraftKind.PM, "$PEER_ID/0", title = "主题", text = "没写完", now = 1L)

        val vm = open(PmChatKey.Peer(PEER_USERNAME))

        awaitUntil("编辑器已弹出") { vm.uiState.value.composer.target != null }
        assertEquals("没写完", vm.uiState.value.composer.text)
        assertEquals("主题", vm.uiState.value.composer.title)
    }

    private companion object {
        const val ME = 400200
        const val CONVERSATION = 300003
        const val PEER_ID = 400100
        const val PEER_USERNAME = "anonymous_test"
        val TWO_THREADS = PmSamples.read("conversation-two-threads.html")
    }
}

/** 去掉会话页上的一封信（[id]）。 */
private fun String.withoutMessage(id: Int): String =
    replace(Regex("""<div id="msg_$id".*?</div>\s*</div>\s*</div>""", RegexOption.DOT_MATCHES_ALL), "")
