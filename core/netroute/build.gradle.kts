plugins {
    alias(libs.plugins.chogumi.android.library)
    // 向 app 的 Koin 编译期校验导出 netRouteModule 的 DSL 定义提示
    alias(libs.plugins.chogumi.koin)
}

android {
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
}

dependencies {
    // ObservedPreferences 与 outcomeOf；core:network（OkHttpCustomizer）随它传递
    implementation(projects.core.data)

    implementation(libs.ktor.client.okhttp)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    // 直连核心（Go 源码在 go/，tools/echcore/build.sh 生成，只含 arm64-v8a）：整个应用里只有本模块依赖它
    implementation(files("libs/echcore.aar"))

    testImplementation(libs.junit4)
    // 网页会话经核心的测试用生产工厂 createWebHttpClient（Ktor）
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
