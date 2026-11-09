package io.github.touhougo.chogumi.core.network

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网络客户端工厂与应用接线必须显式决定线路调整，不能隐式造出未接线的客户端。 */
class RoutingConfigurationContractTest {
    @Test
    fun `三个客户端工厂没有隐式空线路调整`() {
        val source = RepoFiles.read("core/network/src/main/kotlin/io/github/touhougo/chogumi/core/network/NetworkModule.kt")
        assertTrue(
            "客户端工厂不能默认绕过线路调整",
            !Regex("""customizers\s*:\s*List<OkHttpCustomizer>\s*=\s*emptyList\(\)""").containsMatchIn(source),
        )
    }
}
