package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.TopicType
import io.github.touhougo.chogumi.core.model.monoTypeSpec
import io.github.touhougo.chogumi.core.ui.common.LocalInAppLinkHandler

private val IN_APP_HOSTS = setOf(
    "bgm.tv", "www.bgm.tv", "m.bgm.tv", "next.bgm.tv",
    "bangumi.tv", "www.bangumi.tv",
    "chii.in",
)

/** `/group/` 下不是小组的网页（分类、发现、我的小组……）：App 里没有，去浏览器。 */
private val GROUP_SITE_PAGES = setOf("category", "discover", "my", "my_topic", "my_reply", "search", BgmWeb.TOPIC)

/** 用户名与小组名的字符：字母、数字、下划线、连字符；别的写法（`..`、带点或百分号）交给浏览器，不拼进接口路径。 */
private val NAME_PATTERN = Regex("[A-Za-z0-9_-]+")

/**
 * bgm.tv 系链接 → App 内的路由；不是站内链接或这个页面 App 里没有时为 null（去浏览器）。
 * 「一个链接能不能在 App 内打开、打开哪一页」全仓只由这里回答：BBCode 正文、动态里的链接、设置页的调试跳转都走它。
 *
 * 认的形式：`https://bgm.tv/…`、协议相对的 `//bgm.tv/…`、站内相对路径 `/…`（BBCode 里常见）；
 * 路径只认 `/calendar`（每日放送）、`/subject/{id}`、`/subject/topic/{id}`、`/group/topic/{id}`、`/group/{name}`、`/group/all`、`/person/{id}`、
 * `/character/{id}`、`/ep/{id}`、`/user/{name}`、`/index/{id}`、`/blog/{id}`、`/pm/conversation/{id}.chii`（短信会话，`?thread=` 不算），
 * 忽略查询串；id 必须是正整数。路径段名与拼地址的 [BgmWeb] 共用。
 * 锚点只认楼层锚点（[BgmWeb.floor]）：话题、单集、日志带上它就进页落到那层楼，其余页面忽略锚点。
 */
fun inAppRoute(url: String): Route? {
    val text = url.trim()
    val path = when {
        text.startsWith("https://") || text.startsWith("http://") || text.startsWith("//") -> {
            val rest = text.substringAfter("//")
            val host = rest.substringBefore('/')
            if (host == rest || host.lowercase() !in IN_APP_HOSTS) return null
            rest.substring(host.length)
        }
        text.startsWith("/") -> text
        else -> return null
    }
    val segments = path.substringBefore('#').substringBefore('?').split('/').filter { it.isNotBlank() }
    val focusPostId = path.substringAfter('#', "").takeIf { it.startsWith(BgmWeb.POST_ANCHOR) }
        ?.removePrefix(BgmWeb.POST_ANCHOR)?.toIntOrNull()?.takeIf { it > 0 }
    return when {
        segments.size == 3 && segments[1] == BgmWeb.TOPIC -> {
            val type = TopicType.entries.firstOrNull { BgmWeb.topicParent(it) == segments[0] }
            val id = segments[2].toIntOrNull()?.takeIf { it > 0 }
            if (type != null && id != null) Route.Topic(type, id, focusPostId = focusPostId) else null
        }
        segments.size == 3 && segments[0] == BgmWeb.PM && segments[1] == BgmWeb.PM_CONVERSATION ->
            segments[2].removeSuffix(BgmWeb.PAGE_SUFFIX).toIntOrNull()?.takeIf { it > 0 }?.let(Route::PmConversation)
        segments.size == 1 -> if (segments[0] == BgmWeb.CALENDAR) Route.Calendar else null
        segments.size != 2 -> null
        segments[0] == BgmWeb.USER -> segments[1].takeIf(NAME_PATTERN::matches)?.let(Route::UserProfile)
        segments[0] == BgmWeb.GROUP -> when (segments[1]) {
            BgmWeb.ALL_GROUPS -> Route.AllGroups
            in GROUP_SITE_PAGES -> null
            else -> segments[1].takeIf(NAME_PATTERN::matches)?.let(Route::Group)
        }
        else -> segments[1].toIntOrNull()?.takeIf { it > 0 }?.let { id ->
            val mono = MonoType.entries.firstOrNull { monoTypeSpec(it).webPath == segments[0] }
            when {
                mono != null -> Route.MonoDetails(mono, id)
                segments[0] == BgmWeb.SUBJECT -> Route.SubjectDetails(id)
                segments[0] == BgmWeb.EPISODE -> Route.Episode(id, focusPostId)
                segments[0] == BgmWeb.INDEX -> Route.IndexDetails(id)
                segments[0] == BgmWeb.BLOG -> Route.BlogDetails(id, focusPostId)
                else -> null
            }
        }
    }
}

/** 打开一个链接：站内页面交给 App 内导航（[LocalInAppLinkHandler]），其余去浏览器。 */
@Composable
fun rememberLinkOpener(): (String) -> Unit {
    val inApp = LocalInAppLinkHandler.current
    val browser = LocalUriHandler.current
    return remember(inApp, browser) {
        { url ->
            val route = inAppRoute(url)
            if (route != null && inApp != null) inApp(route) else browser.openUri(url)
        }
    }
}
