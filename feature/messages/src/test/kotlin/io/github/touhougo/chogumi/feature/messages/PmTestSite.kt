package io.github.touhougo.chogumi.feature.messages

import io.github.touhougo.chogumi.core.data.db.user.UserDao
import io.github.touhougo.chogumi.core.data.db.user.UserEntity
import io.github.touhougo.chogumi.core.data.repository.ConcurrentRequestRecorder
import io.github.touhougo.chogumi.core.data.repository.testWebClient
import io.github.touhougo.chogumi.core.network.PmSamples
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred

/** 本机缓存里已有的对方资料（他人主页看过）：按用户名查到，不发请求。 */
internal object CachedUsers : UserDao {
    private val peer = UserEntity(
        id = 400100, username = "anonymous_test", nickname = "anonymous",
        avatarLarge = "", avatarMedium = "", avatarSmall = "", sign = "",
        updatedAt = System.currentTimeMillis(),
    )

    override suspend fun get(id: Int) = peer.takeIf { it.id == id }
    override suspend fun getByUsername(username: String) = peer.takeIf { it.username == username }
    override suspend fun upsert(user: UserEntity) = Unit
}

/**
 * 假的旧站：会话页、会话列表是样本外面套上网页每页都有的当前用户脚本（`CHOBITS_UID`），没写的会话回跳转到 `/pm`；
 * 发信先跑 [onSend] 再跳到会话，删除链接先跑 [onErase] 再跳回 `/pm`。请求都记进 [requests]。
 */
internal class PmSite {
    private data class HeldRead(
        val stalled: CompletableDeferred<Unit>,
        val release: CompletableDeferred<Unit>,
    )

    val requests = ConcurrentRequestRecorder()
    val conversations = ConcurrentHashMap<Int, String>()

    /** 会话列表第一页；默认没有会话。 */
    @Volatile var inbox: String = PmSamples.read("inbox-empty.html")
    val conversationReads = AtomicInteger(0)

    @Volatile var onSend: () -> Unit = {}
    @Volatile var onErase: (Int) -> Unit = {}
    @Volatile private var hold: HeldRead? = null
    @Volatile private var eraseResponse: suspend (Int) -> Unit = {}
    val eraseRequests = AtomicInteger(0)
    val eraseResponses = AtomicInteger(0)

    /** 下一次读会话：取下那一刻的页面，告诉 [stalled]，等 [release] 才回。 */
    fun holdNextConversationRead(
        stalled: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
    ) {
        hold = HeldRead(stalled, release)
    }

    fun respondToErase(block: suspend (Int) -> Unit) {
        eraseResponse = block
    }

    private fun signedIn(page: String) = "<script>var CHOBITS_UID = 400200;</script>\n$page"

    val client = testWebClient { request ->
        requests.record(request)
        val path = request.url.encodedPath
        val conversation = Regex("""/pm/conversation/(\d+)\.chii""").matchEntire(path)?.groupValues?.get(1)?.toInt()
        val erase = Regex("""/pm/erase/(\d+)\.chii""").matchEntire(path)?.groupValues?.get(1)?.toInt()
        when {
            request.method == HttpMethod.Post && path == "/pm/create.chii" -> {
                onSend()
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/pm/conversation/300003.chii"))
            }
            path == "/settings/privacy" -> respond(signedIn("""<a href="/logout/0a1b2c3d">登出</a>"""), HttpStatusCode.OK)
            path == "/pm/inbox.chii" -> respond(signedIn(inbox), HttpStatusCode.OK)
            conversation != null -> {
                conversationReads.incrementAndGet()
                val page = conversations[conversation]
                val held = hold
                if (held != null) {
                    hold = null
                    held.stalled.complete(Unit)
                    held.release.await()
                }
                if (page != null) respond(signedIn(page), HttpStatusCode.OK) else respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/pm"))
            }
            erase != null -> {
                eraseRequests.incrementAndGet()
                onErase(erase)
                eraseResponse(erase)
                eraseResponses.incrementAndGet()
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/pm"))
            }
            path == "/json/notify" -> respond(PmSamples.read("notify.json"), HttpStatusCode.OK)
            else -> error("意外请求：${request.url}")
        }
    }
}
