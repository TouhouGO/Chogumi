package io.github.touhougo.chogumi

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 入库的两份 profile 都要覆盖登录后的第一屏：首页与外壳的类都在里面。
 * 没登录时录出来的 profile 只有登录页与探索页，没有首页，启动时登录用户的首页全靠解释执行。
 */
class BaselineProfileContractTest {
    private val profiles = listOf(
        "baseline-prof.txt" to RepoFiles.file("app/src/release/generated/baselineProfiles/baseline-prof.txt"),
        "startup-prof.txt" to RepoFiles.file("app/src/release/generated/baselineProfiles/startup-prof.txt"),
    )

    @Test
    fun `两份 profile 都有首页与外壳的类`() {
        for ((name, file) in profiles) {
            assertTrue("$name 不存在", file.isFile)
            val profile = file.readLines()
            assertTrue("$name 为空", profile.isNotEmpty())
            for (type in listOf("io/github/touhougo/chogumi/feature/home/HomeScreenKt", "io/github/touhougo/chogumi/shell/AppShellKt")) {
                assertTrue("$name 里没有 $type：录的时候被测包可能没有登录", profile.any { it.contains("L$type;") })
            }
        }
    }
}
