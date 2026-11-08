package io.github.touhougo.chogumi.core.foundation

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class RepoFilesTest {
    @Test
    fun `主源码扫描排除依赖构建产物与隐藏目录`() {
        val build = RepoFiles.file("core/foundation/build").apply { mkdirs() }
        val directory = Files.createTempDirectory(build.toPath(), "source-scan-").toFile()
        try {
            val visible = "module/src/main/Visible.kt"
            val ignored = listOf(
                "node_modules/example/src/main/Dependency.kt",
                "module/build/generated/src/main/Generated.kt",
                "module/.hidden/src/main/Hidden.kt",
            )
            for (relative in ignored + visible) {
                directory.resolve(relative).apply {
                    parentFile.mkdirs()
                    writeText("class Sample")
                }
            }
            val files = RepoFiles.mainKotlinFiles(RepoFiles.relativePath(directory))
            assertEquals(listOf(directory.resolve(visible)), files)
        } finally {
            check(directory.deleteRecursively())
        }
    }
}
