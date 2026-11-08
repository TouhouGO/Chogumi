package io.github.touhougo.chogumi.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 人机验证页发回的消息：只认组件页本身（主 frame）发的结果。 */
class TurnstileMessageTest {

    @Test
    fun `主 frame 发来的令牌与出错都认`() {
        assertEquals(TurnstileMessage.Verified("0.abc"), turnstileMessageOf("verified:0.abc", isMainFrame = true))
        assertEquals(TurnstileMessage.Failed, turnstileMessageOf("failed", isMainFrame = true))
    }

    @Test
    fun `iframe 里的脚本发来的一律不认`() {
        assertNull(turnstileMessageOf("verified:0.forged", isMainFrame = false))
        assertNull(turnstileMessageOf("failed", isMainFrame = false))
    }

    @Test
    fun `空令牌和认不出的消息不算`() {
        assertNull(turnstileMessageOf("verified:", isMainFrame = true))
        assertNull(turnstileMessageOf("hello", isMainFrame = true))
        assertNull(turnstileMessageOf(null, isMainFrame = true))
    }
}
