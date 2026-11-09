plugins {
    alias(libs.plugins.chogumi.jvm.library)
    // 测试夹具：贴贴等领域对象的构造（src/testFixtures），供各模块的测试共用
    `java-test-fixtures`
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(projects.core.foundation)
    implementation(libs.kotlinx.serialization.json)
}
