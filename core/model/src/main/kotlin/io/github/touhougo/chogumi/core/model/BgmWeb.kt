package io.github.touhougo.chogumi.core.model

import io.github.touhougo.chogumi.core.foundation.BGM_SITE
import java.net.URLEncoder

/**
 * bgm.tv 网页地址，**全 App 唯一的拼法**（实体 → 网页）。反方向（网页 → App 内页面）只在 core:ui 的 `inAppRoute`，
 * 两边共用这里的路径段名。路径里来自用户的段（用户名、小组名）一律按路径段编码。
 */
object BgmWeb {
    const val SUBJECT = "subject"
    const val TOPIC = "topic"
    const val USER = "user"
    const val INDEX = "index"
    const val BLOG = "blog"
    const val GROUP = "group"
    const val EPISODE = "ep"
    const val PM = "pm"
    const val PM_CONVERSATION = "conversation"

    /** 每日放送页（`/calendar`）的路径段。 */
    const val CALENDAR = "calendar"

    /** 旧站网页地址的后缀（`/pm/conversation/{id}.chii`）。 */
    const val PAGE_SUFFIX = ".chii"

    /** 楼层锚点（`#post_<楼 id>`）的前缀：网页上每层楼（主楼、楼中楼）都是这个锚点。 */
    const val POST_ANCHOR = "post_"

    /** 全部小组页（`/group/all`）的路径段。 */
    const val ALL_GROUPS = "all"

    /** 「时间胶囊」全站动态页。 */
    const val TIMELINE = "$BGM_SITE/timeline"

    fun subject(id: Int) = "$BGM_SITE/$SUBJECT/$id"

    /** 每日放送。 */
    fun calendar() = "$BGM_SITE/$CALENDAR"
    fun subjectStats(id: Int) = "${subject(id)}/stats"

    /** 话题挂在哪一类页面下的路径段（`/subject/topic/{id}`、`/group/topic/{id}`）。 */
    fun topicParent(type: TopicType) = when (type) {
        TopicType.SUBJECT -> SUBJECT
        TopicType.GROUP -> GROUP
    }

    fun topic(type: TopicType, id: Int) = "$BGM_SITE/${topicParent(type)}/$TOPIC/$id"
    fun episode(id: Int) = "$BGM_SITE/$EPISODE/$id"

    /** 角色、人物页（路径段由规格表的 [MonoTypeSpec.webPath] 给出）。 */
    fun mono(type: MonoType, id: Int) = "$BGM_SITE/${monoTypeSpec(type).webPath}/$id"
    fun blog(id: Int) = "$BGM_SITE/$BLOG/$id"
    fun index(id: Int) = "$BGM_SITE/$INDEX/$id"
    fun group(name: String) = "$BGM_SITE/$GROUP/${segment(name)}"

    /** [page] 上 [postId] 那层楼；[postId] 不是正数（没有具体楼层）时就是 [page] 本身。 */
    fun floor(page: String, postId: Int) = if (postId > 0) "$page#$POST_ANCHOR$postId" else page

    /**
     * [surface] 里 [postId] 那层楼在网页上的地址（帖子正文 [isPost] 是页面本身）；null 表示网页上没有这层楼的地址：
     * 作品吐槽挂在收藏上、动态的回复只在动态里，网页都没有单独的楼层锚点。
     */
    fun floor(surface: CommentSurface, postId: Int, isPost: Boolean): String? {
        val page = when (surface) {
            is CommentSurface.Topic -> topic(surface.type, surface.topicId)
            is CommentSurface.Episode -> episode(surface.episodeId)
            is CommentSurface.Blog -> blog(surface.blogId)
            is CommentSurface.Index -> index(surface.indexId)
            is CommentSurface.Mono -> mono(surface.type, surface.id)
            is CommentSurface.SubjectComments, is CommentSurface.Timeline -> return null
        }
        return floor(page, if (isPost) 0 else postId)
    }

    /** 用户主页；[userKey] 由 [userKeyOf] 得出（网页两种都认）。 */
    fun user(userKey: String) = "$BGM_SITE/$USER/${segment(userKey)}"

    /** 用户的时间线页（网页分页，[page] 从 1 起）。 */
    fun userTimeline(userKey: String, page: Int) = "${user(userKey)}/timeline" + if (page > 1) "?page=$page" else ""

    /** 超展开组件（用户脚本）的介绍页。 */
    fun devApp(id: Int) = "$BGM_SITE/dev/app/$id"

    /** 给某个用户写短信。 */
    fun privateMessage(userId: Int) = "$BGM_SITE/pm/compose/$userId.chii"

    /** 短信会话列表（网页分页，[page] 从 1 起；没有更多会话的页是空的）。 */
    fun pmInbox(page: Int) = "$BGM_SITE/$PM/inbox.chii?page=$page"

    /** 一个短信会话（整段会话一页给全）。 */
    fun pmConversation(id: Int) = "$BGM_SITE/$PM/$PM_CONVERSATION/$id$PAGE_SUFFIX"

    /** 写信、回复、新主题共用的提交地址（网页的表单都提交到这里）。 */
    const val PM_CREATE = "$BGM_SITE/$PM/create.chii"

    /** 网页顶栏读的未读数（JSON：未读通知、未读短信与「全部已读」的地址）。 */
    const val WEB_NOTIFY = "$BGM_SITE/json/notify"

    // 网页操作链接：要带登录 Cookie 打开，[formhash] 是那个网页会话的表单校验值（链接上的 `gh=`）。

    /** 移除作品收藏。 */
    fun subjectUncollect(id: Int, formhash: String) = "${subject(id)}/remove?gh=$formhash"

    /** 取消收藏角色 / 人物。 */
    fun monoUncollect(type: MonoType, id: Int, formhash: String) = "${mono(type, id)}/erase_collect?gh=$formhash"

    /** 从自己的短信里删掉一封（不是撤回：对方那边看不看得到，网页没有说法）。 */
    fun pmErase(messageId: Int, formhash: String) = "$BGM_SITE/$PM/erase/$messageId.chii?gh=$formhash"

    /** 解除与 [userId] 的好友关系。 */
    fun unfriend(userId: Int, formhash: String) = "$BGM_SITE/disconnect/$userId?gh=$formhash"

    private fun segment(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
