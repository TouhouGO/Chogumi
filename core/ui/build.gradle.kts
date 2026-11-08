plugins {
    alias(libs.plugins.chogumi.android.library)
    alias(libs.plugins.chogumi.android.robolectric)
    alias(libs.plugins.chogumi.android.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // 测试夹具：骨架几何比对与截图工具（src/testFixtures），供各模块 androidTest 共用。
    // AGP 会把本模块自己的 testFixtures 自动加到本模块的 androidTest 类路径上，
    // 因此 core:ui 的 androidTest 不需要显式依赖自己。
    testFixtures {
        enable = true
    }
}

dependencies {
    api(projects.core.foundation)
    api(projects.core.common)
    api(projects.core.res)
    api(projects.core.model)

    implementation(libs.kotlinx.serialization.core)

    implementation(libs.materialkolor)
    api(libs.androidx.compose.material3.windowsizeclass)

    api(libs.androidx.navigation3.runtime)
    api(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.coil3.compose)
    // 看图页：保存前申请存储权限（API 28 及以下）、分享经 FileProvider
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.kotlinx.serialization.json)

    // 共用测试夹具的编译期依赖：Compose 测试 API、InstrumentationRegistry、JUnit 断言。
    testFixturesApi(platform(libs.androidx.compose.bom))
    testFixturesApi(libs.androidx.compose.ui.test.junit4)
    testFixturesApi(libs.androidx.test.ext.junit)
    testFixturesImplementation(libs.junit4)
}
