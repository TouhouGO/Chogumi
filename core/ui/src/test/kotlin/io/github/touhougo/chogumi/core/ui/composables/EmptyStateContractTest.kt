package io.github.touhougo.chogumi.core.ui.composables

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 空态契约：列表或区块没有内容时只用 [EmptyLine] 画那一行字。各页自己用 `Text` 配一套字阶、颜色和留白，
 * 就会长出一份份不一样的空态。
 *
 * 认的写法是 `Text(` 直接拿一条空态文案（键名里带 `empty`）。
 */
class EmptyStateContractTest {

    @Test
    fun `空态文案只交给 EmptyLine 画`() {
        val offenders = RepoFiles.mainKotlinFiles("app", "core", "feature").flatMap { file ->
            val text = file.readText()
            HAND_DRAWN_EMPTY.findAll(text).map { match ->
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                "${RepoFiles.relativePath(file)}:$line: ${match.value.replace(Regex("\\s+"), " ")}"
            }.toList()
        }
        assertEquals(emptyList(), offenders, "空态用 EmptyLine，不要自己用 Text 画")
    }

    private companion object {
        /** `Text(` 后（可带 `text =`）紧跟一条键名含 `empty` 的文案。 */
        val HAND_DRAWN_EMPTY = Regex("""(?<![\w.])Text\(\s*(?:text\s*=\s*)?stringResource\(\s*R\.string\.\w*empty\w*""")
    }
}
