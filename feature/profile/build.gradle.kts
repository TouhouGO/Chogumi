plugins {
    alias(libs.plugins.chogumi.android.feature)
    alias(libs.plugins.chogumi.android.robolectric)
}

dependencies {
    // 玻璃效果开着时列表铺到浮着的玻璃底栏下面：底部让出多少（core:glass LocalGlassContentBottom）
    implementation(projects.core.glass)
    // 楼层、BBCode 正文、简介等阅读渲染
    implementation(projects.core.reader)

    // 页面面板只能画在导航项里：面板测试用 core:ui 测试夹具的宿主
    testImplementation(testFixtures(projects.core.ui))
}
