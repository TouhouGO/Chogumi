package io.github.touhougo.chogumi

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import io.github.touhougo.chogumi.core.common.debug.CrashRecorder
import io.github.touhougo.chogumi.core.data.di.dataModule
import io.github.touhougo.chogumi.core.data.repository.AuthRepository
import io.github.touhougo.chogumi.core.data.repository.SearchRepository
import io.github.touhougo.chogumi.core.data.update.AppUpdates
import io.github.touhougo.chogumi.core.netroute.NetRoute
import io.github.touhougo.chogumi.core.netroute.di.netRouteModule
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.network.OkHttpCustomizer
import io.github.touhougo.chogumi.core.network.customizedBy
import io.github.touhougo.chogumi.core.network.networkModule
import io.github.touhougo.chogumi.core.network.userAgent
import io.github.touhougo.chogumi.core.ui.image.CoverSizeInterceptor
import io.github.touhougo.chogumi.di.appModule
import io.github.touhougo.chogumi.feature.calendar.di.calendarModule
import io.github.touhougo.chogumi.feature.community.di.communityModule
import io.github.touhougo.chogumi.feature.explore.di.exploreModule
import io.github.touhougo.chogumi.feature.home.di.homeModule
import io.github.touhougo.chogumi.feature.home.widget.WidgetUpdates
import io.github.touhougo.chogumi.feature.login.di.loginModule
import io.github.touhougo.chogumi.feature.messages.di.messagesModule
import io.github.touhougo.chogumi.feature.monodetails.di.monoDetailsModule
import io.github.touhougo.chogumi.feature.profile.di.profileModule
import io.github.touhougo.chogumi.feature.progress.di.progressModule
import io.github.touhougo.chogumi.feature.settings.di.settingsModule
import io.github.touhougo.chogumi.feature.subjectdetails.di.subjectDetailsModule
import io.github.touhougo.chogumi.feature.timeline.di.timelineModule
import io.github.touhougo.chogumi.shell.resetWebViewDataOnAccountDeparture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.android.ext.android.get
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named

class App : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        // 调试包先装崩溃记录器：系统写不出崩溃报告时（见 CrashRecorder），设置页调试区仍能看到上次崩溃
        if (BuildConfig.DEBUG) CrashRecorder.install(this)

        // AuthRepository（dataModule）与网络层共用同一份 OAuth 配置
        val authConfig = AuthConfig(
            clientId = BuildConfig.CLIENT_ID,
            clientSecret = BuildConfig.CLIENT_SECRET,
            redirectUri = BuildConfig.OAUTH_REDIRECT_URI,
        )

        startKoin {
            androidContext(this@App)
            modules(
                appModule(authConfig),
                networkModule(appVersion = BuildConfig.VERSION_NAME, enableLogging = BuildConfig.DEBUG, authConfig = authConfig),
                netRouteModule,
                dataModule,
                homeModule,
                exploreModule,
                progressModule,
                timelineModule,
                communityModule,
                profileModule,
                subjectDetailsModule,
                monoDetailsModule,
                messagesModule,
                calendarModule,
                settingsModule,
                loginModule,
            )
        }
        // onCreate 的主线程上不取任何单例：AuthRepository（三个 HTTP 客户端、DataStore）与 SearchRepository（Room 库）
        // 都在后台取；外壳 ViewModel 随后取同一份单例，Koin 的单例按定义加锁创建，只会等、不会建两份。
        val appScope = get<CoroutineScope>(named("appScope"))
        // 网络线路跟着设置起停直连核心；读到设置之前，发往 bgm.tv 的请求在网络层等它（NetRoute.endpoint）
        appScope.launch {
            get<NetRoute>().run()
        }
        appScope.launch {
            resetWebViewDataOnAccountDeparture(get<AuthRepository>().sessionState, appScope)
        }
        appScope.launch {
            get<SearchRepository>().maintainRecentSearches(appScope)
        }
        // 到前台时按间隔检查更新（冷启动也算一次到前台）
        appScope.launch {
            get<AppUpdates>().watch(get(named("appForeground")))
        }
        // 桌面上放着小组件时，App 里标的进度、换的配色马上画到桌面上
        appScope.launch {
            get<WidgetUpdates>().startIfPlaced()
        }
    }

    /**
     * 图片加载：缓存用 Coil 默认值（内存约 1/4、磁盘上限 250MB），网络是 coil-network-okhttp，OkHttp 客户端套上与接口客户端
     * 同样的调整（[OkHttpCustomizer]：网络线路）。
     * 额外注册：封面按画出来的宽取图床的缩放档（[CoverSizeInterceptor]，不为一张小海报下载、解码原图）；
     * 发往 `api.bgm.tv` 的头像请求带 App 的 User-Agent（[ApiImageUserAgent]）；动图解码（Bangumi 表情多是 GIF，不注册的话只显示第一帧）。
     */
    override fun newImageLoader(context: PlatformContext) =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { OkHttpClient.Builder().customizedBy(getKoin().getAll<OkHttpCustomizer>()).build() }))
                add(CoverSizeInterceptor)
                add(ApiImageUserAgent(userAgent(BuildConfig.VERSION_NAME)))
                add(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AnimatedImageDecoder.Factory() else GifDecoder.Factory())
                // 帖子里的 SVG 图（[img]…svg[/img]，如应用商店徽章）：网页照常显示，这里也要能解码
                add(SvgDecoder.Factory())
            }
            .crossfade(true)
            .build()
}
