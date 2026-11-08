package io.github.touhougo.chogumi.gradle

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * 所有 Android 库模块。
 *
 * namespace 由模块路径推出（`:core:common` → `io.github.touhougo.chogumi.core.common`），
 * 模块脚本不手写，也就不会和目录结构漂移。
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            namespace = CODE_PACKAGE + path.replace(':', '.')
            configureAndroidCommon(this)
        }
    }
}
