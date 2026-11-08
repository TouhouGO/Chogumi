plugins {
    alias(libs.plugins.chogumi.android.library)
    alias(libs.plugins.chogumi.android.robolectric)
    alias(libs.plugins.chogumi.android.compose)
}

/**
 * 阅读渲染层：所有正文的渲染都在这里——BBCode 渲染与折叠、作品 / 角色简介、个人简介、
 * 帖子与评论楼层、贴贴、用户卡片、动态卡片。
 * 解析器本体（BBCodeParser）在 core:common；表情（内置资源与按需下载）、人机验证弹窗也在这里；站内链接路由被导航层共用，留在 core:ui。
 */
dependencies {
    api(projects.core.ui)

    implementation(libs.coil3.compose)
    implementation(libs.androidx.webkit)

    // 动态骨架几何比对与截图工具、面板的测试宿主（core:ui 的测试夹具）
    testImplementation(testFixtures(projects.core.ui))
    androidTestImplementation(testFixtures(projects.core.ui))
}
