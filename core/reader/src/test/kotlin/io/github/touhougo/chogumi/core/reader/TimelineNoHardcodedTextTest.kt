package io.github.touhougo.chogumi.core.reader

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 时间线渲染层**不得残留硬编码文案**。
 *
 * 资源守卫（core:res 的 StringsLocaleContractTest）只扫 `values/strings.xml` 里**已存在的键**
 * （查死键、查纯 ASCII 漏译），它看不见 kotlin 源码里的中文字面量——所以 `Text("显示剧透内容")` 这类写法
 * 能在它眼皮底下活很久，并且在英文界面直接露中文（服务端数据直出是另一条通路，这里管的是**源码字面量**这条）。
 * 一个管「资源有没有人用」，一个管「文案有没有走资源」。注释里引用的中文不算（[RepoFiles.codeLines]）。
 */
class TimelineNoHardcodedTextTest {

    @Test
    fun `时间线渲染层源码不含中文字面量`() {
        val literal = Regex("\"([^\"]*)\"")
        val offenders = sources().flatMap { path ->
            RepoFiles.codeLines(RepoFiles.file(path)).withIndex().flatMap { (index, code) ->
                literal.findAll(code).map { it.groupValues[1] }
                    .filter { text -> text.any { it.code in 0x4E00..0x9FFF } }
                    .map { "${path.substringAfterLast('/')}:${index + 1}  $it" }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "时间线渲染层的文案必须走 @string 资源（两个 locale 都要加），" +
                "否则英文界面会露中文。以下字面量未资源化：\n${offenders.joinToString("\n")}",
        )
    }

    /** 时间线在渲染层涉及的文件；新增渲染文件时应一并加入。 */
    private fun sources(): List<String> = listOf(
        "core/reader/src/main/kotlin/io/github/touhougo/chogumi/core/reader/TimelineRow.kt",
        "core/reader/src/main/kotlin/io/github/touhougo/chogumi/core/reader/TimelineDetailSheet.kt",
        "core/reader/src/main/kotlin/io/github/touhougo/chogumi/core/reader/TimelinePresentation.kt",
        "core/reader/src/main/kotlin/io/github/touhougo/chogumi/core/reader/TimelineActionText.kt",
    ).also { paths ->
        paths.forEach { assertTrue(RepoFiles.file(it).isFile, "待扫描的源文件不存在，守卫失效：$it") }
    }
}
