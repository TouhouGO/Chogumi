package io.github.touhougo.chogumi.core.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import io.github.touhougo.chogumi.core.model.ReadingTextSize

/**
 * 阅读排版的**唯一出处**：帖子正文、楼层、简介、个人简介、动态正文、引用条、楼层头部的字号 / 行高 / 字距都从这里取，
 * 调用处不各写一份 `typography.bodyMedium.copy(...)`（各写一份时左边改了右边还要改，最容易漏）。
 *
 * 取值以 M3 字阶为底（`references/tokens.md`），改动处均为**设计决定（not in M3）**，理由：
 * - **字距 0**：M3 body 的 0.2–0.5sp 字距是给拉丁字母定的，中文字形本身方正、字面饱满，再加字距整段显得松散；
 * - **行高 ≈1.6×**：M3 对正文建议约 1.5×（`typography.md`「Readability」）；中文没有升部降部留白，同样行高显得挤，
 *   成熟的中文社区客户端正文行高普遍在 1.6–1.75×；
 * - **字号只取 M3 字阶**（CI 禁止写死字号）：帖子正文与楼层正文的字阶由设置「阅读字号」（[LocalReadingTextSize]）选，
 *   映射只在 [readingBase] 写一次；默认「标准」= `bodyLarge` 16sp（14sp 可读性差）。
 *   行高按字号的倍数派生（楼层 [COMMENT_LINE_RATIO]、帖子正文 [ARTICLE_LINE_RATIO]），换档自动跟上，帖子正文行高再松一档以示主次。
 */
object ReaderTypography {
    /** 帖子正文（话题 #1、日志）：阅读字号档位的字阶，行高 ×[ARTICLE_LINE_RATIO]（标准档 16sp / 27sp）。 */
    val article: TextStyle
        @Composable get() = readingBase().let { it.copy(lineHeight = it.fontSize * ARTICLE_LINE_RATIO, letterSpacing = 0.sp) }

    /** 楼层正文（话题回复、吐槽、日志 / 目录留言、角色人物吐槽）：同一字阶，行高 ×[COMMENT_LINE_RATIO]（标准档 16sp / 25.6sp）。 */
    val comment: TextStyle
        @Composable get() = readingBase().let { it.copy(lineHeight = it.fontSize * COMMENT_LINE_RATIO, letterSpacing = 0.sp) }

    /** 卡片里的短正文（作品 / 角色简介、个人简介、动态正文、动态引用）：14sp / 22sp。 */
    val compact: TextStyle
        @Composable get() = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp, letterSpacing = 0.sp)

    /** 楼层里的引用条：14sp / 20sp（M3 `bodyMedium`，字距 0），比正文低一档。 */
    val quote: TextStyle
        @Composable get() = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 0.sp)

    /** 楼层头部的昵称：M3 `labelLarge`（14sp Medium）。 */
    val author: TextStyle
        @Composable get() = MaterialTheme.typography.labelLarge

    /** 楼层头部的时间、楼层号：12sp / 16sp，字距 0（M3 `bodySmall` 的 0.4sp 让数字日期显得稀）。 */
    val meta: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.sp)
}

/** 楼层正文行高 / 字号（≈1.6×，设计决定 not in M3，理由见 [ReaderTypography]）。 */
private const val COMMENT_LINE_RATIO = 1.6f

/** 帖子正文行高 / 字号（≈1.7×，比楼层再松一档）。 */
private const val ARTICLE_LINE_RATIO = 1.7f

/** 阅读字号档位 → M3 字阶：**全仓唯一映射**。 */
@Composable
private fun readingBase(): TextStyle = when (LocalReadingTextSize.current) {
    ReadingTextSize.SMALL -> MaterialTheme.typography.bodyMedium
    ReadingTextSize.STANDARD -> MaterialTheme.typography.bodyLarge
    ReadingTextSize.LARGE -> MaterialTheme.typography.titleLarge
}
