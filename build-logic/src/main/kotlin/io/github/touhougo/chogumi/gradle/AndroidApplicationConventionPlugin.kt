package io.github.touhougo.chogumi.gradle

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** 应用模块（app）：namespace、applicationId 与 SDK 级别来自约定，签名、版本号、构建类型留在模块脚本。 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")

        extensions.configure<ApplicationExtension> {
            namespace = CODE_PACKAGE
            configureAndroidCommon(this)

            defaultConfig.applicationId = APPLICATION_ID
            defaultConfig.targetSdk = libs.intVersion("sdk-target")
        }
    }
}
