plugins {
    alias(libs.plugins.chogumi.android.library)
    // 资源解析的单测（系统语言列表怎么选到中文）要在真资源表上跑
    alias(libs.plugins.chogumi.android.robolectric)
}
