package io.github.touhougo.chogumi.core.model

/**
 * 社区帖子流（超展开、小组新帖）里的一帖。帖子是哪一种只在数据层映射时判一次，拆成下面这些字段；
 * 界面只照字段画，不按种类分支。
 *
 * @property key 列表 key：种类前缀 + id（小组帖、作品讨论、单集、角色、人物的 id 各自编号，会撞）。
 * @property webUrl 这帖的网页（[BgmWeb]）。在 App 里能不能打开、打开哪一页只由 core:ui 的 `inAppRoute` 判。
 * @property title 帖子标题；单集是这一集的标题，角色、人物是名字（都按 [displayNameOf]）。
 * @property episode 单集讨论的那一集：标题前面加集号（集号的写法在界面层）；其余为 null。
 * @property source 帖子挂在哪；null 是列表本身就在这个来源里（小组页里这个小组的帖子），帖子行不画来源。
 * @property author 发帖人；单集、角色、人物的讨论没有发帖人。
 * @property replies 回复数（单集、角色、人物是评论数）。
 * @property updatedAt 最后回复时间，epoch 毫秒；0 表示未知。
 */
data class CommunityTopicBo(
    val key: String,
    val webUrl: String,
    val title: String,
    val episode: EpisodeBo?,
    val source: CommunityTopicSource?,
    val author: UserBo?,
    val replies: Int,
    val updatedAt: Long,
)

/** 一帖挂在哪里：帖子行的来源，以及没有发帖人时左列画什么，都由它决定。 */
sealed interface CommunityTopicSource {
    /** 小组帖：来源是小组名。 */
    data class Group(val group: GroupSummaryBo) : CommunityTopicSource

    /** 作品讨论与单集讨论：来源是作品名；单集没有发帖人，左列是作品封面。 */
    data class Subject(val subject: TopicSubjectBo) : CommunityTopicSource

    /**
     * 角色、人物的吐槽：来源是「角色」「人物」，左列是 [imageUrl]（圆形头像该用的图，同作品详情页的角色、制作人员，
     * 见 `RelatedImagesBo.avatarUrl`）；没有图为 null。
     */
    data class Mono(val type: MonoType, val imageUrl: String?) : CommunityTopicSource
}

/** 超展开的分类（网页超展开顶部的那一排）。 */
enum class RakuenFilter { ALL, GROUP, SUBJECT, EPISODE, CHARACTER, PERSON }

/** 小组帖子的范围：全部小组、我加入的小组的新帖（小组页），我发起的、我回复过的（我的帖子）。 */
enum class GroupTopicScope { ALL, JOINED, CREATED, REPLIED }

/**
 * 一页小组新帖，连同这一页取自哪个范围（没加入小组时退回全部小组，后面的页跟着首页走）。
 * [next] 是下一页从哪接着取，null 是到底了。[failedRuns] 是这一页里没取到的小组数（其余小组的帖子照常在 [items] 里，没取到的下一页再试）。
 */
data class GroupFeedPage(val scope: GroupTopicScope, val items: List<CommunityTopicBo>, val next: GroupFeedCursor?, val failedRuns: Int = 0)

/**
 * 小组新帖取到哪了。全部小组是服务端那条流的 [offset]；我加入的小组是每个小组各一路（[runs]），各自从自己的帖子列表往下取。
 * 首页是 [START]：有哪几路在取首页时才定，之后的页沿用。
 */
data class GroupFeedCursor(val offset: Int, val runs: List<GroupTopicRun>?) {
    companion object {
        val START = GroupFeedCursor(offset = 0, runs = null)
    }
}

/**
 * 我加入的小组的新帖里的一路：[group] 自己的帖子（按最后回复倒序），已取到、还没排进页里的 [buffered]，
 * 下次从哪接着取（[nextOffset]，null 是这个小组取完了）。
 */
data class GroupTopicRun(val group: GroupSummaryBo, val buffered: List<CommunityTopicBo>, val nextOffset: Int?)

/** 一帖所在的小组；不是小组帖时为 null。长按能不能屏蔽、屏蔽谁，都看它。 */
fun groupOf(topic: CommunityTopicBo): GroupSummaryBo? = (topic.source as? CommunityTopicSource.Group)?.group

/**
 * 屏蔽的小组的帖子不出现（唯一判定）：按小组的英文键比对，别的来源不受影响。只用在社区 tab 的两页帖子流：
 * 这个小组自己的页、话题页、链接、通知、我的帖子照常能进。
 */
fun withoutMutedGroups(topics: List<CommunityTopicBo>, muted: Set<String>): List<CommunityTopicBo> =
    if (muted.isEmpty()) topics else topics.filterNot { groupOf(it)?.name in muted }

/**
 * 小组页「我加入的小组的新帖」只留 [joined] 这些小组的帖子（唯一判定），[joined] 是顶部那一行用的实时加入名单。
 * 帖子流按取首页那一刻的加入名单分路取，之后退出的小组的帖子还在已取到的页里（续页也接着取它）。
 * [joined] 为 null（名单还没到）时不筛；已知为空时不留任何加入流的旧帖。
 */
fun onlyJoinedGroups(topics: List<CommunityTopicBo>, joined: Set<String>?): List<CommunityTopicBo> =
    if (joined == null) topics else topics.filter { groupOf(it)?.name in joined }

/** 实时名单清空要重取全部流；名单里多了小组（刚加入）要重取，新小组才有自己的一路。 */
fun groupFeedNeedsReload(scope: GroupTopicScope?, previous: Set<String>?, joined: Set<String>?): Boolean =
    (scope == GroupTopicScope.JOINED && joined != null && joined.isEmpty()) ||
        (previous != null && joined != null && !previous.containsAll(joined))
