plugins {
    alias(libs.plugins.chogumi.android.baselineprofile)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    // Macrobenchmark 与 UiAutomator 的最低要求
    defaultConfig.minSdk = 28
}

baselineProfile {
    // 在已连接的设备上生成（device.sh 认领的那台），不建 managed device
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.junit4)
}
