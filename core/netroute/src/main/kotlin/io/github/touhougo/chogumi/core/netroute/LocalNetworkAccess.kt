package io.github.touhougo.chogumi.core.netroute

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Android 17 起连局域网地址要的运行时权限（属于「附近的设备」权限组）；没有它时连局域网的 TCP 连接会超时。 */
const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

/** 连 [ProxyServer] 之前还缺不缺局域网权限：只在 Android 17 起、这一组可能在局域网（[needsLocalNetwork]）、还没给权限时缺。 */
fun interface LocalNetworkAccess {
    fun missing(server: ProxyServer): Boolean
}

internal class AndroidLocalNetworkAccess(private val context: Context) : LocalNetworkAccess {
    override fun missing(server: ProxyServer): Boolean = Build.VERSION.SDK_INT >= LOCAL_NETWORK_SDK && server.needsLocalNetwork() &&
        ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED
}

/** Android 17 的 API 级别：局域网权限从这一版起对 targetSdk 37 的应用生效。 */
private const val LOCAL_NETWORK_SDK = 37
