package io.github.touhougo.chogumi.feature.explore

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.CalendarRepository
import io.github.touhougo.chogumi.core.data.repository.ExploreLayoutRepository
import io.github.touhougo.chogumi.core.data.repository.MonoRepository
import io.github.touhougo.chogumi.core.data.repository.SearchRepository
import io.github.touhougo.chogumi.core.data.repository.TagCatalogRepository
import io.github.touhougo.chogumi.core.data.util.loadOnce
import io.github.touhougo.chogumi.core.data.util.storageAsLoad
import io.github.touhougo.chogumi.core.foundation.AppTime
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.map
import io.github.touhougo.chogumi.core.model.CalendarItemBo
import io.github.touhougo.chogumi.core.model.CollectionEditing
import io.github.touhougo.chogumi.core.model.CollectionMarker
import io.github.touhougo.chogumi.core.model.ContentDomains
import io.github.touhougo.chogumi.core.model.ExploreEntry
import io.github.touhougo.chogumi.core.model.ExploreShelf
import io.github.touhougo.chogumi.core.model.ExploreTile
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.ShelfEntry
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TileDrawing
import io.github.touhougo.chogumi.core.model.TileLogo
import io.github.touhougo.chogumi.core.model.TileNeed
import io.github.touhougo.chogumi.core.model.WorkShelfContent
import io.github.touhougo.chogumi.core.model.defaultShelfLayout
import io.github.touhougo.chogumi.core.model.exploreEntriesFor
import io.github.touhougo.chogumi.core.model.exploreShelvesFor
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkController
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkState
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * 某个分类自己的内容：货架、今日放送与方块要的数据。每一份是一个 [Load]，只经 `collectSection` 前进：
 * 刷新途中与刷新失败时已显示的条目留在原地（[io.github.touhougo.chogumi.core.foundation.advance]）。
 * 取失败的货架与空货架一样不画；失败由一轮加载结束时的提示条统一说明（[ExploreViewModel.failures]）。
 */
data class ExploreTypeContent(
    /** 按货架 id（[ExploreShelf.id]）存的搜索货架内容。 */
    val shelves: Map<String, Load<List<SubjectBo>>> = emptyMap(),
    val todayOnAir: Load<List<CalendarItemBo>> = Load.Pending,
    /** 方块货架每一块的作品（合集、代表作、刻度尺画法），按 [tileWorksKey]。 */
    val tileWorks: Map<String, Load<List<SubjectBo>>> = emptyMap(),
    /** 公司方块的标志图，按公司的人物条目 id（[TileLogo.Company]）；没有图是 null。 */
    val logos: Map<Int, Load<String?>> = emptyMap(),
) {
    /** 某个货架；还没开始取的按「等待」画骨架。 */
    fun shelf(id: String): Load<List<SubjectBo>> = shelves[id] ?: Load.Pending

    /** 方块货架 [shelfId] 里 [tile] 那一块的作品；还没开始取的按「等待」。 */
    fun tileWorks(shelfId: String, tile: ExploreTile): Load<List<SubjectBo>> = tileWorks[tileWorksKey(shelfId, tile)] ?: Load.Pending

    /** 人物条目 [personId] 的标志图；还没开始取的按「等待」。 */
    fun logo(personId: Int): Load<String?> = logos[personId] ?: Load.Pending
}

/** 一块方块的作品在 [ExploreTypeContent.tileWorks] 里的键：同一个词在两条方块货架里各取各的。 */
internal fun tileWorksKey(shelfId: String, tile: ExploreTile): String = "$shelfId/${tile.label.key}"

/**
 * 一个分类这一天的版面：入口行与货架表，只由 [exploreEntriesFor] / [exploreShelvesFor] 给出。
 * [shelfLayout] 是画出这些货架的那份布局（整理列表、编辑面板、「添加货架」按它改）。
 */
data class ExploreLayout(
    val entries: List<ExploreEntry>,
    val shelves: List<ExploreShelf>,
    val shelfLayout: List<ShelfEntry> = emptyList(),
) {
    companion object {
        /**
         * 五个内容分类在 [today] 这一天的版面（[ContentDomains] 的顺序就是翻页的页序）。
         * 布局是 [stored] 里这个分类的那份（[ExploreLayoutRepository.observe]），还没读到的分类先用默认布局。
         */
        fun forDay(today: LocalDate, stored: Map<SubjectType, List<ShelfEntry>>): Map<SubjectType, ExploreLayout> =
            ContentDomains.associateWith { type ->
                val layout = stored[type] ?: defaultShelfLayout(type)
                ExploreLayout(exploreEntriesFor(type, today), exploreShelvesFor(type, today, layout), layout)
            }

        val Empty = ExploreLayout(emptyList(), emptyList())
    }
}

/**
 * 探索页 UI 状态。
 *
 * - [selectedType] 是「当前看哪个分类」的**唯一**来源，左右翻页只是它的样子；
 * - 各分类的内容按分类分开存在 [contents]：任一次加载只写它所属的那一份，「A 的加载写不进 B」由构造保证，
 *   不靠写之前再核一次代次（红线一）；
 * - [layouts] 是每个分类今天的版面（[ExploreLayout.forDay]，跨零点由 [ExploreViewModel] 整份重算）：
 *   翻页途中看到的邻页与落定后是同一份版面。
 */
data class ExploreUiState(
    /**
     * 业务日（[AppTime.today]）：入口、货架表、今日放送的列表与副标题都按它，同在这一份状态里发布，
     * 跨零点时一起换（不会出现副标题已是新的星期、列表还是昨天的）。
     */
    val today: LocalDate,
    val selectedType: SubjectType = SubjectType.ANIME,
    val layouts: Map<SubjectType, ExploreLayout> = emptyMap(),
    val contents: Map<SubjectType, ExploreTypeContent> = emptyMap(),
    val refreshing: Boolean = false,
    /** 海报长按标记（各货架、热门大图），只由 [ExploreViewModel.posterMarks] 改。 */
    val posterMarks: PosterMarkState = PosterMarkState(),
    /** 整理模式，只由 [ExploreViewModel.arranger] 改。 */
    val arrange: ShelfArrangeState = ShelfArrangeState(),
) {
    /** 当前分类的内容。 */
    val current: ExploreTypeContent get() = contents[selectedType] ?: ExploreTypeContent()

    /** [type] 这一页的版面（入口行与货架表）。 */
    fun layoutOf(type: SubjectType): ExploreLayout = layouts[type] ?: ExploreLayout.Empty

    /** [type] 那一份内容（没有就是空的）。 */
    fun contentOf(type: SubjectType): ExploreTypeContent = contents[type] ?: ExploreTypeContent()

    /** 只改 [type] 那一份内容。 */
    fun withContent(type: SubjectType, change: ExploreTypeContent.() -> ExploreTypeContent): ExploreUiState =
        copy(contents = contents + (type to contentOf(type).change()))
}

class ExploreViewModel(
    private val searchRepository: SearchRepository,
    private val calendarRepository: CalendarRepository,
    /** 公司方块的标志图（[MonoRepository.getPersonImage]）。 */
    private val monoRepository: MonoRepository,
    layoutRepository: ExploreLayoutRepository,
    /** 整理里改作品货架条件时，筛选面板的标签目录建议。 */
    tagCatalogRepository: TagCatalogRepository,
    /** 「今天」：货架表随它变（几月新番、本月新作、今日放送）。 */
    appTime: AppTime,
    collectionMarker: CollectionMarker,
    collectionEditing: CollectionEditing,
) : StateViewModel<ExploreUiState>(
    ExploreUiState(today = appTime.today.value, layouts = ExploreLayout.forDay(appTime.today.value, stored = emptyMap())),
) {

    /** 本页的失败提示：一轮货架加载里有货架没取到（一轮只提示一次，可重试）。 */
    val failures = FailureNotices()

    /** 海报长按标记：标状态、撤销、收藏管理面板。 */
    val posterMarks = PosterMarkController(
        scope = viewModelScope,
        read = { currentState.posterMarks },
        write = { change -> updateState { it.copy(posterMarks = change(it.posterMarks)) } },
        failures = failures,
        marker = collectionMarker,
        editing = collectionEditing,
        today = appTime.today,
    )

    /** 整理模式：排序、移除、编辑、添加货架。 */
    val arranger = ShelfArranger(
        scope = viewModelScope,
        state = uiState.map { it.arrange },
        read = { currentState.arrange },
        write = { change -> updateState { it.copy(arrange = change(it.arrange)) } },
        failures = failures,
        layouts = layoutRepository,
        tagCatalog = tagCatalogRepository,
    )

    /**
     * 每个分类已经拿到最终结果（成功或失败）的那几份数据（[Fetch.key]）。切回一个加载被打断的分类时，只补这里没有的。
     * 只是加载的记账，不是界面状态。
     */
    private val settledShelves = mutableMapOf<SubjectType, MutableSet<String>>()

    /** 各内容位置最近一轮的条件与任务（含已结束的）：相同条件去重，条件变化或显式刷新取代旧任务。只是加载的记账。 */
    private val inFlight = mutableMapOf<SubjectType, MutableMap<String, FetchRound>>()

    /**
     * 每个分类里出现过在屏上的方块（[tileWorksKey]）：方块要的数据（[TileDrawing.needs]）只给它们取，下拉刷新时一起重取。
     * 只是加载的记账。
     */
    private val shownTiles = mutableMapOf<SubjectType, MutableSet<String>>()

    /** 当前分类的货架加载；切分类或下拉刷新时取消它，被打断的分类靠 [settledShelves] 补载。 */
    private var loadJob: Job? = null

    /** 各分类存下来的布局（[ExploreLayoutRepository.observe]）；跟着本地表变。 */
    private var stored: Map<SubjectType, List<ShelfEntry>> = emptyMap()

    init {
        load(type = SubjectType.ANIME, refresh = false)
        // 跨零点：换成新一天的入口与货架表，按日子取的货架（今日放送、本月新作……）全部作废重取
        viewModelScope.launch {
            appTime.today.drop(1).collect { today ->
                settledShelves.clear()
                updateState { it.copy(today = today, layouts = ExploreLayout.forDay(today, stored)) }
                load(type = currentState.selectedType, refresh = false)
            }
        }
        // 布局变了（搜索页图钉、整理）：重排货架，新出现的货架接着取，不打断正在取的。表读不出时提示带重试，页面留着默认布局
        follow(failures, { combine(ContentDomains.map { type -> layoutRepository.observe(type).map { type to it } }) { it.toMap() }.storageAsLoad() }) { layouts ->
            stored = layouts
            updateState { it.copy(layouts = ExploreLayout.forDay(it.today, layouts)) }
            loadAdded(currentState.selectedType)
        }
    }

    fun selectType(type: SubjectType) {
        if (type == currentState.selectedType) return
        updateState { it.copy(selectedType = type, refreshing = false) }
        // 换分类会取消上一个分类还在跑的加载；被打断的分类下次切回时按未结算的货架补载。
        load(type = type, refresh = false)
    }

    fun refresh() = load(type = currentState.selectedType, refresh = true)

    /**
     * 方块货架 [shelfId] 里的 [tiles] 出现在屏上：取它们要的数据（合集与代表作要作品、标志要标志图，刻度尺要停在的那一块的作品，
     * 见 [TileDrawing.needs]），取过的、正在取的不再取。只在露面时取：没滚到的方块货架、刻度尺没停过的年代不发请求。
     */
    fun showTiles(type: SubjectType, shelfId: String, tiles: List<ExploreTile>) {
        val shelf = currentState.layoutOf(type).shelves.firstOrNull { it.id == shelfId } as? ExploreShelf.Tiles ?: return
        shownTiles.getOrPut(type) { mutableSetOf() } += tiles.map { tileWorksKey(shelfId, it) }
        loadMissing(type, listOf(shelf))
    }

    /**
     * 加载 [type] 里还没拿到结果的货架。[refresh] 为真时先清空该分类的结算标记，
     * 让整分类重来（下拉刷新只作用在当前分类）。
     */
    private fun load(type: SubjectType, refresh: Boolean) {
        loadJob?.cancel()
        val settled = settledShelves.getOrPut(type) { mutableSetOf() }
        if (refresh) {
            settled.clear()
            updateState { it.copy(refreshing = true) }
        }
        // 这一轮的货架与今日放送按同一天取：跨零点时 [init] 里取消本轮、按新的一天重来
        val today = currentState.today
        val shelves = currentState.layoutOf(type).shelves
        loadJob = viewModelScope.launch {
            val roundFailures = mutableListOf<Failure>()
            coroutineScope {
                shelves.forEach { shelf -> launchShelf(type, shelf, today, settled, refresh, roundFailures) }
            }
            updateState { it.copy(refreshing = false) }
            // 一轮结束才说：几个货架一起失败（多半是断网）只提示一次，不逐个报
            roundFailures.firstOrNull()?.let { failures.post(it, retry = ::refresh) }
        }
    }

    /** 布局里新出现的货架（刚用图钉放上来的、刚加回来的）：只取还没开始取的，不取消正在跑的这一轮。 */
    private fun loadAdded(type: SubjectType) = loadMissing(type, currentState.layoutOf(type).shelves)

    /**
     * 在一轮货架加载之外补取 [shelves] 里还没结算、也没在取的数据（新出现的货架、刚露面的方块货架），不取消正在跑的这一轮；
     * 失败与一轮货架一样只提示一次、重试是下拉刷新。
     */
    private fun loadMissing(type: SubjectType, shelves: List<ExploreShelf>) {
        val settled = settledShelves.getOrPut(type) { mutableSetOf() }
        val today = currentState.today
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val roundFailures = mutableListOf<Failure>()
            coroutineScope {
                shelves.forEach { shelf -> launchShelf(type, shelf, today, settled, refresh = false, roundFailures) }
            }
            roundFailures.firstOrNull()?.let { failures.post(it, retry = ::refresh) }
        }
    }

    /**
     * 取一条货架要的数据（[fetchesOf]）：搜索货架先出上次的结果（[SearchRepository.firstPage]），[refresh] 为真时强刷；
     * 今日放送取放送表里 [today] 那一天；方块露过面才取它要的作品或标志图。
     *
     * 普通露面时，结算过的、正在取的不再发起；显式刷新则由同一个 [FetchRound] owner 闸门取代当前轮，
     * 不等旧请求合作取消。旧轮即使迟到，也不能写回或把新轮标成结算。
     *
     * **完成的唯一判定就在这里**：只有收集正常结束才算这一轮结算（红线一）。
     * `cachedNetworkCall` 的收集正常返回 ⟺ 缓存命中（新鲜、提前 return）或网络轮已跑完；
     * 被取消时收集会抛 [kotlinx.coroutines.CancellationException]；不合作的旧请求由 owner 闸门截掉结果。
     * 因此「过期缓存先回显的那一拍就绪」不会被误当整轮完成——放行前切走即取消、
     * 不结算、切回补载；新鲜缓存提前 return 仍会结算，不会永远重复请求。
     * 出现的失败记进这一轮的 [roundFailures]，一轮结束统一提示。
     */
    private fun CoroutineScope.launchShelf(
        type: SubjectType,
        shelf: ExploreShelf,
        today: LocalDate,
        settled: MutableSet<String>,
        refresh: Boolean,
        roundFailures: MutableList<Failure>,
    ) {
        val running = inFlight.getOrPut(type) { mutableMapOf() }
        fetchesOf(type, shelf, today, refresh).forEach { fetch ->
            val previous = running[fetch.key]
            if (previous != null && (previous.input != fetch.input || refresh)) {
                previous.job?.cancel()
                settled -= fetch.key
                if (previous.input != fetch.input) {
                    // 同一位置换了取数条件，旧内容不属于新条件，不能经 Load.advance 留在新货架上。
                    updateState { it.withContent(type) { fetch.set(this, Load.Pending) } }
                }
            } else if (fetch.key in settled || previous?.job?.isActive == true) {
                return@forEach
            }
            val round = FetchRound(fetch.input)
            running[fetch.key] = round
            // 先登记本轮 owner，再立即执行到第一次挂起；紧接着再要一次时不会重复去取。
            round.job = launch(start = CoroutineStart.UNDISPATCHED) {
                fetch.collectInto(type, roundFailures) { running[fetch.key] === round }
                if (running[fetch.key] === round) settled += fetch.key
            }
        }
    }

    /**
     * 一条货架要取的那几份数据（唯一判定）：近期热门与搜索货架是一次搜索的第一页，今日放送是放送表里 [today] 那一天，
     * 方块货架只取露过面的那几块（[shownTiles]），按画法取每一块要的（[TileDrawing.needs]）。
     */
    private fun fetchesOf(type: SubjectType, shelf: ExploreShelf, today: LocalDate, refresh: Boolean): List<Fetch<*>> = when (shelf) {
        is ExploreShelf.Trending -> listOf(searchFetch(shelf.id, shelf.criteria, refresh))
        is ExploreShelf.Works -> when (val content = shelf.content) {
            is WorkShelfContent.Search -> listOf(searchFetch(shelf.id, content.criteria, refresh))
            WorkShelfContent.TodayOnAir -> listOf(
                Fetch(
                    key = shelf.id,
                    input = today,
                    source = { calendarRepository.getCalendar(refresh).map { load -> load.map { week -> week[today.dayOfWeek.value].orEmpty() } } },
                    get = { it.todayOnAir },
                    set = { load -> copy(todayOnAir = load) },
                ),
            )
        }
        is ExploreShelf.Tiles -> shownTiles[type].orEmpty().let { shown ->
            shelf.tiles.filter { tileWorksKey(shelf.id, it) in shown }.mapNotNull { tile -> tileFetch(shelf, tile, refresh) }
        }
    }

    private fun searchFetch(shelfId: String, criteria: SearchCriteria, refresh: Boolean): Fetch<List<SubjectBo>> = Fetch(
        key = shelfId,
        input = criteria,
        source = { searchRepository.firstPage(criteria, refresh) },
        get = { it.shelf(shelfId) },
        set = { load -> copy(shelves = shelves + (shelfId to load)) },
    )

    /** 方块货架里一块要取的：作品是这块条件的第一页（先出上次的结果），标志是公司人物条目的图；平台图形随包，色块什么都不取。 */
    private fun tileFetch(shelf: ExploreShelf.Tiles, tile: ExploreTile, refresh: Boolean): Fetch<*>? = when (shelf.drawing.needs) {
        TileNeed.NOTHING -> null
        TileNeed.WORKS -> tileWorksKey(shelf.id, tile).let { key ->
            Fetch(
                key = key,
                input = tile.criteria,
                source = { searchRepository.firstPage(tile.criteria, refresh) },
                get = { it.tileWorks(shelf.id, tile) },
                set = { load -> copy(tileWorks = tileWorks + (key to load)) },
            )
        }
        TileNeed.LOGO -> (tile.logo as? TileLogo.Company)?.personId?.let { personId ->
            Fetch(
                key = "logo:$personId",
                input = personId,
                source = { loadOnce { monoRepository.getPersonImage(personId) } },
                get = { it.logo(personId) },
                set = { load -> copy(logos = logos + (personId to load)) },
            )
        }
    }

    /**
     * 收一份数据，写进 [type] 那一份内容；失败记进这一轮的 [roundFailures]，一轮结束统一提示。
     * 当前 owner 的收集正常结束（缓存命中或网络轮跑完）才结算；被取代的轮次停止写回，调用方也会再核 owner。
     */
    private suspend fun <T> Fetch<T>.collectInto(
        type: SubjectType,
        roundFailures: MutableList<Failure>,
        isCurrent: () -> Boolean,
    ) {
        source().takeWhile { isCurrent() }.reportingFailure(roundFailures)
            .collectSection(failures = null, retry = null, { get(it.contentOf(type)) }) { load ->
                withContent(type) { set(load) }
            }
    }

    /** 一条货架的失败记进整轮的失败表，一轮结束统一提示。 */
    private fun <T> Flow<Load<T>>.reportingFailure(roundFailures: MutableList<Failure>): Flow<Load<T>> =
        onEach { load -> if (load is Load.Failed) roundFailures += load.failure }
}

/**
 * 一份要取的数据：一条货架的作品、今日放送、一块方块的作品或标志图。
 *
 * @property key 内容存放的位置（一个分类里唯一：货架 id、[tileWorksKey]、`logo:` 加人物 id）
 * @property input 这个位置的取数条件；位置相同而条件变了时取消旧一轮、清掉旧内容并重新取
 * @property get 它在一个分类的内容里的位置
 * @property set 把新的一拍写回那个位置
 */
private class Fetch<T>(
    val key: String,
    val input: Any,
    val source: suspend () -> Flow<Load<T>>,
    val get: (ExploreTypeContent) -> Load<T>,
    val set: ExploreTypeContent.(Load<T>) -> ExploreTypeContent,
)

/** 一个内容位置最近一轮的取数条件与任务；只有仍是当前位置 owner 的轮次能写回和结算。 */
private class FetchRound(val input: Any) {
    var job: Job? = null
}
