package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 唯一纯判定函数的四种组合，逐条钉死（含开关关闭时 NSFW 必须不可见）。 */
class NsfwVisibilityTest {

    @Test
    fun 开关关闭时NSFW条目不可见() {
        assertFalse(isSubjectVisible(nsfw = true, showNsfw = false))
    }

    @Test
    fun 开关关闭时非NSFW条目可见() {
        assertTrue(isSubjectVisible(nsfw = false, showNsfw = false))
    }

    @Test
    fun 开关打开时NSFW条目可见() {
        assertTrue(isSubjectVisible(nsfw = true, showNsfw = true))
    }

    @Test
    fun 开关打开时非NSFW条目可见() {
        assertTrue(isSubjectVisible(nsfw = false, showNsfw = true))
    }
}
