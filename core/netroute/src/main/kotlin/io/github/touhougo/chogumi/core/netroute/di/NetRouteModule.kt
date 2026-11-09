package io.github.touhougo.chogumi.core.netroute.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.touhougo.chogumi.core.netroute.BuiltinEdgeAddresses
import io.github.touhougo.chogumi.core.netroute.DirectRouteHint
import io.github.touhougo.chogumi.core.netroute.DirectRouting
import io.github.touhougo.chogumi.core.netroute.EdgeAddresses
import io.github.touhougo.chogumi.core.netroute.GoDirectCore
import io.github.touhougo.chogumi.core.netroute.NetRoute
import io.github.touhougo.chogumi.core.netroute.directRouteSupported
import io.github.touhougo.chogumi.core.network.OkHttpCustomizer
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** 网络线路：设置与直连核心（[NetRoute]）、给网络层的调整（[DirectRouting]）、给登录页的提示条件（[DirectRouteHint]）。 */
val netRouteModule = module {
    single<EdgeAddresses> { BuiltinEdgeAddresses }
    single {
        NetRoute(
            PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("netroute") },
            get(named("appScope")),
            get(),
            directRouteSupported,
            GoDirectCore,
        )
    }
    single<OkHttpCustomizer> {
        val route = get<NetRoute>()
        DirectRouting { route.route() }
    }
    single<DirectRouteHint> {
        val route = get<NetRoute>()
        DirectRouteHint { route.directAvailable }
    }
}
