package io.github.touhougo.chogumi.feature.explore

import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.data.repository.ExploreLayoutRepository
import io.github.touhougo.chogumi.core.data.repository.TagCatalogRepository
import io.github.touhougo.chogumi.core.data.util.launchWrite
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.SearchAirtime
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SearchDimensionOption
import io.github.touhougo.chogumi.core.model.SearchSort
import io.github.touhougo.chogumi.core.model.ShelfChoice
import io.github.touhougo.chogumi.core.model.ShelfEntry
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagCatalogEntry
import io.github.touhougo.chogumi.core.model.TagFilter
import io.github.touhougo.chogumi.core.model.TileDrawing
import io.github.touhougo.chogumi.core.model.TileShelfEntry
import io.github.touhougo.chogumi.core.model.TileShelfSource
import io.github.touhougo.chogumi.core.model.WordPick
import io.github.touhougo.chogumi.core.model.WorkShelfEntry
import io.github.touhougo.chogumi.core.model.WorkShelfSource
import io.github.touhougo.chogumi.core.model.WorkShelfStyle
import io.github.touhougo.chogumi.core.model.concreteOrNull
import io.github.touhougo.chogumi.core.model.isPinnable
import io.github.touhougo.chogumi.core.model.defaultTileKeys
import io.github.touhougo.chogumi.core.model.reordered
import io.github.touhougo.chogumi.core.model.withShown
import io.github.touhougo.chogumi.core.model.withTileShelfSaved
import io.github.touhougo.chogumi.core.model.withWorkShelfSaved
import io.github.touhougo.chogumi.core.model.words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 探索页的整理模式（长按货架标题进入）：整理列表、编辑面板、「添加货架」面板开着哪个。只由 [ShelfArranger] 改。
 *
 * @property active 在整理（整页换成整理列表）
 * @property editor 开着的编辑面板（新建也是它）；null = 没开
 * @property adding 「添加货架」面板开着
 * @property closing 要收起的面板（滑下去后照常回调关闭），保存、点「完成」时置上
 */
data class ShelfArrangeState(
    val active: Boolean = false,
    val editor: ShelfEditor? = null,
    val adding: Boolean = false,
    val closing: ArrangeSheet? = null,
    /** 编辑草稿的保存或移除在途；完成前不排第二次写。 */
    val saving: Boolean = false,
)

/** 整理模式里的两种面板。 */
enum class ArrangeSheet { EDITOR, ADDING }

/**
 * 一条货架的编辑草稿：点保存才写进布局（[ShelfArranger.save]），下滑、点遮罩、返回就丢掉。
 *
 * @property type 这条货架在哪个分类
 * @property originalId 改的是布局里哪一条；null = 新建
 */
sealed interface ShelfEditor {
    val type: SubjectType
    val originalId: String?

    /**
     * 作品货架。
     *
     * @property source 改之前的来源（新建是 null）：固定货架只能换样式，预设的名字占位看词变没变（`keptBy`）
     * @property name 名字栏里的字；空着时保存用占位的名字
     * @property form 条件与排序：面板上的条件、筛选面板的草稿都在这一份表单里，经搜索页同一个 [reduce] 变
     * @property panelQuery 筛选面板搜索框的输入
     * @property catalogSuggestions 这个输入命中的标签目录条目
     */
    data class Works(
        override val type: SubjectType,
        override val originalId: String?,
        val source: WorkShelfSource?,
        val name: String,
        val form: SearchForm,
        val style: WorkShelfStyle,
        val panelQuery: String = "",
        val catalogSuggestions: List<TagCatalogEntry> = emptyList(),
    ) : ShelfEditor {
        /** 固定货架：只能换样式。 */
        val isFixed: Boolean get() = source is WorkShelfSource.Fixed

        /** 能保存：固定货架随时能；按词取的与搜索页图钉同一条判定（至少一个条件，不然是一整个分类）。 */
        val canSave: Boolean get() = isFixed || form.applied.isPinnable()
    }

    /**
     * 方块货架。
     *
     * @property source 预设的一组或自己建的（新建是自己建的）
     * @property name 名字栏里的字（只有自己建的有名字）
     * @property tileKeys 现在的方块，按顺序
     * @property picking 「加方块」的候选列表展开着
     */
    data class Tiles(
        override val type: SubjectType,
        override val originalId: String?,
        val source: TileShelfSource,
        val name: String,
        val tileKeys: List<String>,
        val drawing: TileDrawing,
        val picking: Boolean = false,
    ) : ShelfEditor {
        /** 能保存：至少一个方块。 */
        val canSave: Boolean get() = tileKeys.isNotEmpty()
    }
}

/** 整理列表（与浏览时长按标题进入）用到的动作；编辑面板、「添加货架」的其余动作在 [ShelfArranger] 上。 */
interface ArrangeListActions {
    /** 进入整理（长按货架标题）。 */
    fun enter()

    /** 退出整理（顶栏「完成」、返回）。 */
    fun exit()

    /** 打开「添加货架」。 */
    fun openAdding()

    /** 拖动排序松手：[ids] 是 [type] 整理列表里的新顺序。 */
    fun move(type: SubjectType, ids: List<String>)

    /** 左滑移除（= 隐藏，「添加货架」里还能加回来）。 */
    fun remove(type: SubjectType, id: String)

    /** 点一行：打开它的编辑面板。 */
    fun openEditor(type: SubjectType, entry: ShelfEntry)
}

/**
 * 整理模式的全部动作：进出整理、拖动排序、移除、两种编辑面板、「添加货架」。
 *
 * 布局怎么变只由 core:model 的纯函数决定（`reordered`、`withShown`、`withWorkShelfSaved`……），这里经
 * [ExploreLayoutRepository.edit] 交给它们、整份写回；写失败进 [failures]。作品货架的条件草稿与搜索页同一个 [reduce]，
 * 筛选面板与搜索页同一个 [SearchFilterPanel]（[wordActions]），目录建议同一个 [suggestionsFor]。
 */
class ShelfArranger(
    private val scope: CoroutineScope,
    state: Flow<ShelfArrangeState>,
    private val read: () -> ShelfArrangeState,
    private val write: ((ShelfArrangeState) -> ShelfArrangeState) -> Unit,
    private val failures: FailureNotices,
    private val layouts: ExploreLayoutRepository,
    private val tagCatalog: TagCatalogRepository,
) : ArrangeListActions {
    private var editorRound = 0L
    private var wordsRound = 0L

    init {
        // 筛选面板搜索框 → 目录建议；写回前核对输入还是发起查询时那个
        val inputs = state.map { (it.editor as? ShelfEditor.Works)?.let { works -> works.panelQuery to works.type } ?: ("" to null) }
        scope.launch {
            tagCatalog.suggestionsFor(inputs).collect { (query, entries) ->
                updateWorks { if (it.panelQuery == query) it.copy(catalogSuggestions = entries) else it }
            }
        }
    }

    private fun update(change: (ShelfArrangeState) -> ShelfArrangeState) = write(change)

    private fun updateWorks(change: (ShelfEditor.Works) -> ShelfEditor.Works) =
        update { state -> if (state.saving) state else (state.editor as? ShelfEditor.Works)?.let { state.copy(editor = change(it)) } ?: state }

    private fun updateTiles(change: (ShelfEditor.Tiles) -> ShelfEditor.Tiles) =
        update { state -> if (state.saving) state else (state.editor as? ShelfEditor.Tiles)?.let { state.copy(editor = change(it)) } ?: state }

    // —— 进出整理

    override fun enter() = update { it.copy(active = true) }

    override fun exit() {
        ++editorRound
        update { ShelfArrangeState() }
    }

    // —— 整理列表

    override fun move(type: SubjectType, ids: List<String>) = edit(type) { it.reordered(ids) }

    override fun remove(type: SubjectType, id: String) {
        scope.launchWrite(failures) { layouts.hide(type, id) }
    }

    override fun openEditor(type: SubjectType, entry: ShelfEntry) {
        ++editorRound
        update { it.copy(editor = editorOf(type, entry), closing = null, saving = false) }
    }

    /** 新建一条作品货架：从空条件起，按排名、海报行。 */
    fun newWorksShelf(type: SubjectType) {
        ++editorRound
        update {
            val criteria = SearchCriteria(subjectType = type, sort = SearchSort.RANK)
            it.copy(
                editor = ShelfEditor.Works(type, originalId = null, source = null, name = "", form = SearchForm(applied = criteria), style = WorkShelfStyle.POSTER_ROW),
                closing = null,
                saving = false,
            )
        }
    }

    /** 新建一条方块货架：从空起，候选是这个分类的整个预设目录，画成合集。 */
    fun newTilesShelf(type: SubjectType) {
        ++editorRound
        update {
            it.copy(
                editor = ShelfEditor.Tiles(
                    type = type,
                    originalId = null,
                    source = TileShelfSource.Custom(""),
                    name = "",
                    tileKeys = emptyList(),
                    drawing = TileDrawing.STACK,
                    picking = true,
                ),
                closing = null,
                saving = false,
            )
        }
    }

    // —— 编辑面板

    fun setName(name: String) = update { state ->
        if (state.saving) return@update state
        when (val editor = state.editor) {
            is ShelfEditor.Works -> state.copy(editor = editor.copy(name = name))
            is ShelfEditor.Tiles -> state.copy(editor = editor.copy(name = name))
            null -> state
        }
    }

    fun setStyle(style: WorkShelfStyle) = updateWorks { it.copy(style = style) }

    fun setSort(sort: SearchSort) = updateWorks { it.reduced(SearchIntent.SortChanged(sort)) }

    /** 编辑面板上点掉一个条件：与在筛选面板里点掉它、保存一样（同一个 [reduce]）。 */
    fun removeCondition(condition: ActiveCondition) = updateWorks { editor ->
        val drop = when (condition) {
            is ActiveCondition.Dimension -> SearchIntent.DraftDimensionToggled(condition.dimension, condition.option)
            is ActiveCondition.Tag -> SearchIntent.DraftTagRemoved(condition.tag)
            ActiveCondition.Time -> SearchIntent.DraftAirtimeChanged(null)
        }
        editor.reduced(SearchIntent.FiltersOpened).reduced(drop).reduced(SearchIntent.FiltersSaved)
    }

    /** 打开筛选面板改条件（草稿从现在的条件复制），顺带按需拉这个分类的标签目录。 */
    fun openWords() {
        val editor = read().editor as? ShelfEditor.Works ?: return
        val round = ++wordsRound
        val editorAtOpen = editorRound
        updateWorks { it.reduced(SearchIntent.FiltersOpened).copy(panelQuery = "", catalogSuggestions = emptyList()) }
        val type = editor.type.concreteOrNull() ?: return
        scope.refreshCatalog(tagCatalog, type, failures) {
            wordsRound == round && editorRound == editorAtOpen &&
                (read().editor as? ShelfEditor.Works)?.form?.filterDraft != null
        }
    }

    /** 筛选面板点「保存」：草稿成为这条货架的条件（还没写进布局，编辑面板保存才写）。 */
    fun saveWords() = updateWorks { it.reduced(SearchIntent.FiltersSaved).copy(panelQuery = "", catalogSuggestions = emptyList()) }

    /** 筛选面板关掉：丢掉草稿。 */
    fun dismissWords() = updateWorks { it.reduced(SearchIntent.FiltersDismissed).copy(panelQuery = "", catalogSuggestions = emptyList()) }

    /** 筛选面板里只改草稿的动作：与搜索页同一组事件、同一个 [reduce]。 */
    val wordActions: FilterDraftActions = object : FilterDraftActions {
        override fun onPanelQueryChange(value: String) = updateWorks { it.copy(panelQuery = value, catalogSuggestions = emptyList()) }
        override fun onDraftTagRemove(tag: TagFilter) = updateWorks { it.reduced(SearchIntent.DraftTagRemoved(tag)) }
        override fun onDraftWordPick(word: String, pick: WordPick) = updateWorks { it.reduced(SearchIntent.DraftWordPicked(word, pick)) }
        override fun onDraftDimensionToggle(dimension: SearchDimension, option: SearchDimensionOption) =
            updateWorks { it.reduced(SearchIntent.DraftDimensionToggled(dimension, option)) }
        override fun onDraftAirtimeChange(airtime: SearchAirtime?) = updateWorks { it.reduced(SearchIntent.DraftAirtimeChanged(airtime)) }
        override fun onDraftClear() = updateWorks { it.reduced(SearchIntent.DraftCleared) }
    }

    fun setDrawing(drawing: TileDrawing) = updateTiles { it.copy(drawing = drawing) }

    fun togglePicking() = updateTiles { it.copy(picking = !it.picking) }

    fun addTile(key: String) = updateTiles { if (key in it.tileKeys) it else it.copy(tileKeys = it.tileKeys + key) }

    fun removeTile(key: String) = updateTiles { it.copy(tileKeys = it.tileKeys - key) }

    /**
     * 编辑面板保存：写进布局，面板滑下去。[name] 是最终的名字（名字栏空着时就是占位的名字，由界面按语言给）。
     * 不能保存的草稿（没有条件的作品货架、没有方块的方块货架）不写。
     */
    fun save(name: String) {
        val editor = read().editor ?: return
        val change: (List<ShelfEntry>) -> List<ShelfEntry> = when (editor) {
            is ShelfEditor.Works -> {
                if (!editor.canSave) return
                { it.withWorkShelfSaved(editor.type, editor.originalId, editor.form.applied, name, editor.style) }
            }
            is ShelfEditor.Tiles -> {
                if (!editor.canSave) return
                { it.withTileShelfSaved(editor.type, editor.originalId, name, editor.tileKeys, editor.drawing) }
            }
        }
        writeEditor { layouts.edit(editor.type, change) }
    }

    /** 编辑面板里的移除与保存共用完成裁决：写失败不关闭。 */
    fun removeEdited() {
        val editor = read().editor ?: return
        val id = editor.originalId ?: return
        writeEditor { layouts.hide(editor.type, id) }
    }

    /** 编辑草稿只在成功后请求关闭；失败留草稿并允许重试，关闭/换面板后旧回包不影响新面板。 */
    private fun writeEditor(persist: suspend () -> Unit) {
        if (read().saving || read().closing == ArrangeSheet.EDITOR) return
        val round = editorRound
        update { it.copy(saving = true) }
        scope.launch {
            val result = outcomeOf { persist() }
            if (editorRound != round) return@launch
            update { it.copy(saving = false, closing = if (result is Outcome.Success) ArrangeSheet.EDITOR else null) }
            if (result is Outcome.Failed) failures.post(result.failure)
        }
    }

    /** 编辑面板滑完（保存后、或下滑 / 点遮罩 / 返回丢掉草稿）。 */
    fun editorClosed() {
        ++editorRound
        update { it.copy(editor = null, saving = false, closing = it.closing.takeIf { closing -> closing != ArrangeSheet.EDITOR }) }
    }

    // —— 添加货架

    override fun openAdding() = update { it.copy(adding = true, closing = null) }

    /** 「添加货架」点「完成」：面板滑下去。 */
    fun finishAdding() = update { it.copy(closing = ArrangeSheet.ADDING) }

    fun addingClosed() = update { it.copy(adding = false, closing = it.closing.takeIf { closing -> closing != ArrangeSheet.ADDING }) }

    /** 「添加货架」里点一行：在页面上的移除，不在的放上来（在近期热门下面第一条）。 */
    fun toggle(type: SubjectType, choice: ShelfChoice) {
        if (choice.shown) remove(type, choice.entry.id) else edit(type) { it.withShown(choice.entry) }
    }

    private fun edit(type: SubjectType, change: (List<ShelfEntry>) -> List<ShelfEntry>) {
        scope.launchWrite(failures) { layouts.edit(type, change) }
    }
}

/** 布局里的一条 → 它的编辑草稿。 */
private fun editorOf(type: SubjectType, entry: ShelfEntry): ShelfEditor = when (entry) {
    is WorkShelfEntry -> {
        val source = entry.source
        val criteria = source.words(type)?.criteria ?: SearchCriteria(subjectType = type, sort = SearchSort.RANK)
        val name = when (source) {
            is WorkShelfSource.Fixed -> ""
            is WorkShelfSource.Preset -> source.name.orEmpty()
            is WorkShelfSource.Custom -> source.name
        }
        ShelfEditor.Works(type, entry.id, source, name, SearchForm(applied = criteria), entry.style)
    }
    is TileShelfEntry -> ShelfEditor.Tiles(
        type = type,
        originalId = entry.id,
        source = entry.source,
        name = (entry.source as? TileShelfSource.Custom)?.name.orEmpty(),
        tileKeys = entry.tileKeys ?: defaultTileKeys(type, entry.source),
        drawing = entry.drawing,
    )
}

/** 草稿表单经搜索页同一个 [reduce] 走一步；不合法的结果（不会出现：面板只给这个分类的取值）保持原样。 */
private fun ShelfEditor.Works.reduced(intent: SearchIntent): ShelfEditor.Works {
    val reduction = reduce(form, intent)
    return if (reduction.errors.isEmpty()) copy(form = reduction.form) else this
}
