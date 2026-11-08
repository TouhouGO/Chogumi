plugins {
    alias(libs.plugins.chogumi.android.feature)
    alias(libs.plugins.chogumi.android.robolectric)
}

dependencies {
    // 楼层、BBCode 正文、简介等阅读渲染
    implementation(projects.core.reader)

    // 共享编辑器与真实草稿仓库的集成边界：保留 Room、DataStore 和生产客户端。
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.room.testing)
}
