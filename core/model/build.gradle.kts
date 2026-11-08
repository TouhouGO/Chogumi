plugins {
    alias(libs.plugins.chogumi.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(projects.core.foundation)
    implementation(libs.kotlinx.serialization.json)
}
