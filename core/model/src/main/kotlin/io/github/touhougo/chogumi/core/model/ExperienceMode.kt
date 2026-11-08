package io.github.touhougo.chogumi.core.model

/**
 * 体验模式：完整 / 专注。
 *
 * 「专注」用于屏蔽社区，只专注于记录和追番本身。模式本身只是一个档位，
 * **哪些功能可见由 [appFeatures] 唯一决定**，任何页面都不得自行判断模式。
 */
enum class ExperienceMode { FULL, FOCUS }

/**
 * 功能可见性开关组（由 [appFeatures] 派生，界面只读这些布尔值）。
 *
 * @property community 社区功能：超展开、动态与他人互动（贴贴/回复/表情反应）、条目讨论、吐槽区、好友、通知。
 * @property publicRatings 他人的评分/排名数字/评分分布。
 *   排行榜及排序功能恒可用；当该值为 false 时只隐藏分数与排名数字。
 *   用户自己的评分与短评不属于此项，任何模式下都保留。
 * @property coverScores 海报封面右下角的分数角标。属于 [publicRatings] 的一部分：公共评分关掉时一定不画；
 *   公共评分开着时还可以单独关掉封面上的这一处，详情页、列表行里的分数照常显示。
 * @property friendMarks 名字旁、头像底边的「好友」小牌。属于 [community] 的一部分：专注模式下一定不画；
 *   完整模式下还可以单独关掉。
 */
data class AppFeatures(
    val community: Boolean,
    val publicRatings: Boolean,
    val coverScores: Boolean,
    val friendMarks: Boolean,
)

/**
 * 体验模式 + 公共评分开关 + 封面评分开关 + 好友标识开关 → 功能可见性 —— **全仓唯一的可见性判定函数**。
 *
 * 专注模式（[ExperienceMode.FOCUS]）只管社区功能（community = (mode == FULL)），公共评分由独立开关
 * [showPublicRatings] 决定（两种模式下都可开关）。封面上的分数角标还要 [showCoverScores] 开着：
 * 它是公共评分里的一处，公共评分关掉时封面评分开关不起作用。
 * 用户自己的评分与短评恒保留，不属于 publicRatings。好友小牌是社区里的一处：专注模式下 [showFriendMarks] 不起作用。
 *
 * | mode | showPublicRatings | showCoverScores | community | publicRatings | coverScores |
 * |---|---|---|---|---|---|
 * | FULL | true | true | true | true | true |
 * | FULL | true | false | true | true | false |
 * | FULL | false | 任意 | true | false | false |
 * | FOCUS | 同上 | 同上 | false | 同上 | 同上 |
 *
 * friendMarks = community 且 [showFriendMarks]。
 */
fun appFeatures(
    mode: ExperienceMode,
    showPublicRatings: Boolean,
    showCoverScores: Boolean,
    showFriendMarks: Boolean,
): AppFeatures = AppFeatures(
    community = mode == ExperienceMode.FULL,
    publicRatings = showPublicRatings,
    coverScores = showPublicRatings && showCoverScores,
    friendMarks = mode == ExperienceMode.FULL && showFriendMarks,
)
