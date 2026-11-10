package io.github.touhougo.chogumi.feature.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.cancelAndAwait
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.netroute.LocalNetworkAccess
import io.github.touhougo.chogumi.core.netroute.ProxyServer
import io.github.touhougo.chogumi.core.netroute.ProxyType
import io.github.touhougo.chogumi.core.netroute.RouteMode
import io.github.touhougo.chogumi.core.netroute.testNetRoute
import io.github.touhougo.chogumi.core.netroute.testRouteCheck
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** 「网络」页的 ViewModel 接真的 [io.github.touhougo.chogumi.core.netroute.NetRoute]（夹具：不起核心、试连可控）。 */
class NetworkSettingsViewModelTest {
    private val main = TestMain()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val viewModels = mutableListOf<NetworkSettingsViewModel>()
    private val lan = ProxyServer(ProxyType.SOCKS5, "192.168.1.5", 1080)

    @BeforeTest
    fun setUp() = main.install()

    @AfterTest
    fun tearDown() {
        val scopes = viewModels.map { it.viewModelScope } + scope
        cancelAndAwait(scopes)
        main.uninstall(scopes)
    }

    @Test
    fun `冷启动时存下的局域网代理缺权限_状态是缺权限且连接能点`() = runBlocking {
        val store = PreferenceDataStoreFactory.create(scope = scope) { Files.createTempDirectory("netroute").toFile().resolve("netroute.preferences_pb") }
        store.edit {
            it[stringPreferencesKey("route_mode")] = RouteMode.PROXY.name
            it[stringPreferencesKey("proxy_type")] = lan.type.name
            it[stringPreferencesKey("proxy_host")] = lan.host
            it[intPreferencesKey("proxy_port")] = lan.port
        }
        val route = testNetRoute(store, scope) { Outcome.Success(Unit) }
        scope.launch { route.run() }
        // 连上过之后，用户在系统设置里收回了局域网权限
        val revoked = LocalNetworkAccess { true }
        val viewModel = withContext(Dispatchers.Main) { NetworkSettingsViewModel(route, testRouteCheck(), revoked).also { viewModels += it } }

        awaitUntil("用上存下的那一组") { viewModel.uiState.value.active == lan && viewModel.uiState.value.draft.server == lan }

        assertEquals(ProxyStatus.NO_LOCAL_NETWORK, viewModel.uiState.value.proxyStatus, "不能说已连上：请求都会失败")
        assertTrue(viewModel.uiState.value.canConnect, "要能点「连接」走权限流程")
    }

    @Test
    fun `冷启动时存下的局域网代理有权限_照常是已连上`() = runBlocking {
        val store = PreferenceDataStoreFactory.create(scope = scope) { Files.createTempDirectory("netroute").toFile().resolve("netroute.preferences_pb") }
        store.edit {
            it[stringPreferencesKey("route_mode")] = RouteMode.PROXY.name
            it[stringPreferencesKey("proxy_type")] = lan.type.name
            it[stringPreferencesKey("proxy_host")] = lan.host
            it[intPreferencesKey("proxy_port")] = lan.port
        }
        val route = testNetRoute(store, scope) { Outcome.Success(Unit) }
        scope.launch { route.run() }
        val granted = LocalNetworkAccess { false }
        val viewModel = withContext(Dispatchers.Main) { NetworkSettingsViewModel(route, testRouteCheck(), granted).also { viewModels += it } }

        awaitUntil("用上存下的那一组") { viewModel.uiState.value.active == lan && viewModel.uiState.value.draft.server == lan }

        assertEquals(ProxyStatus.CONNECTED, viewModel.uiState.value.proxyStatus)
    }
}
