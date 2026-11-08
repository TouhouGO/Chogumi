package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [NsfwPreferenceSynchronizer] 与 [io.github.touhougo.chogumi.core.data.db.cache.LocalCacheJanitor] 接线方式的**可执行守卫**。
 *
 * ## 为什么需要它
 *
 * 两者是"注册即生效"的副作用组件：**没有任何调用方会 `get()` 它们**。而 Koin 的
 * `single` 是懒加载的 —— 没人主动建，`init` 永不执行，观察永不开始。症状是"登录后 NSFW 开关永远不同步"
 * 「缓存表只增不减」，且**不报任何错**：编译绿、单测绿（单测直接 new 它，绕过 DI），只有实机才看得出来。
 *
 * 启动时由 [io.github.touhougo.chogumi.core.data.di.BackgroundStartup] 在后台建它们（不在 `startKoin` 的主线程上连带建
 * DataStore、HTTP 客户端与数据库）。故这里扫描 DataModule 源码，钉死两件事：`BackgroundStartup` 以
 * `createdAtStart = true` 注册，且列上了每个副作用组件；副作用组件自己不再 `createdAtStart`（那会回到主线程上建）。
 * 行为测试（`NsfwPreferenceSynchronizerTest`）覆盖不到这一层，因为它直接构造实例。
 *
 * 与 `FeatureDependencyContractTest` 同为源码级契约守卫：不依赖运行时 DI 容器，
 * 无需在 core:data 测试里引入 Koin。
 */
class NsfwPreferenceSynchronizerWiringTest {

    @Test
    fun `观察点必须由启动入口在后台建好_否则懒加载永不构造`() =
        assertStartedInBackground("NsfwPreferenceSynchronizer", "表现为登录后 NSFW 开关永不同步且不报错。")

    @Test
    fun `缓存清理必须由启动入口在后台建好_否则懒加载永不构造`() =
        assertStartedInBackground("LocalCacheJanitor", "表现为本地缓存表只增不减且不报错。")

    @Test
    fun `只有启动入口在 startKoin 时建_其余都是懒加载`() {
        val eager = RepoFiles.read(DATA_MODULE).lineSequence().filter { it.contains("createdAtStart") }.map { it.trim() }.toList()
        assertEquals(1, eager.size, "DataModule 里只有 BackgroundStartup 一行可以 createdAtStart：\n${eager.joinToString("\n")}")
        assertTrue(eager.single().contains("BackgroundStartup("), "createdAtStart 的那一行必须是 BackgroundStartup：${eager.single()}")
    }

    /** 组件有自己的懒加载注册，并列在启动入口那一行里。 */
    private fun assertStartedInBackground(component: String, symptom: String) {
        val lines = RepoFiles.read(DATA_MODULE).lineSequence().toList()

        lines.firstOrNull { it.contains("$component(") }
            ?: fail("在 $DATA_MODULE 里找不到 $component 的注册 —— 组件未被接入 DI 图")

        val startup = lines.firstOrNull { it.contains("BackgroundStartup(") }
            ?: fail("在 $DATA_MODULE 里找不到 BackgroundStartup 的注册 —— 副作用组件没人建，$symptom")
        assertTrue(
            startup.contains("createdAtStart = true"),
            "BackgroundStartup 必须用 createdAtStart = true 注册：\n  实际：${startup.trim()}",
        )
        assertTrue(
            startup.contains("inject<$component>()"),
            "$component 必须列在 BackgroundStartup 那一行里：\n" +
                "  实际：${startup.trim()}\n" +
                "  它没有任何调用方会 get() 它，懒加载 single 会让 init 永不执行，$symptom",
        )
    }

    private companion object {
        const val DATA_MODULE = "core/data/src/main/kotlin/io/github/touhougo/chogumi/core/data/di/DataModule.kt"
    }
}
