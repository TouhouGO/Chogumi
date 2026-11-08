plugins {
    alias(libs.plugins.chogumi.android.feature)
    alias(libs.plugins.chogumi.android.robolectric)
}

android {
    // 测试夹具：单测与设备测试共用的假动作（src/testFixtures）
    testFixtures {
        enable = true
    }
}

dependencies {
    // 夹具实现的整理动作接口用到 core:model 的类型；本模块开着 Compose 编译器插件，夹具的编译也要有 Compose 运行时
    testFixturesImplementation(projects.core.model)
    testFixturesImplementation(platform(libs.androidx.compose.bom))
    testFixturesImplementation(libs.androidx.compose.ui)
    // 重组计数（CompositionWork）
    testImplementation(testFixtures(projects.core.ui))
}
