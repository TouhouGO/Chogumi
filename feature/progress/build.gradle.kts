plugins {
    alias(libs.plugins.chogumi.android.feature)
}

dependencies {
    // 玻璃效果开着时列表铺到浮着的玻璃底栏下面：底部让出多少（core:glass LocalGlassContentBottom）
    implementation(projects.core.glass)
}
