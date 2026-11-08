package io.github.touhougo.chogumi.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.koin.compiler.plugin.KoinGradleExtension

/**
 * 声明了 Koin 模块的工程：向 app 的编译期整图校验导出定义（校验本身的开关在 app 的 koinCompiler 里）。
 *
 * 没有 startKoin 的模块里插件只能跳过校验，并每次编译报一条提示；这里把插件自己的提示降为 info，
 * 不混进编译告警。真正的装配问题（KOIN-Dxxx / KOIN-Wxxx）不受影响。
 */
class KoinConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("io.insert-koin.compiler.plugin")
            extensions.configure<KoinGradleExtension> {
                logSeverity.set("info")
            }
        }
    }
}
