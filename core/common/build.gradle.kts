plugins {
    alias(libs.plugins.chogumi.android.library)
}

dependencies {
    api(projects.core.foundation)
    api(projects.core.model)

    api(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(libs.jsoup)
}
