package io.github.touhougo.chogumi.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/** 版本目录（`gradle/libs.versions.toml`）。约定插件取版本与依赖坐标的唯一入口。 */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** 按目录别名取依赖坐标；别名写错时立刻在配置期报错，而不是静默漏掉依赖。 */
internal fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalArgumentException("版本目录里没有依赖别名 `$alias`") }

/** 按目录别名取整数版本（SDK 级别）。 */
internal fun VersionCatalog.intVersion(alias: String): Int =
    findVersion(alias).orElseThrow { IllegalArgumentException("版本目录里没有版本别名 `$alias`") }
        .requiredVersion.toInt()

/** 把一组目录别名加进同一个依赖配置。 */
internal fun Project.addLibraries(configuration: String, vararg aliases: String) {
    aliases.forEach { dependencies.add(configuration, libs.library(it)) }
}

/** 把 Compose BOM 作为平台依赖加进某个配置（该配置里的 Compose 依赖因此不必写版本）。 */
internal fun Project.addComposeBom(configuration: String) {
    dependencies.add(configuration, dependencies.platform(libs.library("androidx-compose-bom")))
}
