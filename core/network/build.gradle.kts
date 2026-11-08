plugins {
    alias(libs.plugins.chogumi.jvm.library)
    `java-test-fixtures`
    alias(libs.plugins.kotlin.serialization)
    // 向 app 的 Koin 编译期校验导出 networkModule 的 DSL 定义提示
    alias(libs.plugins.chogumi.koin)
}

dependencies {
    implementation(projects.core.foundation)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)

    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)

    testFixturesApi(libs.ktor.client.core)
    testFixturesApi(libs.ktor.client.mock)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
