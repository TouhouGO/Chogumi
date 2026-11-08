package io.github.touhougo.chogumi.core.common.paging

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.model.BlockedUsers
import io.github.touhougo.chogumi.core.model.PagedResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 一份按位置续取的分页列表（位置是 offset、页码，或游标流的页号，由调用方的取页函数定）：已取到的条目、游标、首屏与续取各自的状态。
 * 是个值，放进页面状态里用（一页可以有好几份，也可以和筛选等别的状态放在一起）；取页只由 [Pager] 做。
 */
data class PagedList<T>(
    val items: List<T> = emptyList(),
    /** 服务端给出的总条数（过滤前）；小于等于 0 表示未知。 */
    val total: Int = 0,
    /** 下一页游标（数据层给出，不用列表长度反推：列表会去重、会因服务端过滤而变短）；null 表示已到末页。 */
    val nextOffset: Int? = null,
    /** 首屏在加载。 */
    val isLoading: Boolean = true,
    /** 首屏加载失败的原因（整页或整块报错 + 重试）；续取失败见 [loadMoreError]。 */
    val error: Failure? = null,
    val isLoadingMore: Boolean = false,
    /** 续取失败的原因：已取到的条目与游标都留着，页脚「重试」从同一页继续。 */
    val loadMoreError: Failure? = null,
    /** 用户下拉触发的重拉在途（[Pager.reload]）：旧条目还在屏幕上，只有下拉指示器在转。 */
    val isRefreshing: Boolean = false,
) {
    /** 是否还有下一页。空页但有后续游标时仍为真（中间整段被服务端跳过）。 */
    val hasMore: Boolean get() = nextOffset != null

    /**
     * 确认为空（画空态的唯一判定）：首屏落定、没出错、没有条目也没有下一页。
     * 过滤让一页没有可见条目、但还有下一页时不算空，页脚继续续取。
     */
    val isConfirmedEmpty: Boolean get() = !isLoading && error == null && items.isEmpty() && !hasMore
}

/**
 * 一次取全的列表当作没有下一页的一份分页列表来画：等待 → 首屏加载，取不到 → 首屏失败，就绪 → 全部条目。
 * 同一处画法里既有分页的、也有一次取全的列表时用（资料页收藏：角色 / 人物分页，条目一次取全）。
 */
fun <T> Load<List<T>>.asPagedList(): PagedList<T> = when (this) {
    Load.Pending -> PagedList(isLoading = true)
    is Load.Failed -> PagedList(isLoading = false, error = failure)
    is Load.Ready -> PagedList(items = value, total = value.size, isLoading = false)
}

/**
 * 要画的条目在页面跟着的绝交名单 [blocked] 下的样子（与 `Load.shownUnder` 同一条规则）：名单还没读到（null）时首屏仍算在加载、
 * 一条也不给（取不到照常给出），读到了按 [visible] 留下要显示的（一条在名单下怎么显示，null 是不显示）；分页游标与总数不变。
 */
fun <T> PagedList<T>.shownUnder(blocked: BlockedUsers?, visible: (T, BlockedUsers) -> T?): PagedList<T> =
    if (blocked == null) PagedList(isLoading = error == null, error = error) else copy(items = items.mapNotNull { visible(it, blocked) })

/**
 * 分页列表的首屏、重试、触底续取与「取完剩下的页」——全仓所有「一页页往下翻」的列表共用这一份。
 *
 * 状态是调用方页面状态里的一份 [PagedList]，经 [read] / [write] 读写，所以 ViewModel 的状态仍只有一份。
 * 不是线程安全的：全部调用（含 [read] / [write] 回调）必须落在同一个调度器上串行执行；跨线程用的调用方自己备一个串行调度器
 * （时间线的 feed 用 `feedDispatcher`）。
 * 同一时刻只有一路请求；[refresh] 作废还在路上的旧请求，旧结果不会覆盖新结果。
 * 续取失败只在页脚提示，由用户点重试（再调一次 [loadMore]），不自动重试。
 *
 * @param fetch 取一页（位置 → 一页）；筛选、分类等参数由调用方在里面读当前状态。
 * @param present 已取到的原始条目 → 显示的条目（按实体合并、去重……）；跨页累积后整体换算，默认原样。
 * @param fetchFirstShown [show] 换到一组本页面还没显示过的条件时取第一页用它（可以先用上次存下的那一页）；
 *   重试、再次提交同一组条件、下拉刷新、续取照旧走 [fetch]。null 时一律走 [fetch]。
 */
class Pager<T>(
    private val scope: CoroutineScope,
    private val read: () -> PagedList<T>,
    private val write: ((PagedList<T>) -> PagedList<T>) -> Unit,
    private val present: (List<T>) -> List<T> = { it },
    private val fetchFirstShown: (suspend () -> Outcome<PagedResult<T>>)? = null,
    private val fetch: suspend (offset: Int) -> Outcome<PagedResult<T>>,
) {
    /** 已向服务端取到的原始条目（未经 [present]），续取时接在后面。 */
    private var fetched: List<T> = emptyList()
    private var job: Job? = null
    private var generation = 0

    /**
     * 在途的重拉（[reload]，下拉与后台的都算）。它换掉第一页之前，屏幕上的条目和游标还属于旧列表：这时的 [loadMore]
     * 不发请求（旧游标的那页会接在新第一页后面），只记下 [Reload.loadMoreWanted]；重拉落定（成功或失败）后，
     * 在同一路里从那时的游标接着取一页。页脚只在进入待续取时要一次，丢掉这一次列表就停在那里不再往下翻。
     * 只有 [Reload.gen] 等于当前代次时算数：之后的 [refresh]、[show]、[edit]、[clear] 换了代次，它随之作废。
     * [PagedList.isRefreshing] 只管下拉指示器亮不亮，不当闸门用。
     */
    private var reloading: Reload? = null

    private class Reload(val gen: Int) {
        var loadMoreWanted = false
    }

    /** [show] 当前显示的那组条件；没经 [show] 显示过（或 [clear] 之后）为 null。 */
    private var shownKey: Any? = null

    /** 最近离开的几组条件的列表（[show] 换回来时用），按最近使用排，最多 [REMEMBERED_LISTS] 组。 */
    private val remembered = object : LinkedHashMap<Any, Remembered<T>>(REMEMBERED_LISTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Remembered<T>>) = size > REMEMBERED_LISTS
    }

    private class Remembered<T>(val list: PagedList<T>, val fetched: List<T>)

    /**
     * 换到 [key] 这组条件（搜索页切排序、切分类、改筛选）。本页面里最近离开的几组（[REMEMBERED_LISTS]）原样回来：
     * 条目、游标、已经翻过的页都在，不重取、不闪骨架；没取过的从第一页取（同 [refresh]，[keepItems] 也同；
     * 第一页经 [fetchFirstShown]）。再次提交同一组条件照旧重取（重试、重新搜索，经 [fetch]）。
     * 离开时首屏还没落定或首屏失败的列表不记。
     */
    fun show(key: Any, keepItems: Boolean = false) {
        val leaving = shownKey
        if (leaving == key) return refresh(keepItems)
        rememberShown()
        shownKey = key
        val back = remembered.remove(key) ?: return restart(keepItems, firstShow = true)
        job?.cancel()
        generation++
        fetched = back.fetched
        write { back.list }
    }

    /** 记下正在显示的那组条件的列表；还在续取、下拉刷新的，记成没在途的样子（游标没动，回来时页脚照常续取）。 */
    private fun rememberShown() {
        val key = shownKey ?: return
        val current = read()
        if (current.isLoading || current.error != null) return
        remembered[key] = Remembered(current.copy(isLoadingMore = false, isRefreshing = false), fetched)
    }

    /**
     * 从第一页重拉（首屏、重试、换筛选）。
     *
     * @param keepItems 换筛选时传 true：新一页到手前**旧条目留在原地**，到手后整体替换。
     *   若先清空成加载态，列表在页面里的高度骤降，外层滚动位置被压到底再弹回，整页跟着上下跳。
     *   首屏与重试清空（那时没有可留的内容）。
     */
    fun refresh(keepItems: Boolean = false) = restart(keepItems, firstShow = false)

    /** [refresh] 与 [show] 共用：[firstShow] 时第一页经 [fetchFirstShown]。 */
    private fun restart(keepItems: Boolean, firstShow: Boolean) {
        job?.cancel()
        val gen = ++generation
        fetched = emptyList()
        write {
            if (keepItems && it.items.isNotEmpty()) {
                it.copy(nextOffset = null, isLoadingMore = false, loadMoreError = null, error = null, isRefreshing = false)
            } else {
                PagedList(isLoading = true)
            }
        }
        job = scope.launch { load(offset = 0, gen, firstShow) }
    }

    /**
     * 下拉刷新：从第一页重拉，新一页到手前旧条目留在原地（[showIndicator] 为真时 [PagedList.isRefreshing] 期间指示器在转；
     * 用户没动手的后台重拉传 false，列表原样不动）。
     * 失败时旧条目与游标都留着，原因交给 [onFailure]（页面用提示条说），列表不变成整块报错；
     * 还没有条目时（首屏失败后下拉）与 [refresh] 相同。重拉在途时要的续取等它落定再取（见 [reloading]）。
     */
    fun reload(showIndicator: Boolean = true, onFailure: (Failure) -> Unit) {
        if (read().items.isEmpty()) return refresh()
        job?.cancel()
        val gen = ++generation
        val round = Reload(gen)
        reloading = round
        write { it.copy(isRefreshing = showIndicator, isLoadingMore = false, loadMoreError = null) }
        job = scope.launch {
            val result = fetch(0)
            if (gen != generation) return@launch
            reloading = null
            when (result) {
                is Outcome.Success -> accept(offset = 0, page = result.value)
                is Outcome.Failed -> {
                    write { it.copy(isRefreshing = false) }
                    onFailure(result.failure)
                }
            }
            if (round.loadMoreWanted) loadNext(gen)
        }
    }

    /** 等当前这一路请求（首屏、重拉、续取）结束；被新请求作废的也算结束。没有在途请求时立刻返回。 */
    suspend fun join() {
        job?.join()
    }

    /**
     * 本地改已取到的条目（删除、贴贴这类写穿）：[transform] 作用在原始条目上，再经 [present] 换算；
     * 还在路上的请求作废（它是操作之前发出的，回包会盖掉这次改动），游标不动，页脚照常续取。
     * 首屏还没有任何条目时什么都不做。
     */
    fun edit(transform: (List<T>) -> List<T>) {
        if (read().isLoading) return
        job?.cancel()
        generation++
        fetched = transform(fetched)
        write { it.copy(items = present(fetched), isRefreshing = false, isLoadingMore = false) }
    }

    /**
     * [present] 读到的外部条件变了（超展开的屏蔽名单）：已取到的条目按它重新换算，不重取、游标不动。
     * 变短了也不用管：页脚还在视野里时会自己续取下一页。
     */
    fun represent() {
        if (fetched.isEmpty()) return
        write { it.copy(items = present(fetched)) }
    }

    /** 有一路请求（首屏、重拉、续取）还在路上。 */
    val isActive: Boolean get() = job?.isActive == true

    /** 作废还在路上的请求，列表回到空（没有可取的东西时，如搜索条件清空）。离开的那组条件照样记下（见 [show]）。 */
    fun clear() {
        rememberShown()
        shownKey = null
        job?.cancel()
        generation++
        fetched = emptyList()
        write { PagedList(isLoading = false) }
    }

    /** 触底续取下一页；已到末页、首屏未落定或正在续取时什么都不做；重拉在途时记下，等它落定再取（见 [reloading]）。 */
    fun loadMore() {
        val state = read()
        val offset = state.nextOffset ?: return
        if (state.isLoading || state.isLoadingMore) return
        val pendingReload = reloading?.takeIf { it.gen == generation }
        if (pendingReload != null) {
            pendingReload.loadMoreWanted = true
            return
        }
        val gen = generation
        write { it.copy(isLoadingMore = true, loadMoreError = null) }
        job = scope.launch { load(offset, gen) }
    }

    /**
     * 把剩下的页取完（本地排序要全量时用）：排在正在进行的那一路后面，一页接一页；
     * 失败停在失败的那页（记在 [PagedList.loadMoreError]），[loadMore] 或再调一次本函数从那页继续。
     */
    fun loadAll() {
        val previous = job
        val gen = generation
        job = scope.launch {
            previous?.join()
            while (gen == generation) {
                if (!loadNext(gen)) break
            }
        }
    }

    /** 从当前游标续取一页（[loadAll] 与重拉落定后补的续取共用）；已到末页、失败或被作废时返回 false。 */
    private suspend fun loadNext(gen: Int): Boolean {
        val offset = read().nextOffset ?: return false
        write { it.copy(isLoadingMore = true, loadMoreError = null) }
        return load(offset, gen)
    }

    /** 取 [offset] 这一页落进状态；返回是否成功。被 [refresh] 作废的结果直接丢弃。[firstShow] 见 [restart]。 */
    private suspend fun load(offset: Int, gen: Int, firstShow: Boolean = false): Boolean {
        val result = fetchFirstShown?.takeIf { firstShow }?.invoke() ?: fetch(offset)
        if (gen != generation) return false
        return when (result) {
            is Outcome.Success -> {
                accept(offset, result.value)
                true
            }
            is Outcome.Failed -> {
                write {
                    if (offset == 0) {
                        // 首屏失败：换筛选时留着的旧条目属于旧条件，一并撤掉，整块报错 + 重试（重试 = [refresh]）
                        PagedList(isLoading = false, error = result.failure)
                    } else {
                        it.copy(isLoadingMore = false, loadMoreError = result.failure)
                    }
                }
                false
            }
        }
    }

    /** 取到的 [offset] 这一页落进状态：首页换掉已有条目，其余接在后面。 */
    private fun accept(offset: Int, page: PagedResult<T>) {
        fetched = if (offset == 0) page.items else fetched + page.items
        val shown = present(fetched)
        write {
            it.copy(
                items = shown,
                total = if (offset == 0) page.total else it.total,
                nextOffset = page.nextOffset,
                isLoading = false,
                error = null,
                isLoadingMore = false,
                loadMoreError = null,
                isRefreshing = false,
            )
        }
    }
}

/**
 * 整页就是一份分页列表的 ViewModel（社区的讨论版 / 评论 / 目录、资料页的日志、角色的关联人物）。
 * 页面还有筛选等别的状态时，不用它：在自己的状态里放一份 [PagedList]，配一个 [Pager]。
 * 首屏在构造时**不会**自动加载：子类在 `init` 里调 [loadFirstPage]。
 */
abstract class PagedListViewModel<T> : StateViewModel<PagedList<T>>(PagedList()) {

    /** 取一页；接口抛异常的仓库在这里套 `outcomeOf { }`。 */
    protected abstract suspend fun fetchPage(offset: Int): Outcome<PagedResult<T>>

    /** 原始条目 → 显示条目；默认原样。 */
    protected open fun present(fetched: List<T>): List<T> = fetched

    private val pager = Pager(viewModelScope, { currentState }, ::updateState, { present(it) }) { fetchPage(it) }

    fun retry() = pager.refresh()

    fun loadMore() = pager.loadMore()

    protected fun loadFirstPage() = pager.refresh()

    /** [present] 读到的外部条件变了：已取到的条目按它重算（[Pager.represent]）。 */
    protected fun represent() = pager.represent()
}

/** [Pager.show] 记住最近离开的几组条件（一个搜索页切排序五档、再加几次筛选够用；设计决定，not in M3）。 */
private const val REMEMBERED_LISTS = 8
