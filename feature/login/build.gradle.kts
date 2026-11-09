plugins {
    alias(libs.plugins.chogumi.android.feature)
    alias(libs.plugins.chogumi.android.robolectric)
}

dependencies {
    // 连不上 bgm.tv 时提示「试试直连」：线路设置给出的提示条件
    implementation(projects.core.netroute)
    // LocalActivity：登录页的 ViewModel 放在 Activity 上（全 App 一份，见 LoginScreen）
    implementation(libs.androidx.activity.compose)
}
