package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.touhougo.chogumi.core.network.api.PrivacyApi
import io.github.touhougo.chogumi.core.network.testApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * 测试用 [NsfwFilter]（唯一实现，供 core:data 与各 feature 模块的测试共用）。
 *
 * ## 不是"假实现"，是带假服务端的真实现
 *
 * 走的是真实的 `TokenStore → NsfwPreferenceRepository → PrivacyApi → MockEngine`，
 * 开关值经 `refresh()` 从（假的）`/p1/privacy` 响应写进缓存，再被 [NsfwFilter] 读出来。
 * 这样缓存键名、分区规则、未登录恒 false 等行为都由生产代码本身保证，
 * 测试里不存在第二份键名清单。
 *
 * 夹具始终建立人造已登录会话，[showNsfw] 控制该账号的服务端开关（默认关闭）。
 * 未登录行为由专门的用例覆盖，不能把夹具默认关闭当作访客证据。
 * DataStore、客户端与临时文件都由调用方的 [TestResources] 释放。
 */
fun testNsfwFilter(resources: TestResources, showNsfw: Boolean = false): NsfwFilter = TestNsfwFixture(resources, showNsfw).nsfwFilter

/**
 * 可在测试中途切换开关值的 [NsfwFilter] 包装：用于验证
 * 「命中会话缓存后切换开关依然正确」（过滤在发出时读当前值）。
 *
 * 仍是真实 DataStore + [NsfwFilter]，[set] 改变假服务端的响应后重新 `refresh()`，
 * 与用户在 bgm.tv 改设置、客户端下次刷新读到新值的过程一致。
 */
class MutableTestNsfwFilter(resources: TestResources) {
    private val fixture = TestNsfwFixture(resources, false)

    /** 交出去给生产代码的过滤组件（构造后不换实例，换的是它读到的开关值）。 */
    val nsfwFilter: NsfwFilter = fixture.nsfwFilter

    fun set(showNsfw: Boolean) = fixture.set(showNsfw)
}

/**
 * 夹具实现：组装真实仓库链，用 MockEngine 冒充 `/p1/privacy`。
 *
 * 初始开关与 [set] 都经同一个测试账号的真实仓库读写，不改变账号身份。
 */
private class TestNsfwFixture(resources: TestResources, initialAllowNsfw: Boolean) {

    /** 假服务端当前返回的 allowNsfw；[set] 改它再 refresh，模拟账号设置变化。 */
    @Volatile
    private var allowNsfw: Boolean = initialAllowNsfw

    private val dir = resources.directory("nsfw-test")
    private val scope = resources.scope

    private val engine = MockEngine {
        respond(
            content = privacyJson(allowNsfw = allowNsfw, showNsfwSubject = allowNsfw),
            status = HttpStatusCode.OK,
            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
        )
    }
    private val client = resources.own(testApiClient(engine))
    private val tokenStore = testTokenStore(resources)
    private val repository = NsfwPreferenceRepository(
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "prefs.preferences_pb") },
        privacyApi = PrivacyApi(client),
        tokenStore = tokenStore,
        webSessions = testWebSessionRepository(tokenStore, resources.own(noWebClient())),
    )

    val nsfwFilter: NsfwFilter = NsfwFilter(repository)

    init {
        // 只有登录账号才有设置分区；先登录，再 refresh 将初始开关写入该账号缓存。
        runBlocking {
            val session = tokenStore.beginSession("test-token")
            tokenStore.completeSession(session, userId = TEST_USER_ID, username = "test_user")
            repository.refresh()
        }
    }

    fun set(showNsfw: Boolean) = runBlocking {
        allowNsfw = showNsfw
        repository.refresh()
    }

    private companion object {
        const val TEST_USER_ID = 99999
    }
}

/** `/p1/privacy` 的最小合法响应体（settings 字段按 DTO 必需项补齐）。 */
private fun privacyJson(allowNsfw: Boolean, showNsfwSubject: Boolean): String = """
    {
      "settings": {
        "privateMessage": "all", "timelineReply": "all", "timelineCollectReply": "all",
        "follow": "all", "mentionNotification": "all", "commentNotification": "all",
        "friendNotification": "all"
      },
      "preferences": {
        "showNsfwSubject": $showNsfwSubject,
        "canSetNsfwSubject": true,
        "allowNsfw": $allowNsfw
      }
    }
""".trimIndent()
