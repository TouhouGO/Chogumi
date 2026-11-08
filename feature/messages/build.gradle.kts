plugins {
    alias(libs.plugins.chogumi.android.feature)
    alias(libs.plugins.chogumi.android.robolectric)
}

dependencies {
    // 短信正文的 BBCode 渲染、回复的编辑器面板、正文的长按与复制
    implementation(projects.core.reader)
    // 面板的测试宿主（core:ui 的测试夹具）
    testImplementation(testFixtures(projects.core.ui))
}
