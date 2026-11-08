package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import io.github.touhougo.chogumi.core.network.model.p1.CommentBaseDto
import io.github.touhougo.chogumi.core.network.model.p1.NoticeDto
import io.github.touhougo.chogumi.core.network.model.p1.P1UserDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpochSecondsTest {

    @Test
    fun `秒换成毫秒_0 和负数记 0`() {
        assertEquals(1_700_000_000_000L, 1_700_000_000L.epochSecondsToMillis())
        assertEquals(0L, 0L.epochSecondsToMillis())
        assertEquals(0L, (-5L).epochSecondsToMillis())
    }

    @Test
    fun `楼层时间一律按秒换算_不按数值大小猜单位`() {
        val looksLikeMillis = CommentBaseDto(
            id = 1, mainID = 2, creatorID = 3, relatedID = 0, createdAt = 1_700_000_000_000L, content = "", state = 0,
        )
        assertEquals(1_700_000_000_000_000L, looksLikeMillis.toCommentItemBo().createdAt)
    }

    @Test
    fun `通知时间是负数时记 0`() {
        val notice = NoticeDto(
            id = 1, type = 1, sender = P1UserDto(id = 1, username = "u", nickname = "n"),
            title = "", mainID = 1, relatedID = 1, createdAt = -5L, unread = true,
        )
        assertEquals(0L, notice.toDomain().createdAt)
    }

    /** 只扫本模块自己的主源码：换算只许 [epochSecondsToMillis] 一处，别处不得再写 `x * 1000L`、`= 1000L` 的常量或另一个私有换算。 */
    @Test
    fun `core data 里秒到毫秒只有一处换算`() {
        val main = RepoFiles.file("core/data/src/main")
        val files = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size > 100, "待扫描的源码目录不对：$main")
        val bareMultiply = Regex("""[A-Za-z_)\]]\s*\*\s*1_?000L""")
        val secondConstant = Regex("""=\s*1_?000L\b""")
        val offenders = files
            .filter { it.name != "EpochSeconds.kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> bareMultiply.containsMatchIn(line) || secondConstant.containsMatchIn(line) || "multiplyExact(" in line }
                    .map { (index, line) -> "${RepoFiles.relativePath(file)}:${index + 1}: ${line.trim()}" }
            }
        assertEquals(emptyList(), offenders)
    }
}
