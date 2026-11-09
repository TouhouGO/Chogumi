import com.android.build.api.variant.UnitTest

plugins {
    alias(libs.plugins.chogumi.android.library)
    alias(libs.plugins.chogumi.android.robolectric)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // 向 app 的 Koin 编译期校验导出 dataModule 的 DSL 定义提示
    alias(libs.plugins.chogumi.koin)
}

android {
    // 测试夹具：FakeDaos 供 feature 模块 VM 测试复用（src/testFixtures）
    testFixtures {
        enable = true
    }
}

// 迁移测试从导出的表结构（schemas/）起一个旧版本的库，再迁到当前版本。
androidComponents {
    onVariants { variant ->
        variant.nestedComponents.filterIsInstance<UnitTest>().forEach {
            it.sources.assets?.addStaticSourceDirectory("schemas")
        }
    }
}

// 每个版本的表结构导出到 schemas/，以后升版本写迁移时拿它当基线。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(projects.core.foundation)
    api(projects.core.common)
    api(projects.core.model)
    api(projects.core.network)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.serialization.json)

    // di/DataModule.kt 的表情资源下载用共享 HttpClient 取原始字节（bodyAsBytes）；
    // 用 implementation 而非 api，避免 ktor 类型泄漏到 core:data 的消费者。
    implementation(libs.ktor.client.core)

    // di/DataModule.kt 的 androidContext() 需要 Koin
    // （core:network 是纯 JVM 库用 koin-core，本模块是 Android 库故用 koin-android）
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    // 网络测试共用生产客户端工厂，只替换传输层。
    testFixturesApi(testFixtures(projects.core.network))
    testFixturesApi(testFixtures(projects.core.model))
    // FakeDaos 返回 Flow（observeAll/observeCurrent），需要协程类型参与编译
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    // 测试夹具 TestNsfwFilter 要构造真实 PreferencesRepository/NsfwPreferenceRepository 的 DataStore，
    // 并用 MockEngine 冒充 /p1/privacy —— 走生产仓库链而非假实现，夹具里不存在第二份缓存键清单。
    testFixturesImplementation(libs.androidx.datastore.preferences)
    testFixturesImplementation(libs.ktor.client.core)
    testFixturesImplementation(libs.ktor.client.mock)
    testFixturesImplementation(libs.kotlin.test)
    testFixturesApi(libs.junit4)
    // TestMain：ViewModel 单测共用的 Dispatchers.Main（setMain / resetMain）
    testFixturesImplementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    // 「SQL 通配符转义」与缓存清理必须跑真实 Room / SQLite：手写 FakeDao 测的是自己的假实现，
    // 证明不了 like 语义。Robolectric 提供 JVM 上的真实 Android SQLite。
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}
