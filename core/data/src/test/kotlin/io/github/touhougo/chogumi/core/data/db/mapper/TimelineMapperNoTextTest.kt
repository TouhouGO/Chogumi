package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `TimelineMapper` **不得产出任何文案**——数据层只传原始类型，文案由渲染层决定。
 *
 * ## 为什么有这条守卫
 *
 * 文案硬编码在 mapper 里有两个后果：
 * 1. 英文界面直接露中文（写进 `TimelineItemBo` 字符串字段（`text` 等）的是成品文案，
 *    不是资源键，故资源层的守卫看不见）；
 * 2. 数据层替界面决定了"这条动态该怎么措辞"，而措辞会随版式变——版式改了
 *    数据层要跟着改，同一件事写成了两处。
 *
 * 映射在 `core:reader` 的 `presentationOf`，这里只允许：
 * - 用户产生的内容（昵称、签名、评论、标题）
 * - 服务端返回的数据（群组名、条目名）
 * - 与语言无关的纯数值/符号（URL、数字）
 *
 * **不得**出现任何中文文案字面量。
 */
class TimelineMapperNoTextTest {

    @Test
    fun `mapper 源码不含中文文案字面量`() {
        val file = RepoFiles.file(MAPPER_PATH)
        assertTrue("待扫描的源文件不存在，守卫失效：$file", file.isFile)

        val offenders = mutableListOf<String>()
        var inBlockComment = false
        file.readLines().forEachIndexed { index, raw ->
            val (code, stillInBlock) = stripComments(raw, inBlockComment)
            inBlockComment = stillInBlock
            if (code.isBlank()) return@forEachIndexed
            Regex("\"([^\"]*)\"").findAll(code).forEach { match ->
                val literal = match.groupValues[1]
                if (literal.any { it.code in 0x4E00..0x9FFF }) {
                    offenders += "${index + 1}  $literal"
                }
            }
        }
        assertTrue(
            "数据层不得产出界面文案，这些字面量应上浮到 core:ui 并走 @string 资源：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun stripComments(line: String, startsInBlock: Boolean): Pair<String, Boolean> {
        val code = StringBuilder()
        var inBlock = startsInBlock
        var i = 0
        while (i < line.length) {
            when {
                !inBlock && line.startsWith("//", i) -> break
                !inBlock && line.startsWith("/*", i) -> { inBlock = true; i += 2 }
                inBlock && line.startsWith("*/", i) -> { inBlock = false; i += 2 }
                inBlock -> i++
                else -> { code.append(line[i]); i++ }
            }
        }
        return code.toString() to inBlock
    }

    private companion object {
        const val MAPPER_PATH = "core/data/src/main/kotlin/io/github/touhougo/chogumi/core/data/db/mapper/TimelineMapper.kt"
    }
}
