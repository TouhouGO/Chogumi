package io.github.touhougo.chogumi.core.reader

import io.github.touhougo.chogumi.core.model.COMMON_REACTIONS
import io.github.touhougo.chogumi.core.common.bbcode.SmileyCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 贴贴表情的显示：**value → 表情 code**，认不出的退回原始编号。
 *
 * 防的是直接拼原始 value，界面显示「54」而不是表情。
 * 渲染需要设备，但"哪个 value 该出图、哪个该退回原文"是纯逻辑，抽出来单测。
 */
class ReactionDisplayTest {

    /** 可选 reaction 全部解析成表情，无一落到降级分支。 */
    @Test
    fun `可选reaction全部解析为表情`() {
        COMMON_REACTIONS.forEach { value ->
            val display = reactionDisplayOf(value)
            assertTrue(display is ReactionDisplay.Smiley, "value=$value 应解析为表情，实际：$display（会露出裸数字）")
        }
    }

    /**
     * 未知 value **退回原文**而不是崩溃或空白。
     *
     * 服务端将来可能新增 reaction。客户端遇到不认识的 value 时，显示原始编号比显示空白诚实 ——
     * 与 `BBCodeText` 对未知节点「原样显示」的处理一致。
     */
    @Test
    fun `未知value退回原文显示`() {
        assertEquals(ReactionDisplay.Unknown(9999), reactionDisplayOf(9999))
    }

    /** 服务端隐藏的表情不在选择器里，但某条动态带了它时仍能解析出 code：我们认识它，只是用户点不到。 */
    @Test
    fun `隐藏表情也能解析出code`() {
        listOf(53, 92, 118, 60, 128, 47, 68, 137, 76, 132).forEach { value ->
            assertTrue(reactionDisplayOf(value) is ReactionDisplay.Smiley, "隐藏 value=$value 应解析成表情")
        }
    }

    /** 解析出的 code 必须与 Catalog 一致 —— 不在这里另立映射。 */
    @Test
    fun `解析结果与Catalog一致`() {
        COMMON_REACTIONS.forEach { value ->
            val actual = (reactionDisplayOf(value) as ReactionDisplay.Smiley).code
            assertEquals(SmileyCatalog.codeOfReaction(value), actual, "value=$value 的 code 应与 Catalog 一致")
        }
    }
}
