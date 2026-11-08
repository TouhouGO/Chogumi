package io.github.touhougo.chogumi.feature.monodetails

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 契约测试：人物与角色详情的吐槽箱与日志、目录的留言、单集评论是同一套评论区。
 *
 * 被测的是 [io.github.touhougo.chogumi.feature.monodetails.MonoDetailsScreen]：
 * 1. 楼层（占位、重试、楼层与空态、分割线、楼层号）交给 core:reader 的 `floorCommentsBody`，页面不自己逐条画楼层；
 * 2. 评论区类型是 `CommentSurface.Mono`（能不能回复、分不分两层由它决定）；
 * 3. 回复在 App 里写：点楼层与「回复」FAB 打开与帖子页同一个编辑器面板；
 * 4. 标题走全仓共用的 SectionHeader，失败的重试接 retryComments。
 *
 * 楼层内部的契约（富文本、贴贴、楼中楼不画分割线……）由 core:reader 的 CommentThreadContractTest 钉住。
 */
class MonoCommentsSectionContractTest {

    private fun screen(): String {
        val file: File = RepoFiles.file("feature/monodetails/src/main/kotlin/io/github/touhougo/chogumi/feature/monodetails/MonoDetailsScreen.kt")
        assertTrue(file.exists(), "MonoDetailsScreen.kt 必须存在")
        return file.readText()
    }

    @Test
    fun `吐槽箱的楼层交给公共评论区段落，页面不自己逐条画楼层`() {
        val content = screen()
        assertTrue(content.contains("floorCommentsBody("), "吐槽箱的楼层必须走 floorCommentsBody")
        assertFalse(content.contains("CommentThreadItem("), "页面不自己画楼层：楼层组件只经评论区段落进来")
        assertFalse(content.contains("CommentFloorDivider("), "楼层分割线由评论区段落画")
        assertTrue(content.contains("CommentSurface.Mono("), "必须向评论区传角色 / 人物的评论区类型")
    }

    @Test
    fun `吐槽与回复在 App 里写：点楼层和回复 FAB 打开同一个编辑器面板`() {
        val content = screen()
        assertTrue(content.contains("onReply = reply"), "点楼层回复必须接到编辑器")
        assertTrue(Regex("""\bReplyFab\s*[({]""").containsMatchIn(content), "页面右下要有写吐槽的「回复」FAB")
        assertTrue(content.contains("ReplyComposerSheet("), "编辑器面板与帖子页同一个")
    }

    @Test
    fun `标题与错误重试规范处理`() {
        val content = screen()
        assertTrue(
            content.contains("title = stringResource(R.string.mono_comments_title)"),
            "吐槽箱标题必须走 SectionHeader",
        )
        assertTrue(content.contains("onRetry = viewModel::retryComments"), "吐槽箱取失败时的重试必须接 retryComments")
    }
}
