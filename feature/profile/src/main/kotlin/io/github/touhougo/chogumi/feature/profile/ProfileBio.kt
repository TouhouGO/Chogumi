package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeAst
import io.github.touhougo.chogumi.core.reader.BBCodeText
import io.github.touhougo.chogumi.core.reader.ReaderTypography

/**
 * 简介（BBCode）：ⓘ 面板里的一张卡，全文展开，长了在面板里滚。
 *
 * bio 走 BBCode 渲染，同一套渲染器也服务超展开与日志。@、链接、剧透照常点。
 *
 * 外观是原型 `.bio`：`surfaceContainer` 卡片、圆角 12dp（M3 shape medium）、内边距上下 14 左右 16；
 * 正文取阅读排版的紧凑字阶（14sp / 行高 22，原型 `.bio .txt{line-height:22px}`，M3 bodyMedium 行高为 20——
 * 我的设计决定（not in M3），取自原型）。
 */
@Composable
internal fun ProfileBio(
    ast: BBCodeAst,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        BBCodeText(ast = ast, style = ReaderTypography.compact, onUserClick = onUserClick)
    }
}
