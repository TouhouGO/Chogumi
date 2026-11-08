plugins {
    alias(libs.plugins.chogumi.android.feature)
}

dependencies {
    // 玻璃效果开着时列表铺到浮着的玻璃底栏下面：底部让出多少（core:glass LocalGlassContentBottom）
    implementation(projects.core.glass)
    // 发帖的编辑器面板、小组简介、草稿回复对象的说法（「回复 #7 某某」）
    implementation(projects.core.reader)
}
