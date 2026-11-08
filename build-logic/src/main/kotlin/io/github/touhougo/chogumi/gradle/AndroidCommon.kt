package io.github.touhougo.chogumi.gradle

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project

/** 所有 Android 模块 namespace 的前缀：代码的包名。 */
internal const val CODE_PACKAGE = "io.github.touhougo.chogumi"

/** 装机后的应用身份（applicationId）。登录、偏好、库、备份、权限都按它记，与代码包名分开定，改包名不会换身份。 */
internal const val APPLICATION_ID = "io.github.touhougo.chogumi"

/** 仪器测试运行器；Compose 设备测试与普通设备测试共用。 */
private const val INSTRUMENTATION_RUNNER = "androidx.test.runner.AndroidJUnitRunner"

/** 截图与录屏的标记（core:ui testFixtures）：给人看的产物，不是测试。 */
private const val VISUAL_CAPTURE = "$CODE_PACKAGE.core.ui.testing.VisualCapture"

/** 带上这个 Gradle 属性（`-Pchogumi.captures`）时仪器测试只跑截图，否则跳过截图。 */
private const val CAPTURES_PROPERTY = "chogumi.captures"

/**
 * 应用与库共用的 Android 配置：SDK 级别、Java / Kotlin 级别、脱糖、测试运行器、默认语言的资源目录与单元测试。
 *
 * 仪器测试默认跳过 [VISUAL_CAPTURE] 标的截图，带 [CAPTURES_PROPERTY] 时反过来只跑截图。测试包里没有这个注解类的模块
 * （没有用 core:ui 测试夹具），运行器找不到类就不筛：默认照常全跑，只跑截图时也会全跑——所以出图只对有截图的模块跑。
 *
 * 脱糖对所有模块一律开启：minSdk 24 上 `java.time` 等靠它，模块各自判断「用没用到」
 * 只会让 lint 与打包结果在模块之间不一致。
 */
internal fun Project.configureAndroidCommon(android: CommonExtension) {
    configureAndroidBuild(android)
    configureDefaultLocaleStrings()
    configureUnitTests()
}

/** [configureAndroidCommon] 去掉单元测试的那部分：没有 JVM 单元测试的 Android 模块（`:baselineprofile`）只用它。 */
internal fun Project.configureAndroidBuild(android: CommonExtension) {
    android.apply {
        compileSdk = libs.intVersion("sdk-compile")

        defaultConfig.minSdk = libs.intVersion("sdk-min")
        defaultConfig.testInstrumentationRunner = INSTRUMENTATION_RUNNER
        val captureFilter = if (providers.gradleProperty(CAPTURES_PROPERTY).isPresent) "annotation" else "notAnnotation"
        defaultConfig.testInstrumentationRunnerArguments[captureFilter] = VISUAL_CAPTURE

        compileOptions.isCoreLibraryDesugaringEnabled = true
        compileOptions.sourceCompatibility = JAVA_VERSION
        compileOptions.targetCompatibility = JAVA_VERSION
    }
    configureKotlinCompile()
    addLibraries("coreLibraryDesugaring", "android-desugar-jdk-libs")
}
