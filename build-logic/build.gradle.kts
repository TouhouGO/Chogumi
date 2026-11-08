plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "chogumi.jvm.library"
            implementationClass = "io.github.touhougo.chogumi.gradle.JvmLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "chogumi.android.library"
            implementationClass = "io.github.touhougo.chogumi.gradle.AndroidLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = "chogumi.android.application"
            implementationClass = "io.github.touhougo.chogumi.gradle.AndroidApplicationConventionPlugin"
        }
        register("androidBaselineProfile") {
            id = "chogumi.android.baselineprofile"
            implementationClass = "io.github.touhougo.chogumi.gradle.AndroidBaselineProfileConventionPlugin"
        }
        register("androidCompose") {
            id = "chogumi.android.compose"
            implementationClass = "io.github.touhougo.chogumi.gradle.AndroidComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "chogumi.android.feature"
            implementationClass = "io.github.touhougo.chogumi.gradle.AndroidFeatureConventionPlugin"
        }
        register("androidRobolectric") {
            id = "chogumi.android.robolectric"
            implementationClass = "io.github.touhougo.chogumi.gradle.AndroidRobolectricConventionPlugin"
        }
        register("koin") {
            id = "chogumi.koin"
            implementationClass = "io.github.touhougo.chogumi.gradle.KoinConventionPlugin"
        }
    }
}

dependencies {
    implementation(libs.gradle.plugin.android)
    implementation(libs.gradle.plugin.kotlin)
    implementation(libs.gradle.plugin.kotlin.compose)
    // 插件本体由根工程 apply false 载入，这里只要类型（KoinGradleExtension）
    compileOnly(libs.gradle.plugin.koin.compiler)
}
