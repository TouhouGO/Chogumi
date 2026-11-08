package io.github.touhougo.chogumi.core.model

/*
 * 探索页「整理」对一份布局的改动：拖动排序、放上页面（新建、加回）、两种编辑面板的保存、「添加货架」的清单。
 * 全部是纯函数：core:data 的 ExploreLayoutRepository.edit 拿当前布局交给它们、整份写回；界面只说要做什么。
 * 移除（= 隐藏）、改名、图钉见 ExploreShelfLayout.kt。
 */

/**
 * 拖动排序：[ids] 是整理列表里显示着的货架的新顺序。
 * 不在 [ids] 里的行（隐藏的、这一版画不出的）按原来的相对顺序接在后面：隐藏的不画，加回时本来就挪到最前。
 */
fun List<ShelfEntry>.reordered(ids: List<String>): List<ShelfEntry> {
    val byId = associateBy { it.id }
    val moved = ids.distinct().mapNotNull { byId[it] }
    val movedIds = moved.mapTo(HashSet()) { it.id }
    return moved + filterNot { it.id in movedIds }
}

/**
 * 放上页面（「添加货架」里打开一条、加回）：布局里已有同 id 的就放出来、挪到最前（改过的名字、样式都留着）；
 * 没有就把 [entry] 放在最前。最前 = 近期热门下面第一条。
 */
fun List<ShelfEntry>.withShown(entry: ShelfEntry): List<ShelfEntry> {
    val existing = firstOrNull { it.id == entry.id } ?: entry
    return listOf(existing.withHiddenFlag(false)) + filterNot { it.id == entry.id }
}

/** 同一条货架换个隐藏标记（两种货架各自 copy，别处不再分）。 */
internal fun ShelfEntry.withHiddenFlag(hidden: Boolean): ShelfEntry = when (this) {
    is WorkShelfEntry -> copy(hidden = hidden)
    is TileShelfEntry -> copy(hidden = hidden)
}

/**
 * 预设货架编辑后的条件 [criteria] 还是不是这条预设的词：是就返回那条预设（排序不算，预设能改排序）。
 * 编辑面板的名字占位（还是预设名，还是条件连成的名字）与保存（[withWorkShelfSaved]）都按它。
 */
fun WorkShelfSource.Preset.keptBy(type: SubjectType, criteria: SearchCriteria): ShelfPreset? =
    shelfPresetOf(type, presetId)?.takeIf { it.criteria.copy(sort = criteria.sort).presetKey() == criteria.presetKey() }

/**
 * 作品货架编辑面板的保存。[originalId] 为 null 是新建：同 [pinned]（条件相同的已有货架放出来，没有才新建一条叫 [name] 的
 * 自建货架，样式 [style]，在最前）。否则改布局里 [originalId] 那条，位置不变：
 * - 固定货架只换样式；
 * - 预设的词没变（[keptBy]）：记下排序，名字与预设名不同才记（相同就继续跟目录）；
 * - 预设的词变了：不再是这条预设，在原位换成一条新的自建货架；
 * - 自建的：换条件与名字。
 *
 * [name] 是界面给的最终名字（名字栏空着时就是它的占位文字）。
 */
fun List<ShelfEntry>.withWorkShelfSaved(
    type: SubjectType,
    originalId: String?,
    criteria: SearchCriteria,
    name: String,
    style: WorkShelfStyle,
): List<ShelfEntry> {
    if (originalId == null) return pinned(type, criteria, name, style)
    val original = firstOrNull { it.id == originalId } as? WorkShelfEntry ?: return this
    val saved = when (val source = original.source) {
        is WorkShelfSource.Fixed -> original.copy(style = style)
        is WorkShelfSource.Preset -> source.keptBy(type, criteria)?.let { preset ->
            original.copy(source = source.copy(sort = criteria.sort, name = name.takeIf { it != preset.name }), style = style)
        } ?: WorkShelfEntry(id = newCustomShelfId(this), source = WorkShelfSource.Custom(criteria, name), style = style)
        is WorkShelfSource.Custom -> original.copy(source = WorkShelfSource.Custom(criteria, name), style = style)
    }
    return map { if (it.id == originalId) saved else it }
}

/**
 * 方块货架编辑面板的保存。[originalId] 为 null 是新建：一条叫 [name] 的自建方块货架放在最前。
 * 否则改 [originalId] 那条，位置不变：方块与默认方块画出来一样时存回 null（继续跟随默认），画法换成 [drawing]，
 * 自建的再换名字（预设组的名字跟着组走，[name] 不用）。
 */
fun List<ShelfEntry>.withTileShelfSaved(
    type: SubjectType,
    originalId: String?,
    name: String,
    tileKeys: List<String>,
    drawing: TileDrawing,
): List<ShelfEntry> {
    if (originalId == null) {
        val created = TileShelfEntry(
            id = newCustomShelfId(this),
            source = TileShelfSource.Custom(name),
            tileKeys = tileKeys,
            drawing = drawing,
        )
        return withShown(created)
    }
    val original = firstOrNull { it.id == originalId } as? TileShelfEntry ?: return this
    val source = when (original.source) {
        is TileShelfSource.Fixed -> original.source
        is TileShelfSource.Custom -> TileShelfSource.Custom(name)
    }
    val keys = tileKeys.takeIf { tilesOf(type, source, it) != tilesOf(type, source, null) }
    val saved = original.copy(source = source, tileKeys = keys, drawing = drawing)
    return map { if (it.id == originalId) saved else it }
}

/** 「添加货架」里的一行：放上去就是 [entry]（布局里已有的就是布局里那条），[shown] = 正显示在页面上。 */
data class ShelfChoice(
    val entry: ShelfEntry,
    val shown: Boolean,
    /** 名字之外的一行说明：预设的名字与它的词不同时，是那几个词（「A × B」）；其余为 null。 */
    val detail: String? = null,
)

/** 「添加货架」的清单（[shelfChoicesFor]）。 */
data class ShelfChoices(
    /** 我建的：布局里的自建货架（作品与方块），按布局顺序。 */
    val custom: List<ShelfChoice>,
    /** 固定货架与预设方块：这个分类能有的固定作品货架、有候选的预设方块组。 */
    val fixed: List<ShelfChoice>,
    /** 预设目录，按大类（[ShelfPreset.group]）分组，组内按目录顺序。 */
    val presets: List<Pair<String, List<ShelfChoice>>>,
)

/**
 * [type] 的「添加货架」清单（唯一来源）：每一行放上去是哪条货架、现在在不在页面上。
 * 固定货架、预设方块组、预设各有固定的 id，布局里已有的（哪怕移除了）就用布局里那条，加回来保留之前改过的样式与方块。
 */
fun shelfChoicesFor(type: SubjectType, layout: List<ShelfEntry>): ShelfChoices {
    val byId = layout.associateBy { it.id }
    fun choice(entry: ShelfEntry, detail: String? = null): ShelfChoice {
        val existing = byId[entry.id]
        return ShelfChoice(entry = existing ?: entry, shown = existing != null && !existing.hidden, detail = detail)
    }
    val custom = layout.filter { entry ->
        when (entry) {
            is WorkShelfEntry -> entry.source is WorkShelfSource.Custom
            is TileShelfEntry -> entry.source is TileShelfSource.Custom
        }
    }.map { choice(it) }
    val fixed = FixedWorkShelf.entries.filter { it.isAvailableFor(type) }.map { choice(fixedWorkShelfEntry(it)) } +
        TileSet.entries.filter { tileCandidates(type, TileShelfSource.Fixed(it)).isNotEmpty() }.map { choice(tileSetShelfEntry(it)) }
    val presets = shelfPresetsFor(type).groupBy { it.group }.map { (group, presets) ->
        group to presets.map { preset ->
            val words = combinationTitle(preset.words).joined
            choice(presetShelfEntry(preset), detail = words.takeIf { it != preset.name })
        }
    }
    return ShelfChoices(custom = custom, fixed = fixed, presets = presets)
}
