package io.github.touhougo.chogumi.core.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * BBCode 表情节点（`Smiley`）**该怎么显示**。
 *
 * ## 为什么需要它
 *
 * 防的是把表情节点 append 成文本：`append("(${node.code})")`，
 * 界面于是显示「(bgm38)」而不是表情图。这条通路服务的是**社区讨论楼层**
 * （BBCode 正文），与时间线正文（纯文本，见 `segmentTimelineBody`）不同。
 *
 * ## 为什么解析单独可测
 *
 * 渲染需要设备，但"哪个 code 能出图、哪个该退回原文"是纯逻辑。
 */
class BbcodeSmileyRenderTest {

    /** 12 个内置 reaction 的 code 都能解析出内置资源。 */
    @Test
    fun `内置code解析出图片`() {
        BundledSmileyResources.map.keys.forEach { code ->
            assertEquals(code, resolvableSmileyCode(code), "$code 应可渲染为图片")
        }
    }

    /**
     * **非内置但合法的 code** 仍应被"认识"（返回 code 而非 null）：它的图在运行时按需下载。
     *
     * 这条钉住的是「没有内置图不等于不存在」：`(musume_01)` 是可解析的表情，
     * 下载到之前显示原文只是降级，不是判定它非法。
     */
    @Test
    fun `合法但非内置的code仍被识别`() {
        listOf("musume_01", "blake_01", "bgm01").forEach { code ->
            assertEquals(code, resolvableSmileyCode(code), "$code 是合法表情，应被识别")
        }
    }

    /**
     * **越界/不可识别的 code 返回 null** —— 调用方据此显示原文。
     *
     * 与 `segmentTimelineBody` 的越界处理一致：显示原文比显示空白诚实。
     */
    @Test
    fun `越界code不可解析`() {
        assertNull(resolvableSmileyCode("bgm999"), "bgm999 超出所有家族范围")
        assertNull(resolvableSmileyCode("bgm0"), "code 0 不存在")
        assertNull(resolvableSmileyCode("musume_999"))
    }
}
