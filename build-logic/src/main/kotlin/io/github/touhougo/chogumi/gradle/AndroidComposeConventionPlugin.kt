package io.github.touhougo.chogumi.gradle

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/** 稳定性配置：哪些外部类型按值比较、等值即跳过重组。 */
private const val STABILITY_CONFIGURATION = "gradle/compose-stability.conf"

/** 带上这个 Gradle 属性（`-Pchogumi.composeReports`）时编译器把稳定性报告与指标写到 `build/compose-compiler`。 */
private const val REPORTS_PROPERTY = "chogumi.composeReports"

/**
 * Compose：编译器插件（稳定性配置、按需的编译器报告）、基础 UI 依赖与 Compose 设备测试依赖。
 * 叠加在 `chogumi.android.library` / `chogumi.android.application` 之上，须排在它们之后。
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.configure<CommonExtension> {
            buildFeatures.compose = true
        }

        val stability = rootProject.layout.projectDirectory.file(STABILITY_CONFIGURATION)
        extensions.configure<ComposeCompilerGradlePluginExtension> {
            stabilityConfigurationFiles.add(stability)
            if (providers.gradleProperty(REPORTS_PROPERTY).isPresent) {
                val reports = layout.buildDirectory.dir("compose-compiler")
                reportsDestination.set(reports)
                metricsDestination.set(reports)
            }
        }
        // 编译器插件只收到文件路径，Gradle 不看文件内容：不登记成输入，改了配置编译任务仍判 up-to-date，产物还是旧的
        tasks.withType<KotlinJvmCompile>().configureEach {
            inputs.file(stability).withPathSensitivity(PathSensitivity.NONE).withPropertyName("composeStability")
        }

        addComposeBom("implementation")
        addLibraries(
            "implementation",
            "androidx-compose-ui",
            "androidx-compose-ui-graphics",
            "androidx-compose-ui-tooling-preview",
            "androidx-compose-material3",
        )

        addComposeBom("androidTestImplementation")
        addLibraries("androidTestImplementation", "androidx-compose-ui-test-junit4", "androidx-test-ext-junit")
        addLibraries("debugImplementation", "androidx-compose-ui-test-manifest")
    }
}
