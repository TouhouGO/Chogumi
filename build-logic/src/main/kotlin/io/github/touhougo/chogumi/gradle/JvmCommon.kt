package io.github.touhougo.chogumi.gradle

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/** 全仓唯一的 Java 源码 / 字节码级别；Kotlin 的 jvmTarget 由它推出，不存在第二份。 */
internal val JAVA_VERSION = JavaVersion.VERSION_11

/**
 * 所有 Kotlin/JVM 编译任务（含 Android 模块与各测试源码）：产出与 [JAVA_VERSION] 相同的字节码；告警一律当错误。
 *
 * 告警不是可选项（docs/code-standards.md 红线 2）。这同时守住 app 的 Koin 编译期校验：插件读不懂模块清单时
 * 只报 KOIN-W003 告警并跳过整张图，不升级成错误，保护就会悄悄消失。
 */
internal fun Project.configureKotlinCompile() {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(JAVA_VERSION.toString()))
        compilerOptions.allWarningsAsErrors.set(true)
    }
}

/**
 * 单元测试（JVM 库与 Android 模块一致）：JUnit 4 与 kotlin.test 断言；仓库源码登记为每个测试任务的输入；
 * 测试读仓库文件只经 `RepoFiles`（core:foundation 的 testFixtures），仓库根由这里以系统属性传入。
 *
 * 源码契约测试读主源码、构建脚本、资源与文档来判断「只有一处」，这些文件不在测试的 classpath 里。
 * 不登记，只改了它们时 Gradle 会把测试判成 up-to-date 跳过，契约就悄悄不跑了。
 *
 * `-Pchogumi.testDryRun=true` 时测试只演不跑（Gradle 的 dryRun）：只有 `tools/ci/check.sh warm` 这样用，
 * 为了把编译与资源处理的产出写进构建缓存而不花跑测试的时间。
 */
internal fun Project.configureUnitTests() {
    addLibraries("testImplementation", "junit4", "kotlin-test-junit")
    // core:foundation 自己的测试由 java-test-fixtures 接上自己的夹具
    if (path != FOUNDATION) dependencies.add("testImplementation", dependencies.testFixtures(project(FOUNDATION)))
    val repoRoot = rootProject.layout.projectDirectory.asFile.absolutePath
    val repoSources = rootProject.layout.projectDirectory.asFileTree.matching {
        // RepoFiles 会扫描所有源集和全仓的 Kotlin / Java / XML / GIF，也直接读取根目录说明与 docs。
        include(
            "**/src/**",
            "**/*.kt", "**/*.java", "**/*.xml", "**/*.gif",
            "**/build.gradle.kts", "settings.gradle.kts", "gradle/libs.versions.toml",
            "*.md", "LICENSE", "docs/**",
        )
        include("app/src/release/generated/baselineProfiles/**")
        // 与 RepoFiles.walk 一致：任意层的构建产物、点目录与依赖副本都不属于仓库输入。
        exclude("**/build/**", "**/.*/**", "**/node_modules/**")
    }
    val testDryRun = providers.gradleProperty("chogumi.testDryRun").map(String::toBoolean).orElse(false)
    tasks.withType<Test>().configureEach {
        dryRun.set(testDryRun)
        inputs.files(repoSources).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("repoSources")
        jvmArgumentProviders += RepoRootArgument(repoRoot)
    }
}

private const val FOUNDATION = ":core:foundation"

/** 把仓库根交给测试（`RepoFiles` 读系统属性 `chogumi.repoRoot`）。只是位置、不算输入：输入是上面登记的仓库源码。 */
private class RepoRootArgument(@get:Internal val path: String) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Dchogumi.repoRoot=$path")
}
