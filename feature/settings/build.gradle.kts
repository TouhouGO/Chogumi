plugins {
    alias(libs.plugins.chogumi.android.feature)
    alias(libs.plugins.chogumi.android.robolectric)
}

dependencies {
    // 设置页清理图片缓存：量与删的是应用唯一的 Coil 加载器的缓存
    implementation(libs.coil3.compose)
    // 个人资料换头像：系统选图器（只选图片）
    implementation(libs.androidx.activity.compose)
    // 页面面板只能画在导航项里：面板测试用 core:ui 测试夹具的宿主
    testImplementation(testFixtures(projects.core.ui))
}
