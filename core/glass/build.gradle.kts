plugins {
    alias(libs.plugins.chogumi.android.library)
    alias(libs.plugins.chogumi.android.compose)
    alias(libs.plugins.chogumi.android.robolectric)
}

/**
 * 液态玻璃（设置里的「玻璃效果」，默认关）：玻璃的全部代码只在这个模块里。
 * 页面只在入口分岔：玻璃开着画这里的组件，关着画 core:ui 原来的组件；不想要了，删掉这个模块和那几处分岔即可。
 * 规则在 docs/design/liquid-glass.md。
 */
dependencies {
    api(projects.core.ui)

    // 背景采样、模糊、折射（Android 12 起模糊，13 起折射与高光）
    implementation(libs.kyant0.backdrop)
}
