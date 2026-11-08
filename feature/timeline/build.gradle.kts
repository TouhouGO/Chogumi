plugins {
    alias(libs.plugins.chogumi.android.feature)
}

dependencies {
    // 动态卡片与正文渲染
    implementation(projects.core.reader)
}
