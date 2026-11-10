package io.github.touhougo.chogumi.feature.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.RecordedMockEngines
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.update.AppUpdates
import io.github.touhougo.chogumi.core.foundation.testing.TestClock
import io.github.touhougo.chogumi.core.network.api.ReleaseApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** 设置 › 更新的「立即检查」：查完写「已是最新」；正在查时又要查（查的时候打开了预发布通道），这一轮结束后再查一次。 */
class UpdateSettingsViewModelTest {
    @get:org.junit.Rule
    val resources = TestResources()

    private val main = TestMain()
    private val viewModels = mutableListOf<UpdateSettingsViewModel>()

    @BeforeTest
    fun setUp() = main.install()

    @AfterTest
    fun tearDown() = main.uninstall(viewModels.map { it.viewModelScope })

    private val engines = RecordedMockEngines()
    private val latestUrl = "https://github.com/TouhouGO/Chogumi/releases/latest/download/release.json"

    /** 装着的就是 0.2.0：查到的都是「已是最新」。 */
    private val manifest = """{"versionName": "0.2.0", "versionCode": 200, "sha256": {"chogumi-0.2.0-200.apk": "${"0".repeat(64)}"}}"""

    private val prefs = PreferencesRepository(
        PreferenceDataStoreFactory.create(scope = resources.scope) { File(resources.directory("prefs"), "p.preferences_pb") },
    )

    /** 最新正式版的 release.json 等 [latestGate] 放行才回。 */
    private fun engine(latestGate: CompletableDeferred<Unit>) = engines.create { request ->
        val octet = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
        when (request.url.toString()) {
            latestUrl -> {
                latestGate.await()
                respond(manifest, HttpStatusCode.OK, octet)
            }
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private fun viewModel(engine: MockEngine): UpdateSettingsViewModel {
        val updates = AppUpdates(
            api = ReleaseApi(resources.apiClient(engine)),
            preferences = prefs,
            clock = TestClock(0L),
            installedVersionCode = 200,
            dir = File(resources.directory("updates"), "updates"),
            scope = resources.scope,
            io = Dispatchers.IO,
        )
        return UpdateSettingsViewModel(prefs, updates).also(viewModels::add)
    }

    private fun requestUrls(engine: MockEngine) = engines.requests(engine).map { it.url.toString() }

    @Test
    fun `立即检查查完已是最新`() = runBlocking {
        val engine = engine(CompletableDeferred(Unit))
        val viewModel = viewModel(engine)

        withContext(main.dispatcher) { viewModel.checkNow() }

        awaitUntil("已是最新") { viewModel.uiState.value.manualCheck == ManualUpdateCheck.UP_TO_DATE }
        assertEquals(listOf(latestUrl), requestUrls(engine))
    }

    @Test
    fun `正在查时又要查_这一轮结束后再查一次`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val engine = engine(gate)
        val viewModel = viewModel(engine)

        withContext(main.dispatcher) { viewModel.checkNow() }
        awaitUntil("第一次的请求已发出") { latestUrl in requestUrls(engine) }
        withContext(main.dispatcher) { viewModel.checkNow() }
        gate.complete(Unit)

        awaitUntil("又查了一次") { requestUrls(engine).size == 2 }
        awaitUntil("已是最新") { viewModel.uiState.value.manualCheck == ManualUpdateCheck.UP_TO_DATE }
        assertEquals(listOf(latestUrl, latestUrl), requestUrls(engine))
    }
}
