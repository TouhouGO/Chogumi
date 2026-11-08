package io.github.touhougo.chogumi

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模块分层的可执行守卫（构建文件里两种写法都认：`project(":x:y")` 与类型安全的 `projects.x.y`）：
 * - feature 模块只能依赖 core 系列模块，需要另一个 feature 的内容时一律由 app 壳层经槽位参数注入（如首页「动态」tab）；
 * - 渲染层（core:ui、core:reader）不依赖数据层（core:data）：它们只拿模型与回调，取数与写入在 ViewModel 与仓库里。
 * - feature 主源码不用 core:network（import 与全限定名都算）：core:data 以 `api` 带出网络层，构建文件拦不住，所以按源码查。
 *   网络层的类型由仓库包住（如账号密码登录的 `LoginAttempt`），失败已在 core:network 变成 `Failure`。
 */
class FeatureDependencyContractTest {
    @Test
    fun `feature 模块之间不得互相依赖`() {
        val featureDir = RepoFiles.file("feature")
        val modules = featureDir.listFiles { file -> file.isDirectory && File(file, "build.gradle.kts").exists() }.orEmpty()
        assertTrue("没有找到 feature 模块，路径不对", modules.isNotEmpty())
        val offenders = modules.flatMap { module -> edgesTo(module, "feature").map { "feature/${module.name}/build.gradle.kts:$it" } }
        assertTrue("feature 模块间禁止互相依赖，违规边：\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    @Test
    fun `渲染层不依赖数据层`() {
        val offenders = listOf("core/ui", "core/reader").flatMap { path ->
            edgesTo(RepoFiles.file(path), "core", "data").map { "$path/build.gradle.kts:$it" }
        }
        assertTrue("core:ui / core:reader 不得依赖 core:data，违规边：\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    @Test
    fun `feature 主源码不直接用网络层`() {
        val network = Regex("""(?<![\w.])io\.github\.touhougo\.chogumi\.core\.network\.""")
        val offenders = RepoFiles.mainKotlinFiles("feature").flatMap { file ->
            RepoFiles.codeLines(file).withIndex()
                .filter { (_, line) -> network.containsMatchIn(line) }
                .map { (index, line) -> "${RepoFiles.relativePath(file)}:${index + 1}: ${line.trim()}" }
        }
        assertTrue("feature 主源码只经 core:data 的仓库接触网络层，违规：\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    /** [module] 的构建文件里指向 `:group(:name)` 的依赖行（「行号: 内容」）。 */
    private fun edgesTo(module: File, group: String, name: String? = null): List<String> {
        val target = name?.let { "$group:$it" } ?: "$group:"
        val accessor = name?.let { "projects.$group.$it" } ?: "projects.$group."
        return module.resolve("build.gradle.kts").readText().lineSequence()
            .withIndex()
            .filter { (_, line) -> !line.trimStart().startsWith("//") }
            .filter { (_, line) -> line.contains("""project(":$target""") || line.contains(accessor) }
            .filter { (_, line) -> name == null || !line.contains("$accessor.") }
            .map { (index, line) -> "${index + 1}: ${line.trim()}" }
            .toList()
    }
}
