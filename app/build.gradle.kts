import java.util.Properties

plugins {
    alias(libs.plugins.chogumi.android.application)
    alias(libs.plugins.chogumi.android.robolectric)
    alias(libs.plugins.chogumi.android.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.chogumi.koin)
    alias(libs.plugins.androidx.baselineprofile)
}

/** bgm.tv OAuth 应用的凭据（项目根的 oauth-app.properties，不入库）；文件不存在时为空，由下面的构建前校验报缺哪个键。 */
val oauthApp = Properties().also {
    val f = rootProject.file("oauth-app.properties")
    if (f.exists()) it.load(f.reader())
}

/**
 * 应用凭据的**必需键清单**（单一真源）。
 *
 * `buildConfigField` 的生成与构建前校验都读这一份，不各自硬编码键名。
 *
 * 缺任何一个都不该打出包：空 client_id 的 APK 打开网页登录会被 bgm.tv 判
 * `app_nonexistence`，而症状要到用户点「登录」才暴露，排查成本极高。
 */
val appCredentialKeys = listOf("CLIENT_ID", "CLIENT_SECRET")

/** 读取单个凭据；缺失时为空串。 */
fun appCredential(key: String): String = oauthApp.getProperty(key) ?: ""

/**
 * 缺失或**空白**的必需键。空白也算缺失 —— `Properties` 可能给出 `" "` 这类
 * 看着有值、实则无效的内容。
 */
val missingAppCredentialKeys: List<String> =
    appCredentialKeys.filter { oauthApp.getProperty(it).isNullOrBlank() }

/**
 * 在 [preBuild] 上装一个凭据校验动作。
 *
 * ## 为什么挂在 preBuild 而不是配置阶段抛错
 *
 * `preBuild` 只有**真正构建 App 变体**时才会经过：`./gradlew help`、`tasks`
 * 以及 core/feature 模块的单测都不碰它。若在配置阶段抛错，任何一条 Gradle
 * 命令（哪怕只是列出任务）都会失败，把"缺钥匙"升级成"整个仓库打不开"。
 *
 * ## 只解析一次凭据文件
 *
 * `oauth-app.properties` 在配置阶段由 [oauthApp] 读一次，缺失清单
 * [missingAppCredentialKeys] 也在此刻算好；`doFirst` 只接收这两个不可变结果，
 * **不重新打开文件**，避免同一份文件被解析两遍。
 *
 * ## 配置缓存兼容（gradle.properties 已开 `org.gradle.configuration-cache`）
 *
 * 配置阶段读取的文件会被配置缓存登记为输入，改动它会自动触发重新配置；
 * `doFirst` 闭包只捕获配置阶段算出的不可变局部量，不引用 `project` /
 * `rootProject` / 脚本属性，免得上游对象被卷进缓存快照。
 *
 * ## 无凭据环境放行
 *
 * 没有 `oauth-app.properties`（已 gitignore）的环境（CI、云端会话）也要能跑 `:app:assembleDebug`，
 * 故 `CI=true` 时降级为**告警**而非失败，
 * 并明确说明该产物无法网页登录 —— 既不挡流水线，也不让"能装机但不能登录"的包
 * 被误当成正常产物。
 */
fun installCredentialCheck(missingKeys: List<String>, isCi: Boolean) {
    tasks.named("preBuild") {
        doFirst {
            if (missingKeys.isEmpty()) return@doFirst

            // 只说缺哪个键，绝不回显任何凭据内容。
            val keys = missingKeys.joinToString("、")
            if (isCi) {
                logger.warn(
                    "缺少 bgm.tv 应用凭据（$keys）—— 本次构建产物只能用于浏览，不能网页登录" +
                        "（授权页会报 app_nonexistence）。要网页登录，请在项目根目录创建 oauth-app.properties，" +
                        "填入 CLIENT_ID、CLIENT_SECRET，并在 https://bgm.tv/dev/app 注册应用。"
                )
            } else {
                throw GradleException(
                    "缺少 bgm.tv 应用凭据（$keys），已中止构建。\n" +
                        "  只浏览：在项目根运行 CI=true ./gradlew :app:assembleDebug（产物不能网页登录）；\n" +
                        "  网页登录：在项目根目录创建 oauth-app.properties，填入 CLIENT_ID、CLIENT_SECRET，" +
                        "并在 https://bgm.tv/dev/app 注册应用。"
                )
            }
        }
    }
}

installCredentialCheck(
    missingKeys = missingAppCredentialKeys,
    isCi = providers.environmentVariable("CI").map { it.toBoolean() }.orElse(false).get(),
)

// 编译期校验整张依赖图：漏注册的依赖、构造参数在图里找不到，运行时就是打开页面即闪退，这里让本模块编译失败。
// 单测与手工构造 VM 绕过 Koin，仪器测试不进合并前检查，所以这道闸必须在编译期。
// 校验对象就是 App.kt 里 `startKoin { modules(...) }` 实际加载的那份清单，不存在第二份副本。
koinCompiler {
    compileSafety = true
    // 默认 true 会放过「带默认值却没在图里注册」的构造参数，而 viewModelOf(::T) / singleOf(::T) 这类构造器引用
    // 在运行时会把默认参数也按类型去图里 get()。所以按运行时的真实行为校验：每个参数都要在图里。
    skipDefaultValues = false
}

// 生成 baseline profile 时一条规则都没录到（被测包或测试包没装上、旅程没跑起来），插件只打一行警告、构建照样成功，
// 还把入库的 profile 换成空的。这一步改成直接失败：空 profile 不会悄悄留在工作区里等人提交。
val releaseBaselineProfiles = layout.projectDirectory.dir("src/release/generated/baselineProfiles")
tasks.matching { it.name.startsWith("generate") && it.name.endsWith("BaselineProfile") }.configureEach {
    val profiles = releaseBaselineProfiles
    doLast {
        val empty = listOf("baseline-prof.txt", "startup-prof.txt").filter { name ->
            val file = profiles.file(name).asFile
            !file.isFile || file.length() == 0L
        }
        if (empty.isNotEmpty()) {
            throw GradleException(
                "没有生成 baseline profile 规则（${empty.joinToString("、")} 不存在或为空）：看上面 connected 测试的输出，" +
                    "被测包与测试包装上了没有、旅程跑通了没有；用 git checkout 恢复入库的 profile",
            )
        }
    }
}

/**
 * 版本只有一个来源：gradle.properties 的 `chogumi.version`（主.次.修订）。versionCode 由它推出
 * （主 × 10000 + 次 × 100 + 修订），两者不会各改各的。
 */
val appVersion: String = providers.gradleProperty("chogumi.version").get()
val appVersionCode: Int = appVersion.split('.').map(String::toInt).let { (major, minor, patch) ->
    require(minor < 100 && patch < 100) { "chogumi.version 的次版本与修订号都要小于 100：$appVersion" }
    major * 10_000 + minor * 100 + patch
}

/** OAuth 回调地址的三部分；清单的 intent-filter 与 `BuildConfig.OAUTH_REDIRECT_URI` 都由它们生成，bgm.tv 应用面板填同一个地址。 */
val oauthCallbackScheme = "chogumi"
val oauthCallbackHost = "oauth"
val oauthCallbackPath = "/callback"

android {
    defaultConfig {
        versionCode = appVersionCode
        versionName = appVersion

        manifestPlaceholders["oauthCallbackScheme"] = oauthCallbackScheme
        manifestPlaceholders["oauthCallbackHost"] = oauthCallbackHost
        manifestPlaceholders["oauthCallbackPath"] = oauthCallbackPath
        buildConfigField("String", "OAUTH_REDIRECT_URI", "\"$oauthCallbackScheme://$oauthCallbackHost$oauthCallbackPath\"")

        // 键名取自 appCredentialKeys（单一真源），与构建前校验同一份清单；缺失时空串。
        // 字段名就是键名（BuildConfig.CLIENT_ID / CLIENT_SECRET，App.kt 读进 AuthConfig）。
        appCredentialKeys.forEach { key ->
            buildConfigField("String", key, "\"${appCredential(key)}\"")
        }
    }

    buildTypes {
        // 调试包与正式包能同时装在一台机器上：包名与版本名都带上 debug。
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        // 正式发布由 tools/release/formal_release.py 固定外部签名并归档同版 mapping；Gradle 不读取签名密钥或口令。
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures.buildConfig = true

    // 应用语言清单（Android 13 起系统设置里的「应用语言」）由 AGP 从资源目录生成：默认语言来自 src/main/res/resources.properties，
    // 其余是有 values-<语言>/ 目录的语言（中文目录由 build-logic 生成，英文是手写的 values-en）；清单项不手写。
    androidResources.generateLocaleConfig = true

    // APK 里不放依赖清单（AGP 默认写一块 Google Play 专用、加密的依赖信息）：开源分发渠道要求包里没有这块不透明数据。
    dependenciesInfo.includeInApk = false
}

base.archivesName = "chogumi-$appVersion"

dependencies {
    implementation(projects.core.foundation)
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.core.model)
    implementation(projects.core.ui)
    implementation(projects.core.netroute)
    implementation(projects.core.reader)
    implementation(projects.feature.home)
    implementation(projects.feature.progress)
    implementation(projects.feature.explore)
    implementation(projects.feature.timeline)
    implementation(projects.feature.community)
    implementation(projects.feature.profile)
    implementation(projects.feature.subjectdetails)
    implementation(projects.feature.login)
    implementation(projects.feature.calendar)
    implementation(projects.feature.settings)
    implementation(projects.feature.monodetails)
    implementation(projects.feature.messages)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.compose.material3.windowsizeclass)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    // AGP 把设备测试运行时类路径里的 concurrent-futures 钉在 1.1.0，而 androidx.test.ext:junit
    // 传递要求 1.2.0，两者冲突会让 app 的设备测试类路径无法解析；这里把两侧统一提升到 1.2.0。
    constraints {
        implementation(libs.androidx.concurrent.futures)
    }

    baselineProfile(projects.baselineprofile)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.slf4j.simple)

    implementation(libs.androidx.browser)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.coil3.compose)
    implementation(libs.coil3.gif)
    implementation(libs.coil3.network.okhttp)
    implementation(libs.coil3.svg)

    // 桌面入口的契约测试要等后台换图标落地：用 core:data 测试夹具里唯一的 awaitUntil
    testImplementation(testFixtures(projects.core.data))
}
