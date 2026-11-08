package io.github.touhougo.chogumi.core.reader

import io.github.touhougo.chogumi.core.model.COMMON_REACTIONS
import io.github.touhougo.chogumi.core.common.bbcode.SmileyCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 内置表情：恰好是 12 个可选 reaction（作品吐槽的 8 个是其子集）——点赞是核心交互，断网也要能显示，
 * 其余全部按需下载。哪些内置只在 [BundledSmileyResources.map] 声明一次，本测试钉住它与可选集一致。
 */
class BundledSmileyTest {

    @Test
    fun `内置集合恰好是12个可选reaction的code`() {
        val reactionCodes = COMMON_REACTIONS.mapNotNull(SmileyCatalog::codeOfReaction).toSet()
        assertEquals(12, reactionCodes.size)
        assertEquals(reactionCodes, BundledSmileyResources.map.keys)
    }

    /** 非内置 code 返回 null —— 调用方据此转走下载路径。 */
    @Test
    fun `非内置code没有资源ID`() {
        assertNull(BundledSmileyResources.resIdOf("bgm01"), "经典家族不在内置 12 个里")
        assertNull(BundledSmileyResources.resIdOf("musume_01"), "musume 走按需下载")
        assertNull(BundledSmileyResources.resIdOf("不存在的code"))
    }

    /** 资源 ID 两两不同 —— 防止复制粘贴时把两个 code 映到同一张图。 */
    @Test
    fun `资源ID互不相同`() {
        val ids = BundledSmileyResources.map.values
        assertEquals(ids.size, ids.toSet().size, "有两个 code 映到了同一个 drawable")
    }
}
