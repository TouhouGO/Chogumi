package io.github.touhougo.chogumi.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.netroute.CheckSite
import io.github.touhougo.chogumi.core.netroute.ProxyServer
import io.github.touhougo.chogumi.core.netroute.ProxyType
import io.github.touhougo.chogumi.core.netroute.RouteMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 「网络」页：直连只在支持的设备上有；代理打开才出现输入；「连接」与状态行按草稿和正在用的那一组；检查连接的四格在原位填上。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class NetworkSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    private val server = ProxyServer(ProxyType.SOCKS5, "127.0.0.1", 1080)
    private val draft = ProxyDraft(ProxyType.SOCKS5, "127.0.0.1", "1080")

    private class RecordingActions : NetworkSettingsActions {
        val proxy = mutableListOf<Boolean>()
        var connects = 0
        override fun onDirectChange(value: Boolean) = Unit
        override fun onProxyChange(value: Boolean) {
            proxy += value
        }
        override fun onProxyType(type: ProxyType) = Unit
        override fun onProxyHost(text: String) = Unit
        override fun onProxyPort(text: String) = Unit
        override fun onProxyConnect() {
            connects++
        }
        override fun onCheck() = Unit
    }

    private fun show(state: NetworkSettingsUiState, actions: NetworkSettingsActions = RecordingActions()) {
        compose.setContent { MaterialTheme { NetworkSettingsContent(state, actions, FailureNotices(), onBack = {}) } }
    }

    @Test
    fun `代理关着时只有开关_打开后才有类型地址端口与连接`() {
        var state by mutableStateOf(NetworkSettingsUiState(directSupported = true))
        compose.setContent { MaterialTheme { NetworkSettingsContent(state, RecordingActions(), FailureNotices(), onBack = {}) } }
        compose.onNodeWithTag(ProxySwitchTag).assertExists()
        compose.onNodeWithText("地址").assertDoesNotExist()

        state = state.copy(mode = RouteMode.PROXY)

        compose.onNodeWithText("类型").assertExists()
        compose.onNodeWithText("地址").assertExists()
        compose.onNodeWithText("端口").assertExists()
        compose.onNodeWithTag(SettingsListTag).performScrollToNode(hasText("填好地址和端口后点「连接」"))
    }

    @Test
    fun `设备不支持直连时没有直连那一块_代理照常`() {
        show(NetworkSettingsUiState(directSupported = false))

        compose.onNodeWithTag(DirectSwitchTag).assertDoesNotExist()
        compose.onNodeWithTag(ProxySwitchTag).assertExists()
    }

    @Test
    fun `点代理开关交给 ViewModel 打开`() {
        val actions = RecordingActions()
        show(NetworkSettingsUiState(directSupported = true), actions)

        compose.onNodeWithTag(ProxySwitchTag).performClick()

        assertEquals(listOf(true), actions.proxy)
    }

    @Test
    fun `正在用的那一组不能再点连接_改了输入才能点`() {
        val connected = NetworkSettingsUiState(mode = RouteMode.PROXY, draft = draft, active = server)
        assertFalse(connected.canConnect)
        assertEquals(ProxyStatus.CONNECTED, connected.proxyStatus)

        val changed = connected.copy(draft = draft.copy(port = "7890"))
        assertTrue(changed.canConnect)
        assertEquals(ProxyStatus.CHANGED, changed.proxyStatus)

        assertFalse("在连的时候不能再点", changed.copy(connecting = ProxyConnecting.BUSY).canConnect)
        assertFalse("没填好不能点", connected.copy(draft = draft.copy(port = "")).canConnect)
        assertTrue("连不上之后同一组可以再试", connected.copy(connecting = ProxyConnecting.FAILED).canConnect)
    }

    @Test
    fun `连接按钮跟着能不能点`() {
        val actions = RecordingActions()
        var state by mutableStateOf(NetworkSettingsUiState(mode = RouteMode.PROXY, draft = draft))
        compose.setContent { MaterialTheme { NetworkSettingsContent(state, actions, FailureNotices(), onBack = {}) } }
        compose.onNodeWithTag(SettingsListTag).performScrollToNode(hasText("连接"))
        compose.onNodeWithText("连接").assertIsEnabled().performClick()
        assertEquals(1, actions.connects)

        state = state.copy(active = server)

        compose.onNodeWithText("已连上").assertExists()
        compose.onNodeWithText("连接").assertIsNotEnabled()
    }

    @Test
    fun `检查连接的四格_没测是横线_测完是毫秒数或连不上`() {
        var state by mutableStateOf(NetworkSettingsUiState(directSupported = true))
        compose.setContent { MaterialTheme { NetworkSettingsContent(state, RecordingActions(), FailureNotices(), onBack = {}) } }
        compose.onNodeWithTag(SettingsListTag).performScrollToNode(hasText("lain.bgm.tv"))
        compose.onNodeWithText("用现在的线路（系统网络）连一次 bgm.tv 的几个站点").assertExists()
        compose.onNodeWithText("开始").assertExists()

        state = state.copy(
            check = mapOf(
                CheckSite.WEB to CheckResult.Millis(312),
                CheckSite.API to CheckResult.Unreachable,
                CheckSite.NEXT to CheckResult.Millis(48),
                CheckSite.IMAGES to CheckResult.Millis(1020),
            ),
        )

        compose.onNodeWithText("312 ms").assertExists()
        compose.onNodeWithText("连不上").assertExists()
        compose.onNodeWithText("再测一次").assertExists()
    }
}
