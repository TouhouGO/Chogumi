plugins {
    alias(libs.plugins.chogumi.jvm.library)
    // 测试夹具：RepoFiles（源码契约测试读仓库文件的唯一入口）与 testAppTime，由 configureUnitTests 加给每个模块的单元测试
    `java-test-fixtures`
}

dependencies {
    // AppTime 对外给的是 StateFlow
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
