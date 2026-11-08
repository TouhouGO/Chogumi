package io.github.touhougo.chogumi.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply

/**
 * feature 模块：Android 库 + Compose + Koin，依赖 core:data 与 core:ui。
 *
 * 只收「几乎每个 feature 都要」的部分；个别 feature 独有的依赖（core:reader、
 * 额外的测试库等）留在它自己的脚本里。
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply<AndroidLibraryConventionPlugin>()
            apply<AndroidComposeConventionPlugin>()
            apply<KoinConventionPlugin>()

            val coreData = project(":core:data")
            val coreUi = project(":core:ui")

            dependencies.add("implementation", coreData)
            dependencies.add("implementation", coreUi)
            dependencies.add("implementation", dependencies.platform(libs.library("koin-bom")))
            addLibraries(
                "implementation",
                // koinViewModel 来自这里；koin-compose 随它传递，不另写
                "koin-compose-viewmodel",
                // core:ui 对这项只是 implementation，不向 feature 传递
                "androidx-lifecycle-runtime-compose",
            )

            // VM 测试直接构造真实仓库：MockEngine 冒充服务端（客户端是 core:network 测试夹具的 testApiClient），
            // DataStore 与 core:data 的 FakeDaos 做存储；kotlinx-serialization-json 给测试自己解析请求体
            addLibraries(
                "testImplementation",
                "kotlinx-coroutines-test",
                "ktor-client-mock",
                "kotlinx-serialization-json",
                "androidx-datastore-preferences",
            )
            dependencies.add("testImplementation", dependencies.testFixtures(coreData))

            // 骨架几何等设备测试用 core:ui 的测试夹具
            dependencies.add("androidTestImplementation", dependencies.testFixtures(coreUi))
        }
    }
}
