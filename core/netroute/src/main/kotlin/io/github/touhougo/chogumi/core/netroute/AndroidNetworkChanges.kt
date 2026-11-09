package io.github.touhougo.chogumi.core.netroute

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/** 系统的默认网络回调：默认网络换了（连上新的 Wi-Fi、切到移动网络）就调一次。 */
internal class AndroidNetworkChanges(private val context: Context) : NetworkChanges {
    override fun observe(onChange: () -> Unit) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange()
        })
    }
}
