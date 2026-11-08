plugins {
    alias(libs.plugins.chogumi.android.feature)
    alias(libs.plugins.chogumi.android.robolectric)
}

dependencies {
    // LocalActivity：登录页的 ViewModel 放在 Activity 上（全 App 一份，见 LoginScreen）
    implementation(libs.androidx.activity.compose)
}
