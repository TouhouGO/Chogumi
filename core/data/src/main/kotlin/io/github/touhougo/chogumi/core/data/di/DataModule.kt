package io.github.touhougo.chogumi.core.data.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.withTransaction
import io.github.touhougo.chogumi.core.data.db.ALL_MIGRATIONS
import io.github.touhougo.chogumi.core.data.db.ChogumiDatabase
import io.github.touhougo.chogumi.core.data.db.cache.LocalCacheJanitor
import io.github.touhougo.chogumi.core.data.image.AvatarImages
import io.github.touhougo.chogumi.core.data.image.DeviceAvatarImages
import io.github.touhougo.chogumi.core.data.mark.CollectionMarkCoordinator
import io.github.touhougo.chogumi.core.data.progress.EpTapCoordinator
import io.github.touhougo.chogumi.core.data.repository.AiringTimeRepository
import io.github.touhougo.chogumi.core.data.repository.AuthRepository
import io.github.touhougo.chogumi.core.data.repository.BlockedUsersFeed
import io.github.touhougo.chogumi.core.data.repository.BlockedUsersSource
import io.github.touhougo.chogumi.core.data.repository.BlocklistRepository
import io.github.touhougo.chogumi.core.data.repository.CalendarRepository
import io.github.touhougo.chogumi.core.data.repository.CollectionEditingImpl
import io.github.touhougo.chogumi.core.data.repository.CollectionObserver
import io.github.touhougo.chogumi.core.data.repository.CollectionStore
import io.github.touhougo.chogumi.core.data.repository.CollectionSync
import io.github.touhougo.chogumi.core.data.repository.CommentRepository
import io.github.touhougo.chogumi.core.data.repository.CommentRepositoryImpl
import io.github.touhougo.chogumi.core.data.repository.CommunityRepository
import io.github.touhougo.chogumi.core.data.repository.CommunityRepositoryImpl
import io.github.touhougo.chogumi.core.data.repository.DataStoreSyncPreferenceStore
import io.github.touhougo.chogumi.core.data.repository.DisplayScaleRepository
import io.github.touhougo.chogumi.core.data.repository.DraftRepository
import io.github.touhougo.chogumi.core.data.repository.ExploreLayoutRepository
import io.github.touhougo.chogumi.core.data.repository.FriendsRepository
import io.github.touhougo.chogumi.core.data.repository.GroupMuteRepository
import io.github.touhougo.chogumi.core.data.repository.GroupRepository
import io.github.touhougo.chogumi.core.data.repository.GroupRepositoryImpl
import io.github.touhougo.chogumi.core.data.repository.HomeRepository
import io.github.touhougo.chogumi.core.data.repository.IndexRepository
import io.github.touhougo.chogumi.core.data.repository.LocalCareerStatistics
import io.github.touhougo.chogumi.core.data.repository.LocalProgressHolds
import io.github.touhougo.chogumi.core.data.repository.MonoRepository
import io.github.touhougo.chogumi.core.data.repository.MonoRepositoryImpl
import io.github.touhougo.chogumi.core.data.repository.NotificationRepository
import io.github.touhougo.chogumi.core.data.repository.NsfwFilter
import io.github.touhougo.chogumi.core.data.repository.NsfwPreferenceRepository
import io.github.touhougo.chogumi.core.data.repository.NsfwPreferenceSynchronizer
import io.github.touhougo.chogumi.core.data.repository.OtherCareerDataSource
import io.github.touhougo.chogumi.core.data.repository.OtherUserCollectionsCache
import io.github.touhougo.chogumi.core.data.repository.PmRepository
import io.github.touhougo.chogumi.core.data.repository.AccountMe
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.ProgressOrchestrator
import io.github.touhougo.chogumi.core.data.repository.SearchRepository
import io.github.touhougo.chogumi.core.data.repository.SmileyAssetStore
import io.github.touhougo.chogumi.core.data.repository.SubjectCommunityRepository
import io.github.touhougo.chogumi.core.data.repository.SubjectCommunityRepositoryImpl
import io.github.touhougo.chogumi.core.data.repository.SubjectRepository
import io.github.touhougo.chogumi.core.data.repository.SyncPreferenceStore
import io.github.touhougo.chogumi.core.data.repository.TagCatalogRepository
import io.github.touhougo.chogumi.core.data.repository.TimelineRepository
import io.github.touhougo.chogumi.core.data.repository.TopicRepository
import io.github.touhougo.chogumi.core.data.repository.TopicRepositoryImpl
import io.github.touhougo.chogumi.core.data.repository.TransactionRunner
import io.github.touhougo.chogumi.core.data.repository.UserRepository
import io.github.touhougo.chogumi.core.data.repository.WatchStateStore
import io.github.touhougo.chogumi.core.data.token.PREFERENCES_DATASTORE_NAME
import io.github.touhougo.chogumi.core.data.token.TOKENS_DATASTORE_NAME
import io.github.touhougo.chogumi.core.data.token.TokenRefresherImpl
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.data.web.CookieCipher
import io.github.touhougo.chogumi.core.data.web.KeystoreCookieCipher
import io.github.touhougo.chogumi.core.data.web.WebSessionRepository
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.CollectionEditing
import io.github.touhougo.chogumi.core.model.CollectionMarker
import io.github.touhougo.chogumi.core.model.UserModeration
import io.github.touhougo.chogumi.core.model.commitClockNanos
import io.github.touhougo.chogumi.core.network.TokenProvider
import io.github.touhougo.chogumi.core.network.TokenRefresher
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/** core:data 的 Koin 模块 */
val dataModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            ChogumiDatabase::class.java,
            "chogumi.db",
        )
            // 条目、收藏与首页进度是可重拉缓存；草稿、屏蔽小组与探索布局是仅存本机的用户数据。
            // 装回旧版本时按既定策略整库重建，本机用户数据也会丢失；不为降级写迁移。
            // 升级必须写迁移，漏写时 Room 打开即报错，不会悄悄丢数据。
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
    }
    single { AppClock.SYSTEM }
    // 应用级作用域：跟随进程、不随页面取消的后台工作（会话恢复、通知轮询、动态缓存、进度提交、NSFW 同步、缓存清理）。
    single<CoroutineScope>(named("appScope")) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    // 时间线 feed 的唯一调度器：Pager 不是线程安全的，同一时刻只在一条线程上改列表。
    single<CoroutineDispatcher>(named("timelineFeedDispatcher")) { Dispatchers.Default.limitedParallelism(1) }
    single { get<ChogumiDatabase>().userDao() }
    single { get<ChogumiDatabase>().recentSearchDao() }
    single { get<ChogumiDatabase>().searchFirstPageDao() }
    single { get<ChogumiDatabase>().subjectDao() }
    single { get<ChogumiDatabase>().episodeDao() }
    single { get<ChogumiDatabase>().userCollectionDao() }
    single { get<ChogumiDatabase>().calendarDao() }
    single { get<ChogumiDatabase>().episodeWatchStateDao() }
    single { get<ChogumiDatabase>().homeProgressDao() }
    single { get<ChogumiDatabase>().tagCatalogDao() }
    single { get<ChogumiDatabase>().exploreShelfDao() }
    single { get<ChogumiDatabase>().subjectSectionDao() }
    single { get<ChogumiDatabase>().cacheEvictionDao() }
    single { get<ChogumiDatabase>().topicReplyDraftDao() }
    single { get<ChogumiDatabase>().mutedGroupDao() }
    single { get<ChogumiDatabase>().postDraftDao() }
    single { get<ChogumiDatabase>().airingTimeDao() }

    /**
     * 表情资源的按需下载。
     *
     * 落 `filesDir` 而非 `cacheDir`：后者在 Android 语义上是「系统空间紧张时
     * 可随时删除」的，而表情要的是持久 —— 用户遇到一次后就该一直在。
     *
     * 用共享的 `HttpClient` 而非手搓客户端（裸客户端会因缺插件而"因错误的原因通过"，docs/dev/guide.md「与生产同构」）。取原始字节用 `bodyAsBytes()` —— 它属于
     * ktor-client-core，不依赖 content-negotiation 插件，而 `core:data`
     * 只有 core（见 build.gradle.kts 的依赖注释）。
     */
    single {
        SmileyAssetStore(
            baseDir = androidContext().filesDir,
            httpGet = { url ->
                outcomeOf { get<HttpClient>().get(url).bodyAsBytes() }.valueOrNull()
            },
            io = Dispatchers.IO,
        )
    }

    // networkModule 的 createHttpClient 按 TokenProvider / TokenRefresher 接口解析，Koin 不会隐式绑定接口，故显式 bind。
    // 凭证与配置分离：TokenStore 独占 [TOKENS_DATASTORE_NAME]（排除云备份），PreferencesRepository 独占 [PREFERENCES_DATASTORE_NAME]（保留云备份）
    single<DataStores> {
        createDataStores(
            preferencesFile = androidContext().preferencesDataStoreFile(PREFERENCES_DATASTORE_NAME),
            tokensFile = androidContext().preferencesDataStoreFile(TOKENS_DATASTORE_NAME),
        )
    }
    single<DataStore<Preferences>>(named("preferencesDataStore")) {
        get<DataStores>().preferences
    }
    single<DataStore<Preferences>>(named("tokensDataStore")) {
        get<DataStores>().tokens
    }
    single { TokenStore(get(named("tokensDataStore"))) } bind TokenProvider::class
    single { TokenRefresherImpl(get(), get()) } bind TokenRefresher::class

    single<CookieCipher> { KeystoreCookieCipher() }
    single { WebSessionRepository(get(), get(), get()) }
    single { AuthRepository(get(), get(), get(), get(), get(), get(named("appScope"))) }
    single { BlocklistRepository(get(named("preferencesDataStore")), get(), get(), get(), get(), get(named("appScope"))) }
    single<BlockedUsersSource> { get<BlocklistRepository>() }
    single<BlockedUsersFeed> { get<BlocklistRepository>() }
    single { FriendsRepository(get(named("preferencesDataStore")), get(), get(), get(), get(named("appScope"))) }
    single { AccountMe(get(), get()) }
    single { UserRepository(get(), get(), get(), get(), get(), get(), get(), get()) }
    single<AvatarImages> { DeviceAvatarImages(androidContext()) }
    single<UserModeration> { get<UserRepository>() }
    single { SubjectRepository(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(named("computationDispatcher"))) }
    // 收藏同步新鲜度的「上次成功时刻」，落在偏好 DataStore（按账号隔离的键）。
    single<SyncPreferenceStore> { DataStoreSyncPreferenceStore(get(named("preferencesDataStore"))) }
    // 跨表落库（subjects + user_collections）包一个真实 Room 事务。
    single<TransactionRunner> {
        object : TransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T =
                get<ChogumiDatabase>().withTransaction(block)
        }
    }
    // 本人收藏：读（CollectionObserver）、记录写（CollectionStore）、逐集（WatchStateStore）、全量同步（CollectionSync）
    // 与生涯统计（LocalCareerStatistics）；本地写与服务端落库之间的保护（LocalProgressHolds）只有一份，读写两方共用。
    single { LocalProgressHolds() }
    single { CollectionObserver(get(), get(), get(), get(), get(named("computationDispatcher"))) }
    single { CollectionStore(get(), get(), get(), get(), get(), get(), get(), get(), get(named("computationDispatcher")), get(), get()) }
    single { WatchStateStore(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { CollectionSync(get(), get(), get(), get(), get(), get(), get(), get(), lineupSource = get<SubjectRepository>()) }
    single {
        LocalCareerStatistics(get(), get(), get(), lineupSource = get<SubjectRepository>(), computationDispatcher = get(named("computationDispatcher")))
    }
    // 他人公开收藏的会话内 LRU 缓存（最多 3 个用户，绝不落自己的收藏表）。
    // 统计计算与本人共用同一个调度器。
    // 生涯页按 [OtherCareerDataSource] 切面解析（单测只 fake 切面），故显式 bind。
    single { OtherUserCollectionsCache(get(), get(), get(), get(), get(named("computationDispatcher"))) } bind OtherCareerDataSource::class
    single { ProgressOrchestrator(get(), get()) }
    single<CollectionEditing> { CollectionEditingImpl(get(), get(), get(), get(), get(), get()) }
    single {
        EpTapCoordinator(get(), get(), get(), get(), get(), get(), get(named("appScope")), ::commitClockNanos, ::delayUntilCommit)
    }
    single<CollectionMarker> {
        CollectionMarkCoordinator(get(), get(), get(), get(), get(named("appScope")), ::commitClockNanos, ::delayUntilCommit)
    }
    // 首页进度区块：/p1/home 慢且大，必须走缓存优先；收藏与逐集随同一拍写进本地。
    single { HomeRepository(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(named("computationDispatcher"))) }
    single { PreferencesRepository(get(named("preferencesDataStore"))) }
    // 界面大小：Activity 在第一帧之前同步读，所以不在偏好 DataStore 里（见 DisplayScaleRepository）。
    single { DisplayScaleRepository(androidContext()) }
    // NSFW 偏好缓存：真源是 bgm.tv 账号设置（p1/privacy），本地只按 userId 分区存副本。
    single { NsfwPreferenceRepository(get(named("preferencesDataStore")), get(), get(), get()) }
    // 唯一刷新观察点：观察会话 authenticated 位，覆盖「启动恢复」与「新登录」两条路径。
    single { NsfwPreferenceSynchronizer(get(), get(), get(named("appScope"))) }
    // 启动时后台清理一次本地缓存（条目表、详情子模块）。
    single { LocalCacheJanitor(get(), get(), get(), get(), get(), get(named("appScope"))) }
    // ⚠️ 上面两个是「注册即生效」的副作用组件，没有调用方会 get() 它们，懒加载的 single 不主动建就永不执行
    // （表现为登录后 NSFW 开关永不同步、缓存表只增不减，且不报任何错）。启动时由这一行在后台建好，
    // 不在 startKoin 所在的主线程上连带建 DataStore、HTTP 客户端与数据库。见 BackgroundStartup。
    single(createdAtStart = true) { BackgroundStartup(get(named("appScope")), listOf(inject<NsfwPreferenceSynchronizer>(), inject<LocalCacheJanitor>())) }
    // 唯一过滤组件：NSFW 条目剔除只此一处，各仓库只调用它（不得各写各的 filter）。
    single { NsfwFilter(get()) }
    // 搜索页、探索页的条目检索。
    single { SearchRepository(get(), get(), get(), get(), get(), get(), get(), get(), get(named("computationDispatcher")), get(named("preferencesDataStore"))) }
    // 每日放送。
    single { CalendarRepository(get(), get(), get(), get(), get(), get(), get(), get(named("computationDispatcher"))) }
    single { AiringTimeRepository(get(), get(), get(), get(named("computationDispatcher"))) }
    // 标签目录缓存：每个类型前 5000 个标签，供筛选面板的搜索框。
    // 过滤在仓库内复用唯一过滤组件 NsfwFilter，此处不重复注入开关。
    single { TagCatalogRepository(get(), get(), get(), get()) }
    // 探索页的货架布局（每个分类一份，搜索页图钉也写在这里）。
    single { ExploreLayoutRepository(get()) }
    single { TimelineRepository(get(), get(), get(), get(), get(), get(named("appScope")), get(named("timelineFeedDispatcher")), get()) }
    single<MonoRepository> { MonoRepositoryImpl(get(), get(), get(), get()) }
    single<SubjectCommunityRepository> { SubjectCommunityRepositoryImpl(get(), get()) }
    single<CommunityRepository> { CommunityRepositoryImpl(get(), get(), get()) }
    single<TopicRepository> { TopicRepositoryImpl(get(), get(), get(), get()) }
    single<CommentRepository> { CommentRepositoryImpl(get(), get(), get(), get(), get()) }
    single<GroupRepository> { GroupRepositoryImpl(get(), get(), get(named("preferencesDataStore")), get()) }
    single { DraftRepository(get(), get(), get<TokenStore>(), get(named("appScope"))) }
    single { GroupMuteRepository(get(), get<TokenStore>()) }
    single { IndexRepository(get(), get(), get()) }
    single { NotificationRepository(get(), get(), get(), get(), get(named("appScope"))) }
    single { PmRepository(get(), get(), get(), get(named("appScope")), get(), get(named("computationDispatcher"))) }
}

/** 两个延时提交的协调器共用的等待：睡到 [commitAt]（[commitClockNanos] 的读数）为止。 */
private suspend fun delayUntilCommit(commitAt: Long) = delay(TimeUnit.NANOSECONDS.toMillis(commitAt - commitClockNanos()))
