package io.github.touhougo.chogumi.gradle

import com.android.build.api.dsl.TestExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * 生成 baseline profile 的测试模块（`:baselineprofile`）：目标是 `:app`，包名在 [CODE_PACKAGE] 之下，
 * SDK 级别、Java / Kotlin 级别与告警规则同其它模块。生成 profile 的 Gradle 插件由模块脚本申请。
 */
class AndroidBaselineProfileConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.test")

        extensions.configure<TestExtension> {
            namespace = CODE_PACKAGE + path.replace(':', '.')
            configureAndroidBuild(this)
            targetProjectPath = ":app"
            // 旅程从运行参数里取被测应用的身份，不在源码里再写一份
            defaultConfig.testInstrumentationRunnerArguments[TARGET_APP_ID_ARGUMENT] = APPLICATION_ID
        }
    }
}

/** 运行参数名：旅程（`:baselineprofile` 的 `StartupJourney`）按这个名字读被测应用的 applicationId。 */
private const val TARGET_APP_ID_ARGUMENT = "targetAppId"
