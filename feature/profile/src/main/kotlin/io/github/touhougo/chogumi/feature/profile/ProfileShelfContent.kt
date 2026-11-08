package io.github.touhougo.chogumi.feature.profile

import io.github.touhougo.chogumi.core.model.RelatedCharacterBo
import io.github.touhougo.chogumi.core.model.RelatedPersonBo
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.common.paging.asPagedList
import io.github.touhougo.chogumi.core.ui.composables.GridCell

/** 收藏 tab 此刻画的内容：哪种条目、哪种网格单元，以及这份列表的加载状态（条目一次取全，也按分页列表画）。 */
internal sealed interface ProfileShelfContent {
    val list: PagedList<*>

    /** 这种内容画成哪种网格单元（决定网格列数，见 [GridCell]）。 */
    val cell: GridCell

    data class Subjects(override val list: PagedList<UserCollectionBo>) : ProfileShelfContent {
        override val cell get() = GridCell.Poster
    }

    data class Characters(override val list: PagedList<RelatedCharacterBo>) : ProfileShelfContent {
        override val cell get() = GridCell.Person
    }

    data class Persons(override val list: PagedList<RelatedPersonBo>) : ProfileShelfContent {
        override val cell get() = GridCell.Person
    }
}

/** 收藏 tab 当前分类；还没打开过时按第一个分类画（骨架与网格列数都跟它走）。 */
internal val ShelfState.shownCategory: ShelfCategory get() = category ?: ShelfCategory.entries.first()

/** 分类到展示内容的唯一判定点：「角色与人物」一档内部按分类自带的小类分角色 / 人物。 */
internal fun shelfContentOf(state: ShelfState): ProfileShelfContent = when (val category = state.shownCategory) {
    is ShelfCategory.SubjectDomain -> ProfileShelfContent.Subjects(state.subjects.asPagedList().copy(isRefreshing = state.subjectsRefreshing))
    is ShelfCategory.CharacterPerson -> when (category.tab) {
        CharacterPersonTab.CHARACTER -> ProfileShelfContent.Characters(state.characters)
        CharacterPersonTab.PERSON -> ProfileShelfContent.Persons(state.persons)
    }
}
