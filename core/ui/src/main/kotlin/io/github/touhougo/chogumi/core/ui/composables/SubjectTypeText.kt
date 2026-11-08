package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.SubjectVerb
import io.github.touhougo.chogumi.core.model.subjectTypeSpec
import io.github.touhougo.chogumi.core.res.R

/**
 * 条目类型 → 显示名资源。**全项目唯一一份**，各页复用。
 * 未知类型给中性的「动画 / 书籍 / 三次元 / 游戏」，不冒充成动画。
 */
fun SubjectType.labelRes(): Int = when (this) {
    SubjectType.BOOK -> R.string.book
    SubjectType.ANIME -> R.string.anime
    SubjectType.MUSIC -> R.string.music
    SubjectType.GAME -> R.string.game
    SubjectType.REAL -> R.string.real
    // 未知条目类型：显示中性占位，不默认为动画。
    SubjectType.UNKNOWN -> R.string.anime_book_real_game
}

/**
 * 条目类型 → 图标资源。**全项目唯一一份**。
 * 未知类型用中性的网格图标，不冒充某一类型。[filled] 只给分段里选中的那一格（图标规矩：选中才填充）。
 */
fun SubjectType.iconRes(filled: Boolean = false): Int = when (this) {
    SubjectType.ANIME -> if (filled) R.drawable.tv_filled_24 else R.drawable.tv_24
    SubjectType.BOOK -> if (filled) R.drawable.book_2_filled_24 else R.drawable.book_2_24
    SubjectType.MUSIC -> if (filled) R.drawable.music_note_filled_24 else R.drawable.music_note_24
    SubjectType.GAME -> if (filled) R.drawable.sports_esports_filled_24 else R.drawable.sports_esports_24
    SubjectType.REAL -> if (filled) R.drawable.theaters_filled_24 else R.drawable.theaters_24
    SubjectType.UNKNOWN -> if (filled) R.drawable.grid_view_filled_24 else R.drawable.grid_view_24
}

/** 类型筛选里的一格 → 显示名：null 是「全部」。分类栏、类型筛选、设置里的进度页默认分类都取这一份。 */
fun SubjectType?.labelOrAllRes(): Int = this?.labelRes() ?: R.string.all

/** 类型筛选里的一格 → 图标：null 是「全部」，与未知类型同用中性的网格图标。 */
fun SubjectType?.iconOrAllRes(filled: Boolean = false): Int = (this ?: SubjectType.UNKNOWN).iconRes(filled)

/** 收藏状态 → 显示名资源（通用词：想看 / 看过 / 在看……）。**全项目唯一一份**，各页复用。 */
fun CollectionType.labelRes(): Int = when (this) {
    CollectionType.WISH -> R.string.collection_wish
    CollectionType.DONE -> R.string.collection_done
    CollectionType.DOING -> R.string.collection_doing
    CollectionType.ON_HOLD -> R.string.collection_on_hold
    CollectionType.DROPPED -> R.string.collection_dropped
    // 未知状态不伪造已知语义。
    CollectionType.UNKNOWN -> R.string.no_content
}

/**
 * 收藏状态 → 显示名资源，**按条目类型分动词**（读 / 看 / 听 / 玩）。**全项目唯一一份**。
 *
 * 与无参的 [CollectionType.labelRes] 的区别：那个用通用词（想看 / 看过 / 在看），回答「条目属于哪一类收藏」；
 * 这个用条目类型自己的动词（书籍「想读」、音乐「想听」），进度页筛选菜单、徽章用它，避免把书籍说成「想看」。
 * 依据 bgm.tv 网页端的收藏选择器：想 / 过 / 在 三态随动词变，**搁置与抛弃不分条目类型**，回落到通用词。
 * 类型 → 动词由规格表 [subjectTypeSpec] 唯一决定（三次元与未知类型走看类动词，同动画），这里只按动词选文案。
 */
fun CollectionType.labelResFor(subjectType: SubjectType): Int {
    val (wish, done, doing) = when (subjectTypeSpec(subjectType).verb) {
        SubjectVerb.READ -> Triple(R.string.collection_wish_book, R.string.collection_done_book, R.string.collection_doing_book)
        SubjectVerb.LISTEN -> Triple(R.string.collection_wish_music, R.string.collection_done_music, R.string.collection_doing_music)
        SubjectVerb.PLAY -> Triple(R.string.collection_wish_game, R.string.collection_done_game, R.string.collection_doing_game)
        SubjectVerb.WATCH -> Triple(R.string.collection_wish_anime, R.string.collection_done_anime, R.string.collection_doing_anime)
    }
    return when (this) {
        CollectionType.WISH -> wish
        CollectionType.DONE -> done
        CollectionType.DOING -> doing
        CollectionType.ON_HOLD, CollectionType.DROPPED, CollectionType.UNKNOWN -> labelRes()
    }
}

/**
 * 「标为看过 / 读过 / 听过 / 玩过」（全项目唯一一份）：EP 按钮看完时、章节方格的看完卡片、收藏面板的建议都用它，
 * 「过」的说法随 [labelResFor] 按条目类型分动词。
 */
@Composable
fun markDoneLabel(subjectType: SubjectType): String =
    stringResource(R.string.collection_mark_as, stringResource(CollectionType.DONE.labelResFor(subjectType)))

/** 动词的进行式（看 / 读 / 听 / 玩；英文 watching / reading …），用于「开始看的人看完了吗」这类句子。**全项目唯一一份**。 */
fun SubjectVerb.rootRes(): Int = when (this) {
    SubjectVerb.WATCH -> R.string.verb_root_watch
    SubjectVerb.READ -> R.string.verb_root_read
    SubjectVerb.LISTEN -> R.string.verb_root_listen
    SubjectVerb.PLAY -> R.string.verb_root_play
}

/**
 * 收藏状态 → 矢量图标资源。**全项目唯一一份**。[filled] 只用于「是这个状态」的地方（海报长按环上的当前状态、
 * 待提交胶囊、瞄准时的预演），按图标规矩选中 / 已开才填充。
 */
fun CollectionType.iconRes(filled: Boolean = false): Int = when (this) {
    CollectionType.WISH -> if (filled) R.drawable.bookmark_filled_24 else R.drawable.bookmark_24
    CollectionType.DONE -> if (filled) R.drawable.check_circle_filled_24 else R.drawable.check_circle_24
    CollectionType.DOING -> if (filled) R.drawable.play_circle_filled_24 else R.drawable.play_circle_24
    CollectionType.ON_HOLD -> if (filled) R.drawable.pause_circle_filled_24 else R.drawable.pause_circle_24
    CollectionType.DROPPED -> if (filled) R.drawable.cancel_filled_24 else R.drawable.cancel_24
    CollectionType.UNKNOWN -> if (filled) R.drawable.info_filled_24 else R.drawable.info_24
}
