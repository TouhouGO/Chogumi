package io.github.touhougo.chogumi.core.ui.common.navigation

import io.github.touhougo.chogumi.core.model.IndexCategory
import io.github.touhougo.chogumi.core.model.IndexTarget
import io.github.touhougo.chogumi.core.model.indexCategoryOf
import io.github.touhougo.chogumi.core.model.monoTypeSpec

/**
 * [indexTargetOf] 回答「这一页能不能收录、收的是什么」。自己的目录里贴链接收录也走它；
 * 链接先经 [inAppRoute] 变成路由。目标回到页面的 Route 只在 app 的接线层完成。
 * 类别的判定在 core:model（角色 / 人物经 [monoTypeSpec]，话题经 [indexCategoryOf]），这里只把路由的参数对上。
 */
fun indexTargetOf(route: Route): IndexTarget? = when (route) {
    is Route.SubjectDetails -> IndexTarget(IndexCategory.SUBJECT, route.subjectId)
    is Route.MonoDetails -> IndexTarget(monoTypeSpec(route.monoType).indexCategory, route.id)
    is Route.Episode -> IndexTarget(IndexCategory.EPISODE, route.episodeId)
    is Route.BlogDetails -> IndexTarget(IndexCategory.BLOG, route.blogId)
    is Route.Topic -> IndexTarget(indexCategoryOf(route.type), route.topicId)
    else -> null
}

/** 一段文字里认出的一个可收录的链接：[url] 是那一段原文（预填输入框用），[target] 是它收录的东西。 */
data class IndexLink(val url: String, val target: IndexTarget)

/**
 * 贴进来的一段文字里能收录的东西（自己的目录里「收录链接」）：第一个经 [inAppRoute] 认得、且 [indexTargetOf] 能收录的链接。
 * 按空白切开逐段试，App 自己分享出去的「标题 + 换行 + 链接」原样贴回来也认；没有这样的链接为 null。
 */
fun indexLinkOf(text: String): IndexLink? =
    text.split(WHITESPACE).firstNotNullOfOrNull { part -> inAppRoute(part)?.let(::indexTargetOf)?.let { IndexLink(part, it) } }

private val WHITESPACE = Regex("\\s+")
