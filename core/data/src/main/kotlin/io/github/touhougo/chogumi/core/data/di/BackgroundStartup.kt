package io.github.touhougo.chogumi.core.data.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 「注册即生效」的副作用组件（NSFW 偏好同步、启动时的缓存清理）在后台建好——启动时唯一的入口。
 *
 * 这些组件没有调用方会 `get()`，而 Koin 的 `single` 是懒加载的：不主动建就永不执行。
 * 它们若直接以 `createdAtStart` 注册，就在 `startKoin` 里、也就是 `Application.onCreate` 的主线程上，
 * 连带建好 DataStore、HTTP 客户端与 Room 数据库；`App.onCreate` 同理，主线程上不取单例，要在启动时用的一律在 `appScope` 里取。本类以 `createdAtStart` 注册、构造时只在 [scope]（后台）上
 * 逐个取一次 [components]，那些依赖因此在后台线程上建。
 *
 * 新的副作用组件加进 DataModule 里本类那一行的 [components]，不另写 `createdAtStart`
 * （守卫：`NsfwPreferenceSynchronizerWiringTest`）。
 */
class BackgroundStartup(scope: CoroutineScope, private val components: List<Lazy<*>>) {
    init {
        scope.launch { components.forEach { it.value } }
    }
}
