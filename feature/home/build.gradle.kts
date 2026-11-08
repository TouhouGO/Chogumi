plugins {
    alias(libs.plugins.chogumi.android.feature)
    alias(libs.plugins.chogumi.android.robolectric)
}

// 正式包（R8）要保留的东西随这个模块走：小组件点击回调的构造函数
android.defaultConfig.consumerProguardFiles("consumer-rules.pro")

dependencies {
    // 玻璃效果开着时列表铺到浮着的玻璃底栏下面：底部让出多少（core:glass LocalGlassContentBottom）
    implementation(projects.core.glass)
    // 桌面小组件：Glance 画 RemoteViews、跟着 App 配色；WorkManager 定时刷新（Glance 自己的会话也跑在它上面）
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime)
    // 小组件的封面要先解码成位图：取的是应用唯一的 Coil 加载器
    implementation(libs.coil3.compose)

    testImplementation(libs.androidx.glance.appwidget.testing)
}
