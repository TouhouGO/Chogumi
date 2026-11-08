package io.github.touhougo.chogumi.core.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeNode

/**
 * BBCode 的块级插件：把正文里某种约定格式的块整块换成插件自己的界面。
 *
 * 渲染器（[BBCodeText]）只认通用 BBCode；社区组件写进正文的约定格式（如贴贴投票的 `[list]`）不进渲染器，
 * 全在各自的插件里：格式变了只改插件。插件认不出时这一块照常按 BBCode 画，不会露出半截。
 * 由正文所在的地方经 [LocalBBCodeBlockPlugin] 提供（插件常绑着那里的数据，如这一楼的贴贴）；引用块里的正文不交给插件。
 */
interface BBCodeBlockPlugin {

    /** 认 [list]：认得出返回这一块的画法，认不出 null。在组合之外调用，只做格式判断。 */
    fun claim(list: BBCodeNode.ListBlock): BBCodeBlockContent?
}

/**
 * 插件认下的一块。实现用 data class：渲染器按相等性复用排好的行，内容（含绑定的数据）变了才重排。
 * 独占一行、占满栏宽；在可折叠的正文里不会被折叠线切开。
 */
interface BBCodeBlockContent {

    @Composable
    fun Content()
}

/** 当前正文可用的块级插件；没有时 null（列表一项一行）。 */
val LocalBBCodeBlockPlugin = compositionLocalOf<BBCodeBlockPlugin?> { null }
