package io.github.touhougo.chogumi.feature.monodetails

import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.orEmpty
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.CastRole
import io.github.touhougo.chogumi.core.model.FactRow
import io.github.touhougo.chogumi.core.model.IndexBo
import io.github.touhougo.chogumi.core.model.MonoCharacterRefBo
import io.github.touhougo.chogumi.core.model.MonoDetailsBo
import io.github.touhougo.chogumi.core.model.MonoFact
import io.github.touhougo.chogumi.core.model.MonoPersonRefBo
import io.github.touhougo.chogumi.core.model.MonoRelationBo
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.MonoWorkBo
import io.github.touhougo.chogumi.core.model.characterBillingRole
import io.github.touhougo.chogumi.core.model.characterVoiceActor
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.monoFacts
import io.github.touhougo.chogumi.core.model.monoInfoRows
import io.github.touhougo.chogumi.core.reader.ComposerState
import io.github.touhougo.chogumi.core.reader.FloorComments
import io.github.touhougo.chogumi.core.reader.ReplyTarget
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkState
import io.github.touhougo.chogumi.core.ui.moderation.ModerationState

/**
 * 人物与角色详情界面状态模型。
 *
 * 遵循手规与单一真源约定：
 * 1. floorNumber 不持久化在 UiState 中，楼层号纯粹在 UI 渲染时由索引推导；
 * 2. 包含乐观收藏状态 (isCollected, collectCount, isCollectionLoading)；
 * 3. 作品、代表角色、关联、目录与评论（[FloorComments.floors]）各是一拍 [Load]，单项失败不破坏整页；
 * 4. 头部的定位、CV、事实 chip 与「全部资料」的行都是派生值（core:model 纯函数），不另存一份。
 */
data class MonoDetailsUiState(
    val id: Int = 0,
    val monoType: MonoType = MonoType.CHARACTER,
    /** 详情本身：决定整页的加载与失败（取不到 = 整页重试）；名字、封面、简介、职业、资料各行都从它来。 */
    val details: Load<MonoDetailsBo> = Load.Pending,
    // 收藏（乐观写）：详情落下时以服务端的值为起点
    val isCollected: Boolean = false,
    val collectCount: Int = 0,
    val isCollectionLoading: Boolean = false,
    // 各区块独立加载，单项失败不破坏整页；初值都是「等待」（角色页没有代表角色，由 ViewModel 给成空的就绪），
    // 所以「全部落定」是单调的信号
    /** 全部作品（全量，排序在界面按 [io.github.touhougo.chogumi.core.model.sortMonoWorks] 做）。 */
    val works: Load<List<MonoWorkBo>> = Load.Pending,
    /** 人物演过的角色（代表角色）的第一页，带总数。 */
    val characters: Load<PagedResult<MonoCharacterRefBo>> = Load.Pending,
    val relations: Load<List<MonoRelationBo>> = Load.Pending,
    /** 收录它的目录：服务端总数与分页游标和已到页面放在同一份分页状态里。 */
    val indexes: PagedList<IndexBo> = PagedList(),
    /** 吐槽箱：与日志、目录的留言同一套楼层（没有楼主）。 */
    val comments: FloorComments = FloorComments(),
    /** 写吐槽、回复一层楼的编辑器面板（[MonoDetailsViewModel.composer]）。 */
    val composer: ComposerState<ReplyTarget> = ComposerState(),
    val isRefreshing: Boolean = false,
    /** 参与作品海报的长按标记，只由 [MonoDetailsViewModel.posterMarks] 改。 */
    val posterMarks: PosterMarkState = PosterMarkState(),
    /** 报告面板（吐槽箱楼层长按「报告」）；非 null = 面板打开，只由 [MonoDetailsViewModel.moderation] 改。 */
    val moderation: ModerationState = ModerationState(),
) {
    val name: String get() = details.valueOrNull()?.name.orEmpty()
    val nameCn: String get() = details.valueOrNull()?.nameCn.orEmpty()
    val coverImage: String? get() = details.valueOrNull()?.image
    val summary: String get() = details.valueOrNull()?.summary.orEmpty()

    /** 人物的职业代号（`seiyu`…）；角色为空。 */
    val careers: List<String> get() = details.valueOrNull()?.careers.orEmpty()
    val infobox: List<FactRow> get() = details.valueOrNull()?.infobox.orEmpty()

    /** 标题：中文名优先。 */
    val title: String get() = displayNameOf(name = name, nameCn = nameCn)

    /** 头部事实 chip（[monoFacts]）。 */
    val facts: List<MonoFact> get() = monoFacts(monoType, infobox)

    /** 「全部资料」的行（[monoInfoRows]）。 */
    val infoRows: List<FactRow> get() = monoInfoRows(infobox, title)

    /** 角色在所有作品里最主要的定位（[characterBillingRole]）；人物页恒为 null（人物作品不带定位）。 */
    val billingRole: CastRole? get() = characterBillingRole(works.orEmpty())

    /** 角色的声优（[characterVoiceActor]）；人物页恒为 null（人物作品不带声优）。 */
    val voiceActor: MonoPersonRefBo? get() = characterVoiceActor(works.orEmpty())

    /** 代表角色的「全部」：总数超过实际露出的条数才给（预览被过滤到一个不剩时，区块也靠它留住标题与入口）。 */
    val hasMoreCharacters: Boolean get() = characters.valueOrNull()?.let { it.total > it.items.size } == true
}
