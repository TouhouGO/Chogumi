package io.github.touhougo.chogumi.gradle

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.gradle.process.CommandLineArgumentProvider

/**
 * 宿主单测跑在 Robolectric 上：带上真实资源（字符串、主题、Room / SQLite 都要）并加依赖。
 * 模块用了 Compose（`chogumi.android.compose`，先后顺序不限）时再加 Compose 测试库，没用时只有 Robolectric。
 * 叠加在 `chogumi.android.library` / `chogumi.android.application` 之上。
 *
 * Robolectric 要的 Android 系统包（`android-all-instrumented`）由 Gradle 像别的依赖一样解析：
 * 进 Gradle 缓存（CI 随 Actions 缓存走），走 settings 里的仓库，测试只从那里读（`robolectric.dependency.dir`）。
 * 不交给 Robolectric 自己在跑测试时去 Maven Central 下载：那份下载不进任何缓存、不重试，
 * 被限流（HTTP 429）时整个测试类在 `classMethod` 上失败，看起来像测试红了。
 */
class AndroidRobolectricConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        extensions.configure<CommonExtension> {
            testOptions.unitTests.isIncludeAndroidResources = true
        }
        addLibraries("testImplementation", "robolectric")
        val androidAll = configurations.create("robolectricAndroidAll") {
            isCanBeConsumed = false
            isTransitive = false
        }
        addLibraries(androidAll.name, "robolectric-android-all")
        tasks.withType<Test>().configureEach {
            jvmArgumentProviders += RobolectricSdkJar(androidAll)
        }
        pluginManager.withPlugin(COMPOSE_PLUGIN_ID) {
            addComposeBom("testImplementation")
            addLibraries("testImplementation", "androidx-test-ext-junit", "androidx-compose-ui-test-junit4")
        }
    }
}

/**
 * 把系统包所在的目录交给 Robolectric。Gradle 缓存里的文件名就是 Robolectric 找的
 * `<artifactId>-<version>.jar`，所以目录直接用它所在的那一层。jar 的内容是测试的输入，所在路径不是。
 * 只有一份（一个 SDK 级别）：测试要别的级别时 Robolectric 报这个目录里没有对应的 jar。
 */
private class RobolectricSdkJar(
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) val jars: FileCollection,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("-Drobolectric.dependency.dir=${jars.singleFile.parentFile.absolutePath}")
}

private const val COMPOSE_PLUGIN_ID = "org.jetbrains.kotlin.plugin.compose"
