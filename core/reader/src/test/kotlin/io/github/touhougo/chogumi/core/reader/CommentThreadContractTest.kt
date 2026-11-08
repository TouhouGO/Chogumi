package io.github.touhougo.chogumi.core.reader

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 契约测试：保证通用社区楼层呈现组件 [CommentThreadItem] 严格遵守核心排版与渲染契约：
 * 1. 楼层正文经 [FoldableText] 渲染、由它调用 [BBCodeText]（禁止退化为裸 Text）；
 * 2. 贴贴（楼层与动态面板共用的 ReactionBar.kt）表情必须使用 [reactionDisplayOf] 分流，未知 value 必须显式展示原始编号而非空白；
 * 3. 渲染已识别贴贴表情必须走共享组件 [SmileyImage]；
 * 4. 楼中楼子楼之间**不画**分割线（原型 `.thread` 只有左侧细线，截图 21；也符合 `containment.md` 对
 *    individual items 的指引：Use dividers to group things, not separate individual items.）；
 * 5. 二级回复具备 2dp outlineVariant 树枝连线与缩进；
 * 6. 已删除状态显式处理 CommentState.DELETED 并展示占位文案；
 * 7. 楼层开头的引用经 core:common 的 splitLeadingQuote 拆出，反查只走 resolveQuoteTargets（不自写第二份匹配）；
 * 8. 签名不在楼层里显示，只在 UserCardSheet 里。
 * 动效规格（不写 spring / tween、animateContentSize 带空间规格）由 tools/guards/check_motion.py 守。
 */
class CommentThreadContractTest {

    private fun getCommentThreadItemFile(): File = RepoFiles.file("core/reader/src/main/kotlin/io/github/touhougo/chogumi/core/reader/CommentThreadItem.kt")

    /** 楼层与动态面板共用的贴贴界面（chip、选择器、表情画法）。 */
    private fun getReactionBarFile(): File = RepoFiles.file("core/reader/src/main/kotlin/io/github/touhougo/chogumi/core/reader/ReactionBar.kt")

    @Test
    fun `楼层正文渲染必须显式调用 BBCodeText 且禁止退化为裸 Text`() {
        val file = getCommentThreadItemFile()
        assertTrue(file.exists(), "CommentThreadItem.kt 必须存在于 core/reader/src/main/kotlin/.../reader/")
        val content = file.readText()
        val foldable = File(file.parentFile, "FoldableText.kt").readText()

        // 正文经共用折叠组件 FoldableText 渲染，富文本由它内部的 BBCodeText 负责。
        assertTrue(
            content.contains("FoldableText("),
            "CommentThreadItem.kt 的楼层正文必须经 FoldableText 渲染（折叠判定只此一份）",
        )
        assertTrue(
            foldable.contains("BBCodeText("),
            "FoldableText.kt 必须调用 BBCodeText 渲染正文富文本",
        )
        assertTrue(
            content.contains("BBCodeParser.parse("),
            "CommentThreadItem.kt 必须调用 BBCodeParser.parse 将正文解析为 AST",
        )
    }

    @Test
    fun `贴贴表情使用 reactionDisplayOf 分流且未知 value 必须显示原始编号而非空白`() {
        val file = getReactionBarFile()
        assertTrue(file.exists(), "ReactionBar.kt 必须存在")
        val content = file.readText()

        assertTrue(
            content.contains("reactionDisplayOf("),
            "ReactionBar.kt 必须使用 reactionDisplayOf 进行贴贴表情分流（唯一真源），严禁直接裸拼 reaction.value",
        )
        assertTrue(
            content.contains("ReactionDisplay.Unknown"),
            "ReactionBar.kt 必须显式处理 ReactionDisplay.Unknown 分支",
        )
        assertTrue(
            content.contains("display.value") || content.contains("\${display.value}"),
            "传入无法识别的 reaction value 时，必须显示原始编号而非空白",
        )
    }

    @Test
    fun `渲染已识别表情必须走共享组件 SmileyImage`() {
        val file = getReactionBarFile()
        assertTrue(file.exists(), "ReactionBar.kt 必须存在")
        val content = file.readText()

        assertTrue(
            content.contains("SmileyImage("),
            "ReactionBar.kt 渲染已识别表情必须走共享组件 SmileyImage",
        )
    }

    @Test
    fun `楼层组件与贴贴流程都不判登录，未登录去登录页由页面的登录门负责`() {
        val content = getCommentThreadItemFile().readText()
        assertFalse(content.contains("isLoggedIn") || content.contains("isAuthenticated"), "楼层组件不接收登录态：页面把贴贴回调包在 LoginGate 里")
        val bar = getReactionBarFile().readText()
        assertFalse(bar.contains("isLoggedIn") || bar.contains("isAuthenticated"), "贴贴界面不接收登录态：调用方给的是已过登录门的回调")
        val reactions = File(getCommentThreadItemFile().parentFile, "FloorReactions.kt").readText()
        assertFalse(reactions.contains("isAuthenticated") || reactions.contains("login"), "贴贴流程不判登录：未登录根本到不了这里")
    }

    @Test
    fun `没有动作行：点楼层回复、长按开楼层菜单，去向、可贴表情、能不能改删只由评论区与楼层决定`() {
        val content = getCommentThreadItemFile().readText()
        val floor = content.substringAfter("private fun CommentFloor(").substringBefore("private fun floorMenuActions(")
        assertTrue(floor.contains("replyStyleOf"), "点楼层能不能回复、分不分两层只由 replyStyleOf 决定")
        assertFalse(floor.contains("openUri"), "回复只在 App 里写，楼层不打开网页")
        assertTrue(floor.contains("replyTargetOf("), "回复谁只由 replyTargetOf 决定")
        assertTrue(floor.contains("onLongPress = if (menu != null) picker::openAt"), "长按楼层弹出楼层菜单（贴贴、复制、网页、报告，对人的或自己的楼的编辑、删除）")
        assertTrue(floor.contains("ownFloorActionsOf("), "自己的楼能不能改、能不能删只由 ownFloorActionsOf 决定")
        val menu = content.substringAfter("private fun floorMenuActions(").substringBefore("private fun FloorHeader(")
        assertTrue(menu.contains("BgmWeb.floor(") && menu.contains("floorReportTargetOf("), "菜单的网页地址、报告对象各只由 BgmWeb.floor、floorReportTargetOf 决定")
        assertEquals(1, Regex("""openUri\(""").findAll(menu).count(), "网页只从菜单的「网页」一项打开")
        assertTrue(floor.contains(".bodyGestures("), "楼层的点与长按走正文共用的 bodyGestures")
        val gestures = File(getCommentThreadItemFile().parentFile, "BodyGestures.kt").readText()
        assertTrue(gestures.contains("onClick(label = tapLabel)"), "点楼层回复必须有读屏动作")
        assertTrue(gestures.contains("onLongClick(label = longPressLabel)"), "长按开楼层菜单必须有读屏动作")
        assertTrue(content.contains("reactionPalette = remember(surface) { surface?.let(::reactionsOf)"), "能贴哪些表情只由 reactionsOf 决定")
    }

    @Test
    fun `楼中楼子楼之间不画分割线`() {
        val file = getCommentThreadItemFile()
        assertTrue(file.exists(), "CommentThreadItem.kt 必须存在")
        val content = file.readText()

        assertFalse(content.contains("HorizontalDivider("), "楼层组件里不得出现 HorizontalDivider：子楼之间没有分割线")
    }

    @Test
    fun `引用拆分与反查只走 core common 的唯一实现，签名只在用户卡片里`() {
        val content = getCommentThreadItemFile().readText()
        assertTrue(content.contains("splitLeadingQuote("), "楼层开头的引用必须经 splitLeadingQuote 拆出")
        assertTrue(content.contains("resolveQuoteTargets("), "引用反查必须走 resolveQuoteTargets")
        assertFalse(content.contains("Regex("), "楼层组件不得自写正则解析引用或正文")
        assertFalse(content.contains("UserCardSheet("), "用户卡片开没开属于屏幕，不在楼层这一行里画（见 UserCards.kt）")
        assertFalse(content.contains("creatorSign"), "签名不得在楼层里显示，只在用户卡片里")
        val cards = File(getCommentThreadItemFile().parentFile, "UserCards.kt").readText()
        assertTrue(cards.contains("UserCardSheet("), "点头像必须弹出 UserCardSheet")
        assertTrue(
            Regex("""creatorSign""").findAll(cards).count() == 1,
            "creatorSign 只能交给用户卡片",
        )
    }

    @Test
    fun `二级回复的树枝线宽度取 ReplyTreeLineWidth`() {
        val content = getCommentThreadItemFile().readText()
        assertTrue(
            content.contains("ReplyTreeLineWidth.toPx()"),
            "二级回复树枝线宽度必须取 ReplyTreeLineWidth",
        )
    }

    @Test
    fun `已删除状态必须渲染该回复不可见占位符`() {
        val file = getCommentThreadItemFile()
        assertTrue(file.exists(), "CommentThreadItem.kt 必须存在")
        val content = file.readText()

        assertTrue(
            content.contains("comment_deleted"),
            "已删除状态必须展示统一占位文案（引用 R.string.comment_deleted）",
        )
        assertTrue(
            content.contains("CommentState.DELETED"),
            "CommentThreadItem.kt 必须显式处理 CommentState.DELETED 状态",
        )
    }

    @Test
    fun `闪烁的楼 id 以读取函数传入_坐标回调不写进重组状态`() {
        val thread = getCommentThreadItemFile().readText()
        val floors = File(getCommentThreadItemFile().parentFile, "CommentFloors.kt").readText()
        assertTrue(thread.contains("flashingId: () -> Int?"), "闪烁 id 以函数传入，开始与结束只让落点那层楼重组")
        assertTrue(floors.contains("flashingId = { jump.flashingId }"), "楼层列表不在组合里读闪烁 id")
        val reactions = getReactionBarFile().readText()
        assertFalse(reactions.contains("onGloballyPositioned"), "贴贴的坐标回调用 onPlaced，滚动时不逐帧触发")
        assertFalse(reactions.contains("scale(pop.value)"), "弹跳缩放在绘制阶段读动画值（graphicsLayer）")
    }
}
