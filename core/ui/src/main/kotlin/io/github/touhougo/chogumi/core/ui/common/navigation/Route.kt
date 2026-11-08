package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.compose.runtime.Immutable
import androidx.navigation3.runtime.NavKey
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TopicType
import kotlinx.serialization.Serializable

/**
 * App 的全部目的地。顶级目的地（[TopLevelDestination]）是 data object，其余带参数；
 * 都可序列化，返回栈随进程死亡一起保存。
 */
@Immutable
sealed interface Route : NavKey {

    @Serializable
    data object Home : Route

    @Serializable
    data object Explore : Route

    /** 进度（个人收藏管理） */
    @Serializable
    data object Progress : Route

    /** 社区（小组、超展开与通知） */
    @Serializable
    data object Community : Route

    /** 自己的空间 */
    @Serializable
    data object Profile : Route

    @Serializable
    data object Login : Route

    /** 动态（首页「好友在看」入口进入） */
    @Serializable
    data object Feed : Route

    /** 每日放送 */
    @Serializable
    data object Calendar : Route

    /** 设置的一级菜单：几个分类，加上图片缓存、关于与退出登录。 */
    @Serializable
    data object Settings : Route

    /** 设置的一个分类（二级页）。 */
    @Serializable
    data class SettingsPage(val section: SettingsSection) : Route

    /** 设置 › 个人资料：头像、昵称、签名、自我介绍。 */
    @Serializable
    data object ProfileSettings : Route

    /** 设置 › 关于：版本、许可与源码。 */
    @Serializable
    data object About : Route

    /**
     * 搜索。条件只从 [criteria] 一个入口进——详情页点标签、探索页的入口与货架「查看全部」
     * 都在调用侧先转成 [SearchCriteria] 再导航。
     * [title] 是这组条件在探索页上的名字（「近期热门」「排行榜」或某个已固定的组合），
     * 改动条件即失效，判定在 SearchForm 的 reduce 里（唯一一处）。
     */
    @Serializable
    data class Search(
        val criteria: SearchCriteria? = null,
        val title: String? = null,
        val openFilters: Boolean = false,
    ) : Route

    /**
     * 别人的空间：压在栈上的新目的地。自己的空间是顶级目的地 [Profile]——
     * 底栏选中态与返回栈都认它这个 key，不能用带用户名的路由代替。
     */
    @Serializable
    data class UserProfile(val username: String) : Route

    @Serializable
    data class SubjectDetails(val subjectId: Int) : Route

    /** 条目的全部关联作品，按权重组分段 */
    @Serializable
    data class SubjectRelations(val subjectId: Int) : Route

    /** 条目讨论版：话题列表 */
    @Serializable
    data class SubjectTopics(val subjectId: Int) : Route

    /**
     * 一个话题（帖子页）：作品讨论版或小组的，[type] 原样带给页面，不按它分路由。
     * [draftPostId] 非 null 是从草稿进来：进页就打开回复那层楼（0 = 话题本身）的草稿接着写。
     * [focusPostId] 非 null 是要看某层楼（通知、带楼层锚点的链接）：进页就落到那层楼并闪一下。
     */
    @Serializable
    data class Topic(val type: TopicType, val topicId: Int, val draftPostId: Int? = null, val focusPostId: Int? = null) : Route

    /** 单集讨论：这一集的标题与简介，下面是评论楼层（超展开的单集、站内 `/ep/{id}` 链接进来）；[focusPostId] 同 [Topic] */
    @Serializable
    data class Episode(val episodeId: Int, val focusPostId: Int? = null) : Route

    /** 我的帖子：我发起的、我回复的（小组帖子）与草稿三页 */
    @Serializable
    data object MyTopics : Route

    /** 屏蔽的小组（设置里进）：本机名单，逐个取消 */
    @Serializable
    data object MutedGroups : Route

    /** 屏蔽的用户（设置里进）：当前账号的绝交名单，逐个取消绝交 */
    @Serializable
    data object BlockedUsers : Route

    /** 小组页：简介、加入或退出、这个小组的帖子、发帖；[name] 是小组的英文键 */
    @Serializable
    data class Group(val name: String) : Route

    /** 全部小组，按成员数排 */
    @Serializable
    data object AllGroups : Route

    /** 条目评论：用户日志列表，点进 [BlogDetails] */
    @Serializable
    data class SubjectReviews(val subjectId: Int) : Route

    /** 收录这个条目的目录，点进 [IndexDetails] */
    @Serializable
    data class SubjectIndexes(val subjectId: Int) : Route

    /** 角色或人物详情 */
    @Serializable
    data class MonoDetails(val monoType: MonoType, val id: Int) : Route

    /** 角色出演 / 人物参与的全部作品 */
    @Serializable
    data class MonoWorks(val monoType: MonoType, val id: Int) : Route

    /** 角色或人物的全部关联：角色关联角色，人物关联人物 */
    @Serializable
    data class MonoRelations(val monoType: MonoType, val id: Int) : Route

    /** 人物出演的全部角色 */
    @Serializable
    data class PersonCharacters(val personId: Int) : Route

    /** 某用户的日志列表 */
    @Serializable
    data class UserBlogs(val username: String) : Route

    /** 日志详情：正文与留言；[focusPostId] 同 [Topic] */
    @Serializable
    data class BlogDetails(val blogId: Int, val focusPostId: Int? = null) : Route

    /** 某用户的目录，「创建的 / 收藏的」两页；两页的条数取自资料头的统计 */
    @Serializable
    data class UserIndexes(val username: String, val createdCount: Int, val collectedCount: Int) : Route

    /** 目录详情：描述、条目（含作者评语）、留言 */
    @Serializable
    data class IndexDetails(val indexId: Int) : Route

    /** 一个短信会话（和一个人的全部短信）：[conversationId] 是会话 id，既不是对方 uid，也不是信的 id */
    @Serializable
    data class PmConversation(val conversationId: Int) : Route

    /** 写信：[username] 为 null 时先在页内选收件人；有收件人时就是和该用户的会话（找不到会话写第一封），打开即弹出编辑器 */
    @Serializable
    data class PmWrite(val username: String? = null) : Route

    /** 某用户的完整生涯统计；[username] 为 null 是自己，[subjectType] 是进入时选中的类型 */
    @Serializable
    data class UserCareer(val username: String?, val subjectType: SubjectType) : Route
}
