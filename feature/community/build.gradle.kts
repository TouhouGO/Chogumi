plugins {
    alias(libs.plugins.chogumi.android.feature)
}

dependencies {
    // 发帖的编辑器面板、小组简介、草稿回复对象的说法（「回复 #7 某某」）
    implementation(projects.core.reader)
}
