package io.github.touhougo.chogumi.core.reader

import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.SubjectVerb
import io.github.touhougo.chogumi.core.model.TimelineCategory
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.TimelineSegmentBo
import io.github.touhougo.chogumi.core.model.subjectTypeSpec
import io.github.touhougo.chogumi.core.model.timelineCategoryOf
import io.github.touhougo.chogumi.core.res.R

/**
 * 动态页的类别（与网页端动态的分类一一对应，顺序也相同）。
 *
 * 时间线接口不能按类别查，类别由客户端筛已取到的动态（往前翻页找齐一屏在 feature:timeline）。
 * 网页端「更多」里的天窗不在这里：服务端给天窗动态的内容恒为空，筛出来也画不出是哪部作品。
 * 日常里的注册、加入乐园、神秘的行动不属于任何类别，只在「全部」里出现（[TimelinePresentation.filter] 为 null）。
 */
enum class TimelineFilter(@StringRes val labelRes: Int) {
    ALL(R.string.timeline_filter_all),
    STATUS(R.string.timeline_filter_status),
    COLLECT(R.string.timeline_filter_collect),
    PROGRESS(R.string.timeline_filter_progress),
    BLOG(R.string.timeline_filter_blog),
    MONO(R.string.timeline_filter_mono),
    FRIEND(R.string.timeline_filter_friend),
    GROUP(R.string.timeline_filter_group),
    WIKI(R.string.timeline_filter_wiki),
    INDEX(R.string.timeline_filter_index),
    ;

    /** 这一项是否列出 [item]：不展示的动态（[TimelinePresentation.visible]）哪一项都不列，「全部」列出其余一切。 */
    fun accepts(item: TimelineItemBo): Boolean {
        val presentation = presentationOf(item)
        return presentation.visible && (this == ALL || presentation.filter == this)
    }
}

/** 一条动态画成哪种版式（原型 `presentationOf().shape`）。 */
enum class TimelineShape {
    /** 作品行：48×64 封面 + 标题 + 动词胶囊。 */
    SUBJECT,

    /** 一次收藏多部：动词胶囊 + 横排海报。 */
    STRIP,

    /** 吐槽：正文 + 行内表情。 */
    TEXT,

    /** 一行：改签名 / 改昵称 / 注册。 */
    LINE,

    /** 标题卡：48dp 图标块 + 动词 + 标题（日志 / 目录 / 小组）。 */
    TITLE_CARD,

    /** 人物 / 角色：48dp 圆头像。 */
    MONO,

    /** 加好友：名字 + 头像叠放。 */
    AVATARS,
}

/** 动词胶囊的配色档（原型 `.vpill` / `.vpill.done` / `.vpill.drop`）。 */
enum class VerbTone { NORMAL, DONE, MUTED }

/**
 * 一条动态的呈现种类：属于哪个筛选项、画成什么样、动词文案、胶囊配色、
 * 标题卡 / 单行的图标，以及一次收藏多部时的计数文案。
 */
data class TimelinePresentation(
    /** 属于哪个类别；null = 不属于任何类别，只在「全部」里出现。 */
    val filter: TimelineFilter?,
    val shape: TimelineShape,
    @StringRes val verbRes: Int,
    val tone: VerbTone = VerbTone.NORMAL,
    @DrawableRes val iconRes: Int? = null,
    /** 一次收藏多部时的「N 部动画」模板；只有 [TimelineShape.STRIP] 有。 */
    @PluralsRes val batchCountRes: Int? = null,
    /** 不展示：cat 9「天窗」服务端不给内容；其余情况见 [presentationOf] 的条目重载。 */
    val visible: Boolean = true,
)

/**
 * 服务端 cat / type → 呈现种类。**全仓唯一**的 `when(cat)`：类别、版式、文案都从这里派生。
 *
 * cat 是服务端给时间线动态分的大类（日常、收藏、进度、状态……），type 是大类下的具体动作，
 * 都是服务端定义的整数编号；各分支旁注明每个编号在服务端的含义。
 *
 * @param subjectType 动态所关于的作品类型：批量进度的动词随它（动画、三次元「看到」，书籍「读到」）；
 *   类型 → 动词由规格表 [subjectTypeSpec] 决定，没有作品时按规格表的缺省（同动画）。
 */
fun presentationOf(
    cat: Int,
    type: Int,
    batch: Boolean = false,
    subjectType: SubjectType? = null,
): TimelinePresentation = when (timelineCategoryOf(cat)) {
    // 收视进度：0 = 批量（动画「看到 N 话」、书籍「读到 N 话、M 卷」），1 = 想看，2 = 看过，3 = 抛弃
    TimelineCategory.PROGRESS -> TimelinePresentation(
        filter = TimelineFilter.PROGRESS,
        shape = TimelineShape.SUBJECT,
        verbRes = when (type) {
            0 -> if (subjectTypeSpec(subjectType).verb == SubjectVerb.READ) {
                R.string.timeline_verb_read_to
            } else {
                R.string.timeline_verb_watch_to
            }
            1 -> R.string.timeline_verb_wish
            3 -> R.string.timeline_verb_dropped
            else -> R.string.timeline_verb_watched
        },
        tone = if (type == 3) VerbTone.MUTED else VerbTone.NORMAL,
    )
    TimelineCategory.COLLECT -> TimelinePresentation(
        filter = TimelineFilter.COLLECT,
        shape = if (batch) TimelineShape.STRIP else TimelineShape.SUBJECT,
        verbRes = collectVerbRes(type),
        tone = when (type) {
            in 5..8 -> VerbTone.DONE
            13, 14 -> VerbTone.MUTED
            else -> VerbTone.NORMAL
        },
        batchCountRes = if (batch) collectBatchCountRes(type) else null,
    )
    // 状态：0 = 改签名，1 = 吐槽，2 = 改昵称
    TimelineCategory.STATUS -> when (type) {
        1 -> TimelinePresentation(TimelineFilter.STATUS, TimelineShape.TEXT, R.string.timeline_verb_tsukkomi)
        else -> TimelinePresentation(
            filter = TimelineFilter.STATUS,
            shape = TimelineShape.LINE,
            verbRes = if (type == 2) R.string.timeline_verb_nickname else R.string.timeline_verb_sign,
            iconRes = R.drawable.edit_24,
        )
    }
    TimelineCategory.BLOG -> TimelinePresentation(
        TimelineFilter.BLOG, TimelineShape.TITLE_CARD, R.string.timeline_verb_blog, iconRes = R.drawable.article_24,
    )
    TimelineCategory.INDEX -> TimelinePresentation(
        TimelineFilter.INDEX, TimelineShape.TITLE_CARD, R.string.timeline_verb_index, iconRes = R.drawable.list_alt_24,
    )
    TimelineCategory.MONO -> TimelinePresentation(
        filter = TimelineFilter.MONO,
        shape = TimelineShape.MONO,
        verbRes = if (type == 0) R.string.timeline_verb_mono_created else R.string.timeline_verb_mono_collected,
    )
    // 日常：0 = 神秘的行动，1 = 注册，2 = 加好友，3 = 加入小组，4 = 创建小组，5 = 加入乐园
    TimelineCategory.DAILY -> when (type) {
        2 -> TimelinePresentation(TimelineFilter.FRIEND, TimelineShape.AVATARS, R.string.timeline_verb_add_friend)
        3, 4 -> TimelinePresentation(
            filter = TimelineFilter.GROUP,
            shape = TimelineShape.TITLE_CARD,
            verbRes = if (type == 3) R.string.timeline_verb_join_group else R.string.timeline_verb_create_group,
            iconRes = R.drawable.group_24,
        )
        else -> TimelinePresentation(
            filter = null,
            shape = TimelineShape.LINE,
            verbRes = when (type) {
                1 -> R.string.timeline_verb_register
                5 -> R.string.timeline_verb_join_eden
                else -> R.string.timeline_verb_mystery
            },
            iconRes = R.drawable.person_add_24,
        )
    }
    TimelineCategory.WIKI -> TimelinePresentation(
        TimelineFilter.WIKI, TimelineShape.SUBJECT, R.string.timeline_verb_new_subject, tone = VerbTone.MUTED,
    )
    TimelineCategory.SKYLIGHT -> TimelinePresentation(
        null, TimelineShape.LINE, R.string.timeline_verb_unsupported, visible = false,
    )
    null -> TimelinePresentation(
        null, TimelineShape.LINE, R.string.timeline_verb_unsupported, iconRes = R.drawable.info_24,
    )
}

/**
 * 一条动态的呈现：版式由 [presentationOf]（cat / type，批量进度的动词另看第一个目标的作品类型）决定；
 * 另外两种情况不展示，个人页「动态」与动态页都按 [TimelinePresentation.visible] 过滤，这里是唯一判定点：
 * - **批量进度没有任何进度**：服务端会为「进度改成 0」记一条动态（新收藏书籍时一并提交的 0 进度就会这样），
 *   画出来只剩一个「读到」或「看到」；
 * - **要画目标的版式却没有目标**：日志删了、设成私密后，服务端给这条动态的内容是空对象（实测），
 *   画出来只剩一行「写了日志」。正文（吐槽）与单行（注册、改签名）本来就不靠目标。
 */
fun presentationOf(item: TimelineItemBo): TimelinePresentation {
    val subjectType = item.targets.firstNotNullOfOrNull { it.subject }?.type
    val presentation = presentationOf(item.cat, item.type, item.batch, subjectType)
    val emptyProgress = timelineCategoryOf(item.cat) == TimelineCategory.PROGRESS && item.targets.any { target ->
        target.segments.any { it is TimelineSegmentBo.ProgressBatch && it.hasNoProgress }
    }
    val hollow = item.targets.isEmpty() && presentation.shape != TimelineShape.TEXT && presentation.shape != TimelineShape.LINE
    return if (emptyProgress || hollow) presentation.copy(visible = false) else presentation
}

/** 收藏 cat 3 的 type：1–4 想 / 5–8 过 / 9–12 在（各按书、动画、音乐、游戏），13 搁置，14 抛弃。 */
@StringRes
private fun collectVerbRes(type: Int): Int = when (type) {
    1 -> R.string.timeline_collect_wish_book
    2 -> R.string.timeline_collect_wish_anime
    3 -> R.string.timeline_collect_wish_music
    4 -> R.string.timeline_collect_wish_game
    5 -> R.string.timeline_collect_done_book
    6 -> R.string.timeline_collect_done_anime
    7 -> R.string.timeline_collect_done_music
    8 -> R.string.timeline_collect_done_game
    9 -> R.string.timeline_collect_doing_book
    10 -> R.string.timeline_collect_doing_anime
    11 -> R.string.timeline_collect_doing_music
    12 -> R.string.timeline_collect_doing_game
    13 -> R.string.timeline_collect_on_hold
    14 -> R.string.timeline_collect_dropped
    else -> R.string.timeline_collect_unknown
}

/** 批量收藏的计数单位：type 按书、动画、音乐、游戏循环（服务端 Batch 注释：本书 / 部番组 / 张音乐 / 部游戏）。 */
@PluralsRes
private fun collectBatchCountRes(type: Int): Int = when ((type - 1).mod(4)) {
    0 -> R.plurals.timeline_batch_count_book
    1 -> R.plurals.timeline_batch_count_anime
    2 -> R.plurals.timeline_batch_count_music
    else -> R.plurals.timeline_batch_count_game
}
